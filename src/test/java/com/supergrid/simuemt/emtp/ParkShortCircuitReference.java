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
        this(machine, re, xe, frequencyHz, v, i, speedHeld, regulator, null, stabilizer, governor);
    }

    /** The same, its field voltage driven by an ST1A {@code st1a} if not {@code null}, in place of the simplified regulator. */
    ParkShortCircuitReference(SubtransientMachineModel machine, double re, double xe, double frequencyHz, double[] v, double[] i,
                              boolean speedHeld, Regulator regulator, St1A st1a, Stabilizer stabilizer, Governor governor) {
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
        this.stabilizer = regulator == null && st1a == null ? null : stabilizer;
        this.governor = governor;
        double efd = xad * fieldCurrent;
        double vt0 = Math.hypot(vdq[0], vdq[1]);
        this.voltageReference = st1a != null ? vt0 + efd / st1a.ka() : regulator == null ? Double.NaN : vt0 + efd / regulator.gain();
        this.mechanicalPower = psid * iq - psiq * id;
        // The stator's flux linkages with the line's leakage added, the currents unchanged through the short.
        this.state = new double[] {psid - xe * id, -xad * id + (xad + d.fieldReactance()) * fieldCurrent, -xad * id + xad * fieldCurrent,
            psiq - xe * iq, -xaq * iq, -xaq * iq, 1.0, 0.0, efd, 0.0, 0.0, 0.0, mechanicalPower, mechanicalPower,
            vt0, efd / (st1a == null ? 1.0 : st1a.ka()), efd / (st1a == null ? 1.0 : st1a.ka()), efd};
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
        if (regulator != null || st1a != null) {
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

    /** The field voltage {@code Efd}: the regulator's state, or an ST1A's field voltage on the terminal voltage it moves. */
    private double fieldVoltage(double[] x) {
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
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
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
