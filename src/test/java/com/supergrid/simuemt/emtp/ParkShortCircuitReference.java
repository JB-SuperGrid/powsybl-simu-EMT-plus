/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.simuemt.extensions.machine.ClassicalDefinitions.RotorCircuit;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;

/**
 * A loaded machine shorted at the far end of its line, from Park's equations integrated here: the circuit the machine's data describe
 * under the exact definitions ({@link RotorCircuit#fromExact}), its field voltage held - or driven by upstream's simplified voltage
 * regulator ({@link Regulator}) -, per unit on its own base, generator convention, the q axis 90 degrees ahead of the d axis. Its
 * speed held at the synchronous one, or free: {@code 2H dw/dt = Pm/w - Te - D (w - 1)}, its mechanical power {@code Pm} held at the
 * steady state's, {@code Te = psid iq - psiq id} - the swing equation upstream's backends integrate, and EMTP's machine with the
 * turbine's power held and its damping {@code DSR}.
 *
 * <p>The line, a series {@code Re + jXe} rotating with the stator's frame, is in the dq frame a leakage like the stator's own: the short
 * at its far end puts it in series with the stator, so the circuit integrated is the machine's with {@code Ra + Re} and {@code Xl + Xe}
 * and nothing at its terminal, and the machine's terminal voltage is the line's drop, {@code Re i + (Xe/w0) di/dt + jwXe i}. The states
 * are the six windings' flux linkages, the speed and the rotor's angle ahead of the synchronous frame, integrated by the classical
 * fourth-order Runge-Kutta method.
 *
 * <p>Before the short the machine is in the steady state of its terminal voltage and current: its q axis along {@code V + (Ra + jXq)
 * I}, no damper current, its field current {@code (psid + Xd id)/Xad} and its field voltage the one that drives it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class ParkShortCircuitReference {

    private final double omega;
    private final double re;
    private final double xe;
    private final double ra;
    private final RotorCircuit d;
    private final RotorCircuit q;
    /** Each axis's currents from its flux linkages - stator, then its two rotor windings. */
    private final double[][] dInverse;
    private final double[][] qInverse;
    /** The field winding's voltage per unit of field voltage {@code Efd}: {@code Rf / Xad}, the steady field current {@code Efd /
     * Xad} through the field's resistance. */
    private final double fieldVoltagePerEfd;
    private final Regulator regulator;
    private final St1A st1a;
    private final Dc1A dc1a;
    private final Ac1A ac1a;
    private final Ac6A ac6a;
    private final Stabilizer stabilizer;
    private final Governor governor;
    private final double voltageReference;
    private final boolean speedHeld;
    private final double inertia;
    private final double damping;
    private final double mechanicalPower;
    private final double[] preFaultTerminal;
    private double[] state;

    /**
     * Upstream's simplified voltage regulator ({@code SimplifiedAvrModel}): {@code Efd = Ka / (1 + sTa) (Vref - Vt)}, held within
     * {@code [min, max]} - the lag's state, not only its output, as a limited TACS s-block and EMTP's {@code f(s)} with limits hold it
     * -, {@code Vt} the terminal voltage's magnitude, {@code Vref = Vt0 + Efd0 / Ka}, at equilibrium before the short.
     */
    record Regulator(double gain, double timeConstant, double min, double max) {
    }

    /**
     * Upstream's ST1A, as ATP's TACS writes it: {@code VC = Vt / (1 + sTr)}; {@code VI = clamp(Vref + Vs - VC - VF, VIMIN, VIMAX)};
     * the lead-lags {@code (1 + sTc) / (1 + sTb)}, {@code (1 + sTc1) / (1 + sTb1)}, a stage of {@code Tb = 0} left out; {@code VA =
     * Ka / (1 + sTa)}, its state within {@code [VAMIN, VAMAX]}; {@code FC = VA - Klr max(Ifd - Ilr, 0)}; {@code EF = clamp(FC, Vt
     * VRMIN, Vt VRMAX - Kc Ifd)} on the unfiltered terminal voltage, {@code Ifd} the field current per unit of the air-gap line's -
     * {@code Xad} times the reciprocal per-unit one -; {@code VF = Kf s / (1 + sTf) EF}, left out when {@code Kf = 0}. Its transducer
     * and its regulator lags are taken as given ({@code Tr}, {@code Ta} positive). {@code EF} reads the terminal voltage that the
     * field voltage moves through the line's {@code Xe di/dt}: solved by three fixed-point passes from the transducer's.
     */
    record St1A(double tr, double viMax, double viMin, double tc, double tb, double tc1, double tb1, double ka, double ta, double vaMax,
                double vaMin, double vrMax, double vrMin, double kc, double kf, double tf, double klr, double ilr) {
    }

    /**
     * Upstream's DC1A, as ATP's TACS writes it: {@code Vr = Ka / (1 + sTa) (Vref + Vs - Vt - VF)}, its state within {@code [vrMin,
     * vrMax]}; {@code Te dEfd/dt = Vr - (Ke + Aex exp(Bex Efd)) Efd}; {@code VF = Kf s / (1 + sTf) Efd}, left out when {@code Kf = 0};
     * {@code Vref = Vt0 + Vr0 / Ka}.
     */
    record Dc1A(double ka, double ta, double vrMin, double vrMax, double ke, double te, double kf, double tf, double aex, double bex) {
    }

    /**
     * Upstream's AC1A, as ATP's TACS writes it: {@code VC = Vt / (1 + sTr)}; {@code VA = Ka / (1 + sTa) (1 + sTc) / (1 + sTb) (Vref + Vs
     * - VC - VF)}, its state within {@code [vaMin, vaMax]}, a lead-lag of {@code Tb = 0} left out; {@code VB = clamp(VA, vrMin, vrMax)};
     * {@code Te dVe/dt = VB - Ve (Ke + Aex exp(Bex Ve)) - Kd Ifd}; {@code Efd = max(Ve, 1e-3) FEX(IN)}, {@code IN = min(Kc Ifd /
     * max(Ve, 1e-3), 1)}, the rectifier's three arcs blended by ATP's smooth steps; {@code VF = Kf s / (1 + sTf) Ve}; {@code Vref = Vt0 +
     * Vr0 / Ka}, at rest on {@code Ve0}.
     */
    record Ac1A(double tr, double tc, double tb, double ka, double ta, double vaMax, double vaMin, double te, double vrMax, double vrMin, double kf,
                double tf, double kc, double kd, double ke, double aex, double bex, double ve0) {
    }

    /**
     * Upstream's AC6A, as ATP's TACS writes it, at rest on upstream's {@code Ac6AInitialState} ({@code reference}, {@code voltage},
     * {@code regulator}, {@code feedback}, {@code exciterVoltage}): {@code e = Vref + Vs - VC}; {@code KA (1 + sTK) / (1 + sTA)}; Dynawo's
     * limited lead-lag {@code (1 + sTC) / (1 + sTB)} within {@code [vaMin, vaMax]}; {@code VFE = KE VE + Aex exp(Bex VE) VE + KD Ifd};
     * {@code VH = (1 + sTJ) / (1 + sTH) clamp(KH (VFE - VFELIM), 0, VHMAX)}; {@code VR = clamp(VA - VH, Vt vrMin, Vt vrMax)}; {@code TE
     * dVE/dt = (VR - VFE)}, cut by ATP's limiter step while {@code VE} is at 0 and falling; AC1A's rectifier.
     */
    record Ac6A(double tr, double ka, double ta, double tk, double tb, double tc, double vaMax, double vaMin, double vrMax, double vrMin, double te,
                double ke, double kc, double kd, double kh, double tj, double th, double vfeLim, double vhMax, double aex, double bex, double reference,
                double voltage, double regulator, double feedback, double exciterVoltage) {
    }

    /**
     * Upstream's PSS1A, its output added to the regulator's error: the speed deviation {@code w - 1} through the washout {@code Ks sTw
     * / (1 + sTw)} and the lead-lags {@code (1 + sT1) / (1 + sT2)}, {@code (1 + sT3) / (1 + sT4)}, all at rest at 0, held within
     * {@code [-limit, limit]} - a clamp on the output, as ATP's after the second lead-lag and EMTP's static limiter.
     */
    record Stabilizer(double gain, double washoutTimeConstant, double t1, double t2, double t3, double t4, double limit) {
    }

    /**
     * Upstream's TGOV1, driving the mechanical power: its valve {@code 1 / (1 + sT1)} on {@code Pm0 - dw / R}, its state held within
     * {@code [valveMin, valveMax]}, its turbine {@code (1 + sT2) / (1 + sT3)}, {@code Pm = turbine - Dt dw}, every state at {@code Pm0}.
     */
    record Governor(double droop, double t1, double valveMin, double valveMax, double t2, double t3, double damping) {
    }

    /**
     * The machine {@code machine}, its line {@code re + j xe} per unit, at {@code frequencyHz}, before the short at terminal voltage
     * {@code v} and current {@code i} - generator convention -, each a complex number in any common reference; its speed held or
     * free.
     */
    ParkShortCircuitReference(SubtransientMachineModel machine, double re, double xe, double frequencyHz, double[] v, double[] i,
                              boolean speedHeld) {
        this(machine, re, xe, frequencyHz, v, i, speedHeld, null, null, null);
    }

    /** The same, its field voltage driven by {@code regulator} - held if {@code null} -, {@code stabilizer} adding to its error - none if
     * {@code null} -, its mechanical power driven by {@code governor} - held if {@code null}. */
    ParkShortCircuitReference(SubtransientMachineModel machine, double re, double xe, double frequencyHz, double[] v, double[] i,
                              boolean speedHeld, Regulator regulator, Stabilizer stabilizer, Governor governor) {
        this(machine, re, xe, frequencyHz, v, i, speedHeld, regulator, null, null, null, null, stabilizer, governor);
    }

    /** The same, its field voltage driven by an ST1A {@code st1a} if not {@code null}, in place of the simplified regulator. */
    ParkShortCircuitReference(SubtransientMachineModel machine, double re, double xe, double frequencyHz, double[] v, double[] i,
                              boolean speedHeld, Regulator regulator, St1A st1a, Dc1A dc1a, Ac1A ac1a, Ac6A ac6a, Stabilizer stabilizer,
                              Governor governor) {
        this.omega = 2.0 * Math.PI * frequencyHz;
        this.re = re;
        this.xe = xe;
        this.ra = machine.getStatorResistance();
        this.speedHeld = speedHeld;
        this.inertia = machine.getInertiaConstant();
        this.damping = machine.getDamping();
        double xl = machine.getStatorLeakageReactance();
        this.d = RotorCircuit.fromExact(machine.getDirectSynchronousReactance(), xl, machine.getDirectTransientReactance(),
                machine.getDirectSubtransientReactance(), machine.getOpenCircuitTransientTimeConstant(),
                machine.getDirectOpenCircuitSubtransientTimeConstant(), "d");
        this.q = RotorCircuit.fromExact(machine.getQuadratureSynchronousReactance(), xl, machine.getQuadratureTransientReactance(),
                machine.getQuadratureSubtransientReactance(), machine.getQuadratureOpenCircuitTransientTimeConstant(),
                machine.getQuadratureOpenCircuitSubtransientTimeConstant(), "q");
        this.dInverse = invert(inductances(d, xl + xe));
        this.qInverse = invert(inductances(q, xl + xe));

        // The rotor's frame: the q axis along V + (Ra + jXq) I, made the imaginary axis.
        double eqRe = v[0] + ra * i[0] - machine.getQuadratureSynchronousReactance() * i[1];
        double eqIm = v[1] + ra * i[1] + machine.getQuadratureSynchronousReactance() * i[0];
        double turn = Math.PI / 2.0 - Math.atan2(eqIm, eqRe);
        double[] vdq = rotate(v, turn);
        double[] idq = rotate(i, turn);
        this.preFaultTerminal = vdq;
        double id = idq[0];
        double iq = idq[1];
        double psiq = -vdq[0] - ra * id;
        double psid = vdq[1] + ra * iq;
        double xad = d.magnetizingReactance();
        double xaq = q.magnetizingReactance();
        double fieldCurrent = (psid + (xl + xad) * id) / xad;
        this.fieldVoltagePerEfd = d.fieldResistance() / omega / xad;
        this.regulator = regulator;
        this.st1a = st1a;
        this.dc1a = dc1a;
        this.ac1a = ac1a;
        this.ac6a = ac6a;
        this.stabilizer = regulator == null && st1a == null && dc1a == null && ac1a == null && ac6a == null ? null : stabilizer;
        this.governor = governor;
        double efd = xad * fieldCurrent;
        double vt0 = Math.hypot(vdq[0], vdq[1]);
        double vr0 = dc1a != null ? (dc1a.ke() + dc1a.aex() * Math.exp(dc1a.bex() * efd)) * efd
                : ac1a != null ? ac1a.ve0() * (ac1a.ke() + ac1a.aex() * Math.exp(ac1a.bex() * ac1a.ve0())) + ac1a.kd() * efd : 0.0;
        this.voltageReference = ac6a != null ? ac6a.reference() : ac1a != null ? vt0 + vr0 / ac1a.ka() : dc1a != null ? vt0 + vr0 / dc1a.ka() : st1a != null ? vt0 + efd / st1a.ka()
                : regulator == null ? Double.NaN : vt0 + efd / regulator.gain();
        this.mechanicalPower = psid * iq - psiq * id;
        // The stator's flux linkages with the line's leakage added, the currents unchanged through the short.
        this.state = new double[] {psid - xe * id, -xad * id + (xad + d.fieldReactance()) * fieldCurrent, -xad * id + xad * fieldCurrent,
            psiq - xe * iq, -xaq * iq, -xaq * iq, 1.0, 0.0, efd, 0.0, 0.0, 0.0, mechanicalPower, mechanicalPower,
            vt0, efd / (st1a == null ? 1.0 : st1a.ka()), efd / (st1a == null ? 1.0 : st1a.ka()), efd, efd};
        if (dc1a != null) {
            // The regulator x[8] at Vr0, the field voltage x[18] at Efd0.
            state[8] = vr0;
        }
        if (ac6a != null) {
            // The transducer x[14], KA (1 + sTK)/(1 + sTA)'s lag x[15] at the error, the limited lead-lag's state x[16], the limiter's
            // lead-lag's lag x[17], the exciter x[18].
            double error0 = ac6a.reference() - ac6a.voltage();
            state[15] = error0;
            state[16] = ac6a.tc() > 0.0 ? (ac6a.tc() - ac6a.tb()) / ac6a.tc() * ac6a.regulator() : 0.0;
            state[8] = ac6a.regulator();
            state[17] = ac6a.feedback();
            state[18] = ac6a.exciterVoltage();
        }
        if (ac1a != null) {
            // The regulator x[8] at Vr0, the lead-lag's lag x[15] at Vr0 / Ka, the rate feedback's lag x[17] and the exciter x[18] at Ve0.
            state[8] = vr0;
            state[15] = vr0 / ac1a.ka();
            state[17] = ac1a.ve0();
            state[18] = ac1a.ve0();
        }
    }

    /** The terminal voltage before the short in the rotor's frame, {@code {vd, vq}}. */
    double[] preFaultTerminalVoltage() {
        return preFaultTerminal.clone();
    }

    /**
     * The terminal voltage in the rotor's frame and the rotor's angle ahead of the synchronous frame, {@code {vd, vq, delta}}, at every {@code sampleStep} through {@code duration} seconds after
     * the short, integrated at {@code step}.
     */
    double[][] terminalVoltages(double duration, double sampleStep, double step) {
        int perSample = (int) Math.round(sampleStep / step);
        int samples = (int) Math.round(duration / sampleStep);
        double[][] voltages = new double[samples][];
        for (int n = 0; n < samples; n++) {
            for (int k = 0; k < perSample; k++) {
                advance(step);
            }
            double[] terminal = terminal(state, rates(state, fieldVoltage(state)));
            voltages[n] = new double[] {terminal[0], terminal[1], state[7]};
        }
        return voltages;
    }

    private void advance(double h) {
        double[] k1 = derivative(state);
        double[] k2 = derivative(add(state, k1, h / 2));
        double[] k3 = derivative(add(state, k2, h / 2));
        double[] k4 = derivative(add(state, k3, h));
        for (int j = 0; j < state.length; j++) {
            state[j] += h / 6 * (k1[j] + 2 * k2[j] + 2 * k3[j] + k4[j]);
        }
    }

    /** The states' rates, per second: the windings', the rotor's; and the field voltage's, the regulator's lag held at its limits,
     * none without one. */
    private double[] derivative(double[] x) {
        double fieldVoltage = fieldVoltage(x);
        double[] rates = rates(x, fieldVoltage);
        if (governor != null) {
            // The valve, then the turbine's lag: x[12], x[13].
            double deviation = x[6] - 1.0;
            double valveRate = (mechanicalPower - deviation / governor.droop() - x[12]) / governor.t1();
            if (x[12] >= governor.valveMax() && valveRate > 0.0 || x[12] <= governor.valveMin() && valveRate < 0.0) {
                valveRate = 0.0;
            }
            rates[12] = valveRate;
            rates[13] = (x[12] - x[13]) / governor.t3();
        }
        if (regulator != null || st1a != null || dc1a != null || ac1a != null || ac6a != null) {
            double[] terminal = terminal(x, rates);
            double stabilizing = 0.0;
            if (stabilizer != null) {
                // The washout's lag of the speed deviation, then each lead-lag's lag: x[9], x[10], x[11].
                double deviation = x[6] - 1.0;
                double washout = stabilizer.gain() * (deviation - x[9]);
                double first = x[10] + stabilizer.t1() / stabilizer.t2() * (washout - x[10]);
                double second = x[11] + stabilizer.t3() / stabilizer.t4() * (first - x[11]);
                rates[9] = (deviation - x[9]) / stabilizer.washoutTimeConstant();
                rates[10] = (washout - x[10]) / stabilizer.t2();
                rates[11] = (first - x[11]) / stabilizer.t4();
                stabilizing = Math.max(-stabilizer.limit(), Math.min(stabilizer.limit(), second));
            }
            if (st1a != null) {
                st1aRates(x, rates, Math.hypot(terminal[0], terminal[1]), fieldVoltage, stabilizing);
                return rates;
            }
            if (ac1a != null) {
                ac1aRates(x, rates, Math.hypot(terminal[0], terminal[1]), stabilizing);
                return rates;
            }
            if (ac6a != null) {
                ac6aRates(x, rates, Math.hypot(terminal[0], terminal[1]), stabilizing);
                return rates;
            }
            if (dc1a != null) {
                boolean feedback = dc1a.kf() != 0.0 && dc1a.tf() > 0.0;
                double rateFeedback = feedback ? dc1a.kf() / dc1a.tf() * (x[18] - x[17]) : 0.0;
                if (feedback) {
                    rates[17] = (x[18] - x[17]) / dc1a.tf();
                }
                double vrRate = (dc1a.ka() * (voltageReference + stabilizing - Math.hypot(terminal[0], terminal[1]) - rateFeedback) - x[8]) / dc1a.ta();
                if (x[8] >= dc1a.vrMax() && vrRate > 0.0 || x[8] <= dc1a.vrMin() && vrRate < 0.0) {
                    vrRate = 0.0;
                }
                rates[8] = vrRate;
                rates[18] = (x[8] - (dc1a.ke() + dc1a.aex() * Math.exp(dc1a.bex() * x[18])) * x[18]) / dc1a.te();
                return rates;
            }
            double efdRate = (regulator.gain() * (voltageReference + stabilizing - Math.hypot(terminal[0], terminal[1])) - x[8]) / regulator.timeConstant();
            if (x[8] >= regulator.max() && efdRate > 0.0 || x[8] <= regulator.min() && efdRate < 0.0) {
                efdRate = 0.0;
            }
            rates[8] = efdRate;
        }
        return rates;
    }

    /** ST1A's states' rates: its transducer x[14], its lead-lags' lags x[15] and x[16], its rate feedback's lag x[17], its regulator
     * x[8] held at its limits. */
    private void st1aRates(double[] x, double[] rates, double vt, double fieldVoltage, double stabilizing) {
        rates[14] = (vt - x[14]) / st1a.tr();
        double feedback = st1a.kf() != 0.0 && st1a.tf() > 0.0 ? st1a.kf() / st1a.tf() * (fieldVoltage - x[17]) : 0.0;
        if (st1a.kf() != 0.0 && st1a.tf() > 0.0) {
            rates[17] = (fieldVoltage - x[17]) / st1a.tf();
        }
        double input = Math.max(st1a.viMin(), Math.min(st1a.viMax(), voltageReference + stabilizing - x[14] - feedback));
        double shaped = input;
        if (st1a.tb() > 0.0) {
            rates[15] = (shaped - x[15]) / st1a.tb();
            shaped = x[15] + st1a.tc() / st1a.tb() * (shaped - x[15]);
        }
        if (st1a.tb1() > 0.0) {
            rates[16] = (shaped - x[16]) / st1a.tb1();
            shaped = x[16] + st1a.tc1() / st1a.tb1() * (shaped - x[16]);
        }
        double vaRate = (st1a.ka() * shaped - x[8]) / st1a.ta();
        if (x[8] >= st1a.vaMax() && vaRate > 0.0 || x[8] <= st1a.vaMin() && vaRate < 0.0) {
            vaRate = 0.0;
        }
        rates[8] = vaRate;
    }

    /** AC1A's states' rates: its transducer x[14], its lead-lag's lag x[15], its rate feedback's lag x[17], its regulator x[8] held
     * at its limits, its exciter x[18]. */
    private void ac1aRates(double[] x, double[] rates, double vt, double stabilizing) {
        double fieldCurrent = d.magnetizingReactance() * multiply(dInverse, x[0], x[1], x[2])[1];
        rates[14] = ac1a.tr() > 0.0 ? (vt - x[14]) / ac1a.tr() : 0.0;
        double measured = ac1a.tr() > 0.0 ? x[14] : vt;
        boolean feedback = ac1a.kf() != 0.0 && ac1a.tf() > 0.0;
        double rateFeedback = feedback ? ac1a.kf() / ac1a.tf() * (x[18] - x[17]) : 0.0;
        if (feedback) {
            rates[17] = (x[18] - x[17]) / ac1a.tf();
        }
        double shaped = voltageReference + stabilizing - measured - rateFeedback;
        if (ac1a.tb() > 0.0) {
            rates[15] = (shaped - x[15]) / ac1a.tb();
            shaped = x[15] + ac1a.tc() / ac1a.tb() * (shaped - x[15]);
        }
        double vaRate = (ac1a.ka() * shaped - x[8]) / ac1a.ta();
        if (x[8] >= ac1a.vaMax() && vaRate > 0.0 || x[8] <= ac1a.vaMin() && vaRate < 0.0) {
            vaRate = 0.0;
        }
        rates[8] = vaRate;
        double drive = Math.max(ac1a.vrMin(), Math.min(ac1a.vrMax(), x[8]));
        rates[18] = (drive - x[18] * (ac1a.ke() + ac1a.aex() * Math.exp(ac1a.bex() * x[18])) - ac1a.kd() * fieldCurrent) / ac1a.te();
    }

    /** AC6A's states' rates: its transducer x[14], its regulator's lag x[15], its limited lead-lag's state x[16], its limiter's
     * lead-lag's lag x[17], its exciter x[18]. */
    private void ac6aRates(double[] x, double[] rates, double vt, double stabilizing) {
        double fieldCurrent = d.magnetizingReactance() * multiply(dInverse, x[0], x[1], x[2])[1];
        rates[14] = ac6a.tr() > 0.0 ? (vt - x[14]) / ac6a.tr() : 0.0;
        double measured = ac6a.tr() > 0.0 ? x[14] : vt;
        double error = ac6a.reference() + stabilizing - measured;
        double amplified = ac6a.ka() * error;
        if (ac6a.ta() > 0.0) {
            rates[15] = (error - x[15]) / ac6a.ta();
            amplified = ac6a.ka() * (x[15] + ac6a.tk() / ac6a.ta() * (error - x[15]));
        }
        double va;
        if (ac6a.tb() <= 0.0) {
            va = Math.max(ac6a.vaMin(), Math.min(ac6a.vaMax(), amplified));
        } else if (ac6a.tc() <= 0.0) {
            double rate = (amplified - x[8]) / ac6a.tb();
            rates[8] = x[8] >= ac6a.vaMax() && rate > 0.0 || x[8] <= ac6a.vaMin() && rate < 0.0 ? 0.0 : rate;
            va = x[8];
        } else {
            double feedback = (ac6a.tc() - ac6a.tb()) / ac6a.tc();
            va = Math.max(ac6a.vaMin(), Math.min(ac6a.vaMax(), ac6a.tc() / ac6a.tb() * (amplified - x[16])));
            rates[16] = (feedback * va - x[16]) / ac6a.tc();
        }
        double ve = x[18];
        double vfe = ac6a.ke() * ve + ac6a.aex() * Math.exp(ac6a.bex() * ve) * ve + ac6a.kd() * fieldCurrent;
        double limited = Math.max(0.0, Math.min(ac6a.vhMax(), ac6a.kh() * (vfe - ac6a.vfeLim())));
        double vh = limited;
        if (ac6a.th() > 0.0) {
            rates[17] = (limited - x[17]) / ac6a.th();
            vh = x[17] + ac6a.tj() / ac6a.th() * (limited - x[17]);
        }
        double vr = Math.min(Math.max(va - vh, vt * ac6a.vrMin()), vt * ac6a.vrMax());
        double rate = vr - vfe;
        rates[18] = rate * (1.0 - limiterStep(-ve) * (1.0 - limiterStep(rate))) / ac6a.te();
    }

    /** ATP's limiter step {@code 0.5 (1 + x / sqrt(x^2 + 1e-8))}. */
    private static double limiterStep(double x) {
        return 0.5 * (1.0 + x / Math.sqrt(x * x + 1.0e-8));
    }

    /** An AC exciter's field voltage: {@code max(Ve, 1e-3) FEX(IN)}, {@code IN = min(Kc Ifd / max(Ve, 1e-3), 1)}, the rectifier's
     * three arcs blended by ATP's smooth steps. */
    private double rectified(double exciterVoltage, double kc, double[] x) {
        double ve = Math.max(exciterVoltage, 1.0e-3);
        if (kc == 0.0) {
            return ve;
        }
        double fieldCurrent = d.magnetizingReactance() * multiply(dInverse, x[0], x[1], x[2])[1];
        double in = Math.min(kc * fieldCurrent / ve, 1.0);
        double s1 = smoothStep(in, 0.433);
        double s2 = smoothStep(in, 0.75);
        double arc = Math.sqrt(Math.max(0.75 - in * in, 0.0));
        return ve * ((1.0 - s1) * (1.0 - 0.577 * in) + s1 * (1.0 - s2) * arc + s2 * 1.732 * (1.0 - in));
    }

    /** ATP's smooth unit step at {@code threshold}. */
    private static double smoothStep(double x, double threshold) {
        double d = x - threshold;
        return 0.5 * (1.0 + d / Math.sqrt(d * d + 1.0e-6));
    }

    /** The field voltage {@code Efd}: the regulator's state, or an ST1A's field voltage on the terminal voltage it moves. */
    private double fieldVoltage(double[] x) {
        if (ac1a != null) {
            return rectified(x[18], ac1a.kc(), x);
        }
        if (ac6a != null) {
            return rectified(x[18], ac6a.kc(), x);
        }
        if (dc1a != null) {
            return x[18];
        }
        if (st1a == null) {
            return x[8];
        }
        double fieldCurrent = d.magnetizingReactance() * multiply(dInverse, x[0], x[1], x[2])[1];
        double limited = x[8] - 0.5 * st1a.klr() * (fieldCurrent - st1a.ilr() + Math.abs(fieldCurrent - st1a.ilr()));
        double vt = x[14];
        double fieldVoltage = x[8];
        for (int pass = 0; pass < 3; pass++) {
            fieldVoltage = Math.min(Math.max(limited, vt * st1a.vrMin()), vt * st1a.vrMax() - st1a.kc() * fieldCurrent);
            double[] terminal = terminal(x, rates(x, fieldVoltage));
            vt = Math.hypot(terminal[0], terminal[1]);
        }
        return Math.min(Math.max(limited, vt * st1a.vrMin()), vt * st1a.vrMax() - st1a.kc() * fieldCurrent);
    }

    /** The windings' and the rotor's rates, per second: the stator shorted behind {@code Ra + Re} at the rotor's speed, the field
     * driven at {@code Efd Rf / Xad}, the dampers free; the swing equation, unless the speed is held. The line's leakage drops out of
     * the torque, {@code (psid + Xe id) iq - (psiq + Xe iq) id}. */
    private double[] rates(double[] x, double fieldVoltage) {
        double[] di = multiply(dInverse, x[0], x[1], x[2]);
        double[] qi = multiply(qInverse, x[3], x[4], x[5]);
        double w = x[6];
        double turbinePower = governor == null ? mechanicalPower
                : x[13] + governor.t2() / governor.t3() * (x[12] - x[13]) - governor.damping() * (w - 1.0);
        double acceleration = speedHeld ? 0.0 : (turbinePower / w - (x[0] * qi[0] - x[3] * di[0]) - damping * (w - 1.0)) / (2.0 * inertia);
        return new double[] {
            omega * ((ra + re) * di[0] + w * x[3]),
            omega * fieldVoltagePerEfd * fieldVoltage - d.fieldResistance() * di[1],
            -d.damperResistance() * di[2],
            omega * ((ra + re) * qi[0] - w * x[0]),
            -q.fieldResistance() * qi[1],
            -q.damperResistance() * qi[2],
            acceleration,
            omega * (w - 1.0),
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    }

    /** The line's drop, {@code Re i + (Xe/w0) di/dt + jwXe i}. */
    private double[] terminal(double[] x, double[] rate) {
        double[] di = multiply(dInverse, x[0], x[1], x[2]);
        double[] qi = multiply(qInverse, x[3], x[4], x[5]);
        double idRate = multiply(dInverse, rate[0], rate[1], rate[2])[0];
        double iqRate = multiply(qInverse, rate[3], rate[4], rate[5])[0];
        double w = x[6];
        return new double[] {re * di[0] + xe / omega * idRate - w * xe * qi[0], re * qi[0] + xe / omega * iqRate + w * xe * di[0]};
    }

    /** An axis's flux linkages from its currents: the stator's (generator convention, behind {@code statorLeakage}), then its rotor's. */
    private static double[][] inductances(RotorCircuit rotor, double statorLeakage) {
        double xa = rotor.magnetizingReactance();
        return new double[][] {
            {-(statorLeakage + xa), xa, xa},
            {-xa, xa + rotor.fieldReactance(), xa},
            {-xa, xa, xa + rotor.damperReactance()}};
    }

    private static double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] inverse = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                // The cofactor of (c, r) over the determinant.
                int r1 = (c + 1) % 3;
                int r2 = (c + 2) % 3;
                int c1 = (r + 1) % 3;
                int c2 = (r + 2) % 3;
                inverse[r][c] = (m[r1][c1] * m[r2][c2] - m[r1][c2] * m[r2][c1]) / det;
            }
        }
        return inverse;
    }

    private static double[] multiply(double[][] m, double a, double b, double c) {
        return new double[] {m[0][0] * a + m[0][1] * b + m[0][2] * c, m[1][0] * a + m[1][1] * b + m[1][2] * c,
            m[2][0] * a + m[2][1] * b + m[2][2] * c};
    }

    private static double[] add(double[] x, double[] rate, double h) {
        double[] y = new double[x.length];
        for (int j = 0; j < x.length; j++) {
            y[j] = x[j] + h * rate[j];
        }
        return y;
    }

    private static double[] rotate(double[] z, double angle) {
        return new double[] {z[0] * Math.cos(angle) - z[1] * Math.sin(angle), z[0] * Math.sin(angle) + z[1] * Math.cos(angle)};
    }
}
