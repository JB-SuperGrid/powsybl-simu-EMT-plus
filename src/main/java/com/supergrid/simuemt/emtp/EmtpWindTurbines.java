/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.wind.Wt4bInitialState;
import com.powsybl.simuemt.extensions.wind.Wt4bLogic;
import com.powsybl.simuemt.extensions.wind.Wt4bModel;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.integrator;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.scoped;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.step;

/**
 * A {@link Wt4bModel} wind turbine in EMTP, Dynawo's WT4B, by the equations every backend writes ({@link Wt4bLogic}): in pu of its rating,
 * generator convention, its currents {@code (id, iq)} the injector's - a positive {@code iq} producing reactive power. Blocked, its bus has
 * an RC to earth on each phase from the start, upstream's ({@link NumericalAids#WIND_TURBINE_BUS_SNUBBER_F}, {@link
 * NumericalAids#WIND_TURBINE_BUS_SNUBBER_OHM}), which holds it once its breaker has let go.
 * <ul>
 *     <li>The PLL on its terminal's Park transform, {@code wdev = Kp vq + I}, {@code I} the integral of {@code Ki vq} held within {@code
 *     [OmegaMin - 1, OmegaMax - 1]}.</li>
 *     <li>REEC_A: its fault ride-through while the voltage is out of {@code [VDip, VUp]}, freezing what integrates; the reactive power
 *     reference the load flow's within {@code [QMin, QMax]}, over the filtered voltage through {@code tIq}; the injection {@code Kqv} times
 *     the voltage's deviation past its dead band within {@code [Iql1, Iqh1]}, held at {@code IqFrz} - or on the voltage - for {@code tHoldIq}
 *     after a dip; the active current the power order over the voltage; the current limits on the filtered voltage's tables and {@code
 *     IMax}, the reactive current first unless {@code PQFlag}, the active one's held {@code tHoldIpMax} after a dip.</li>
 *     <li>REGC_A: the converter's lag on each current, the active one's rise at most {@code Rrpwr}, within the low voltage power logic's
 *     limit if on; the reactive one's rate limited after a dip the way the initial reactive power's sign says.</li>
 *     <li>The injector: an EMF {@code E = vf + Zc (id - j iq) + Kp ((id - j iq) - i)} in the loop's frame behind {@link
 *     Wt4bLogic#INJECTOR_COUPLING_REACTANCE_PU} and its damping, {@code vf} the terminal voltage through {@link
 *     Wt4bLogic#INJECTOR_VOLTAGE_TIME_CONSTANT_S}, {@code i} the current it injects, read on a meter - rebuilt a step ahead of the loop's
 *     angle, EMTP's network taking a control signal a step late.</li>
 * </ul>
 * Dynawo's discrete logic continuous, as {@link Wt4bLogic} writes it: the time since the last dip ended, reset while one lasts; each hold 1
 * while it is under its duration. Blocked ({@code ConverterBlocking} on its generator): the injector's currents times a flag stepping to 0
 * at its instant, and a breaker between its bus and its meter letting go of each phase at its current's first zero - for good.
 *
 * <p>REEC_A's power factor, reactive power and voltage controls, as upstream's backends write them: with {@code PfFlag}, the reactive
 * power reference the active power through {@code tpREEC} times the power factor angle's tangent; with {@code VFlag}, the voltage
 * reference a PI on the reactive power within {@code [VMin, VMax]}, rather than {@code VRef1} plus the reference; with {@code QFlag}, the
 * reactive current a PI on the voltage within {@code +/- IqMax}, rather than the reference over the voltage through {@code tIq} - each PI's
 * integral frozen through a dip and wound back at {@link Wt4bLogic#PI_ANTI_WINDUP_NI}, from the load flow's state. A negative {@code
 * tHoldIq} is written (the injection held on the voltage).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpWindTurbines {

    private static final double[] PHASE_OFFSETS = {0.0, -2.0 * Math.PI / 3.0, 2.0 * Math.PI / 3.0};

    private EmtpWindTurbines() {
    }

    static boolean isWindTurbine(Generator generator) {
        return generator.getExtension(GeneratorModel.class) instanceof Wt4bModel;
    }

    /** Why {@code generator}'s WT4B cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(Generator generator) {
        return new ArrayList<>();
    }

    /** {@code generator}'s devices on its bus's phases {@code phaseNodes}, blocked at {@code blockingSeconds} - {@code NaN} for never. */
    static List<EmtpSynchronousMachines.Device> turbine(Generator generator, String[] phaseNodes, double blockingSeconds, EmtpSimulationSettings settings) {
        return new Turbine(generator, phaseNodes, blockingSeconds, settings).write();
    }

    /** One turbine's devices. */
    private static final class Turbine {

        private final Generator generator;
        private final String[] phaseNodes;
        private final double blockingSeconds;
        private final EmtpSimulationSettings settings;
        private final Wt4bModel model;
        private final Wt4bInitialState initial;
        private final Wt4bLogic logic;
        private final String id;
        private final List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();

        private Turbine(Generator generator, String[] phaseNodes, double blockingSeconds, EmtpSimulationSettings settings) {
            this.generator = generator;
            this.phaseNodes = phaseNodes;
            this.blockingSeconds = blockingSeconds;
            this.settings = settings;
            this.model = (Wt4bModel) generator.getExtension(GeneratorModel.class);
            this.initial = Wt4bInitialState.of(generator);
            this.logic = new Wt4bLogic(model, initial);
            this.id = EmtpVoltageSourceConverters.sanitize(generator.getId());
        }

        private String n(String code) {
            return id + "_" + code;
        }

        /** The function {@code <id>_<code>} of {@code inputs}, at rest on {@code rest}. */
        private String f(String code, List<String> inputs, double rest, String expression) {
            devices.add(function(n(code), n(code), inputs, rest, expression));
            return n(code);
        }

        /** The same, its output recorded. */
        private String recorded(String code, List<String> inputs, double rest, String expression) {
            devices.add(scoped(function(n(code), n(code), inputs, rest, expression)));
            return n(code);
        }

        /** The integral {@code <id>_<code>} of {@code rate}, from {@code output0}. */
        private String integral(String code, String rate, double output0) {
            devices.add(integrator(n(code), n(code), rate, 1.0, output0));
            return n(code);
        }

        /** {@code input} through a lag {@code 1 / (1 + sT)}, at rest on {@code rest}. */
        private String lag(String code, String input, double rest, double timeConstant) {
            devices.add(firstOrder(n(code), n(code), input, rest, 1.0, 1.0, 0.0, timeConstant));
            return n(code);
        }

        private List<EmtpSynchronousMachines.Device> write() {
            Wt4bModel.ElectricalControl reec = model.getElectricalControl();
            Wt4bModel.GeneratorConverter regc = model.getGeneratorConverter();
            Wt4bModel.Pll pll = model.getPll();
            double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
            double dt = settings.timeStepSeconds();
            double vbpk = initial.nominalKv() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            double ibpk = initial.ratedMva() * 1.0e6 / (1.5 * vbpk);
            Bus bus = generator.getTerminal().getBusBreakerView().getBus();
            double theta0 = initial.angle() + Math.toRadians(VectorGroupOffsets.of(generator.getNetwork()).degrees(bus));
            double u0 = initial.voltage();
            double id0 = initial.activeCurrent();
            double iq0 = initial.reactiveCurrent();
            Wt4bLogic.Coupling coupling = Wt4bLogic.injectorCoupling(settings.nominalFrequencyHz(), dt);
            double ed0 = coupling.directEmf(u0, id0, iq0);
            double eq0 = coupling.quadratureEmf(0.0, id0, iq0);
            String zero = n("ZERO");
            devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + zero + ";1;1;" + zero + ",", "0,S1,")));

            // The power stage, phase by phase: the bus voltage's probe; behind a breaker if it is blocked, the meter the injector's current is
            // read on; the coupling reactance and its damping; the EMF, its stand-in in the steady state.
            double couplingH = Wt4bLogic.INJECTOR_COUPLING_REACTANCE_PU * initial.nominalKv() * initial.nominalKv() / initial.ratedMva() / omega;
            String star = n("N");
            String[] v = new String[3];
            String[] i = new String[3];
            for (int k = 0; k < 3; k++) {
                char phase = "abc".charAt(k);
                String node = phaseNodes[k];
                v[k] = n("V" + phase);
                i[k] = n("IM" + phase);
                devices.add(element("_cI", n("PR" + phase), List.of(node, "", zero, v[k], n("PRI" + phase), n("PRP" + phase)), "-1,1E15,0,0,0,0,>v,0,0,"));
                String meterBus = node;
                if (!Double.isNaN(blockingSeconds)) {
                    meterBus = n("B" + phase);
                    devices.add(element("_Sw0", n("BRK" + phase), List.of(node, meterBus), "-1," + number(NumericalAids.switchingInstant(blockingSeconds, dt))
                            + ",0,0,0,,1,.1,.001,5,"));
                    // Its bus's RC to earth, from the start, as every upstream backend writes it (NumericalAids#WIND_TURBINE_BUS_SNUBBER_F): its
                    // breaker let go, the bus may be held by nothing but a transformer's winding.
                    devices.add(element("_RLC", n("SNB" + phase), List.of(node), number(NumericalAids.WIND_TURBINE_BUS_SNUBBER_OHM) + ",0,"
                            + number(NumericalAids.WIND_TURBINE_BUS_SNUBBER_F) + ",0,0,"));
                }
                String measured = n("X" + phase);
                devices.add(element("_cV", n("MET" + phase), List.of(measured, meterBus, zero, n("MV" + phase), i[k], n("MP" + phase)), "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
                String inner = n("K" + phase);
                String standIn = n("M" + phase);
                devices.add(element("_RLC", n("LC" + phase), List.of(inner, measured), "0," + number(couplingH) + ",0,0,0,"));
                devices.add(element("_RLC", n("LCD" + phase), List.of(inner, measured), number(NumericalAids.seriesInductanceDampingOhm(couplingH, dt)) + ",0,0,0,0,"));
                devices.add(element("_Vsine", n("STD" + phase), List.of(inner, standIn), number(vbpk * Math.hypot(ed0, eq0)) + ","
                        + number(settings.nominalFrequencyHz()) + "," + number(Math.toDegrees(theta0 + Math.atan2(eq0, ed0) + PHASE_OFFSETS[k])) + ",-1,1E-15,"));
                devices.add(element("_cV", n("CV" + phase), List.of(standIn, star, n("E" + phase), n("CVV" + phase), n("CVI" + phase), n("CVP" + phase)),
                        "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            }
            devices.add(element("_RLC", n("NREF"), List.of(star), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,"));

            // The PLL.
            String theta = n("TH");
            String vd = n("VD");
            String vq = n("VQ");
            devices.add(EmtpVoltageSourceConverters.park(vd, vd, v, theta, "COS", vbpk, u0, false));
            devices.add(EmtpVoltageSourceConverters.park(vq, vq, v, theta, "SIN", vbpk, 0.0, true));
            String rate = "(" + number(pll.integralGain()) + ")*u[1]";
            f("XIR", List.of(vq, n("XI")), 0.0, rate + "*(1-" + step("u[2]-(" + number(pll.frequencyMax() - 1.0) + ")") + "*" + step(rate) + "-"
                    + step("(" + number(pll.frequencyMin() - 1.0) + ")-u[2]") + "*" + step("-" + rate) + ")");
            integral("XI", n("XIR"), 0.0);
            f("WD", List.of(vq, n("XI")), 0.0, "(" + number(omega * pll.proportionalGain()) + ")*u[1]+(" + number(omega) + ")*u[2]");
            integral("TI", n("WD"), 0.0);
            f("TH", List.of(n("TI")), theta0, "(" + number(theta0) + ")+(" + number(omega) + ")*t+u[1]");

            // The injector's currents, REGC_A's - blocked, times a flag stepping to 0 at its instant -, and the measurements.
            String jd = n("ID");
            String jq = n("IQ");
            if (!Double.isNaN(blockingSeconds)) {
                String release = number(NumericalAids.switchingInstant(blockingSeconds, dt));
                f("JD", List.of(n("ID")), id0, "u[1]*" + step("(" + release + ")-t"));
                f("JQ", List.of(n("IQ")), iq0, "u[1]*" + step("(" + release + ")-t"));
                jd = n("JD");
                jq = n("JQ");
            }
            String u = recorded("U", List.of(vd, vq), u0, "SQRT(u[1]*u[1]+u[2]*u[2])");
            recorded("P", List.of(vd, vq, jd, jq), initial.activePower(), "u[1]*u[3]-u[2]*u[4]");
            recorded("Q", List.of(vd, vq, jd, jq), initial.reactivePower(), "u[2]*u[3]+u[1]*u[4]");

            // REEC_A: the fault ride-through; the voltage filtered.
            String frt = recorded("FRT", List.of(u), 0.0, step("(" + number(reec.voltageDipThreshold()) + ")-u[1]") + "+" + step("u[1]-("
                    + number(reec.voltageUpThreshold()) + ")"));
            String run = f("RN", List.of(frt), 1.0, "1-u[1]");
            String uf = lag("UF", u, u0, reec.voltageFilterTimeConstant());
            String floored = "0.5*(u[1]+" + number(Wt4bLogic.VOLTAGE_FLOOR_PU) + "+ABS(u[1]-" + number(Wt4bLogic.VOLTAGE_FLOOR_PU) + "))";
            // The reactive power reference: the load flow's, or the active power's on its power factor; within [QMin, QMax].
            double qRef0 = reec.powerFactorControl() ? Math.tan(initial.powerFactorAngle()) * initial.activePower() : initial.reactivePower();
            double qLim0 = Math.min(Math.max(qRef0, reec.reactivePowerMin()), reec.reactivePowerMax());
            String qLim;
            if (reec.powerFactorControl()) {
                String pf = lag("PF", n("P"), initial.activePower(), reec.activePowerFilterTimeConstant());
                qLim = f("QL", List.of(pf), qLim0, clamp("(" + number(Math.tan(initial.powerFactorAngle())) + ")*u[1]", number(reec.reactivePowerMin()),
                        number(reec.reactivePowerMax())));
            } else {
                qLim = f("QL", List.of(zero), qLim0, number(qLim0) + "+0*u[1]");
            }
            // The time since the last dip ended, every hold's clock.
            f("SNR", List.of(run, frt, n("SN")), 1.0, "u[1]-u[2]*u[3]/" + number(Wt4bLogic.TIMER_RESET_TIME_CONSTANT_S));
            String since = integral("SN", n("SNR"), Wt4bLogic.TIME_SINCE_DIP_INITIAL_S);

            // The current limits, on the filtered voltage's tables, the reactive current first unless PQFlag.
            String ipTable = table("TP", reec.activeCurrentLimit(), uf);
            String iqTable = table("TQ", reec.reactiveCurrentLimit(), uf);
            String imax2 = number(reec.currentMax() * reec.currentMax());
            String headroom = "SQRT(0.5*((" + imax2 + "-u[2]*u[2])+ABS(" + imax2 + "-u[2]*u[2])))";
            String ipLimit;
            String iqMax;
            if (reec.activePowerPriority()) {
                ipLimit = f("IPR", List.of(ipTable), logic.activeCurrentLimit0(), min("u[1]", number(reec.currentMax())));
                iqMax = f("IQM", List.of(iqTable, n("IDC")), Math.min(Wt4bModel.ElectricalControl.limitAt(reec.reactiveCurrentLimit(), u0),
                        Math.sqrt(Math.max(reec.currentMax() * reec.currentMax() - id0 * id0, 0.0))), min("u[1]", headroom));
            } else {
                iqMax = f("IQM", List.of(iqTable), Math.min(Wt4bModel.ElectricalControl.limitAt(reec.reactiveCurrentLimit(), u0), reec.currentMax()),
                        min("u[1]", number(reec.currentMax())));
                ipLimit = f("IPR", List.of(ipTable, n("IC")), logic.activeCurrentLimit0(), min("u[1]", headroom));
            }
            String ipMax = ipLimit;
            if (reec.activeCurrentLimitHoldTime() > 0.0) {
                f("IPN", List.of(frt, ipLimit, n("IPS")), 0.0, "u[1]*(u[2]-u[3])/" + number(Wt4bLogic.SAMPLE_TIME_CONSTANT_S));
                integral("IPS", n("IPN"), logic.activeCurrentLimit0());
                ipMax = f("IPM", List.of(run, since, n("IPS"), ipLimit), logic.activeCurrentLimit0(), "u[1]*" + step("(" + number(reec.activeCurrentLimitHoldTime())
                        + ")-u[2]") + "*(u[3]-u[4])+u[4]");
            }

            // The reactive current: the voltage PI's, or the reference over the voltage through tIq - frozen through a dip; plus the
            // injection and its logic.
            String iqBase;
            if (reec.voltageControl()) {
                String vSel;
                if (reec.reactivePowerControl()) {
                    String qError = f("QE", List.of(qLim, n("Q")), qLim0 - initial.reactivePower(), "u[1]-u[2]");
                    vSel = pi("QPI", qError, reec.reactivePowerProportionalGain(), reec.reactivePowerIntegralGain(), number(reec.voltageMin()),
                            number(reec.voltageMax()), List.of(), logic.reactivePowerPiIntegral0(), run);
                } else {
                    vSel = f("VS", List.of(qLim), Math.min(Math.max(reec.voltageBias() + qLim0, reec.voltageMin()), reec.voltageMax()),
                            clamp("(" + number(reec.voltageBias()) + ")+u[1]", number(reec.voltageMin()), number(reec.voltageMax())));
                }
                String vError = f("VE", List.of(vSel, uf), 0.0, "u[1]-u[2]");
                iqBase = pi("VPI", vError, reec.voltageProportionalGain(), reec.voltageIntegralGain(), "-u[2]", "u[2]", List.of(iqMax),
                        logic.voltagePiIntegral0(), run);
            } else {
                f("BN", List.of(run, uf, n("IB"), qLim), 0.0, "u[1]*(u[4]/(" + floored.replace("u[1]", "u[2]") + ")-u[3])/"
                        + number(reec.reactiveCurrentTimeConstant()));
                iqBase = integral("IB", n("BN"), iq0);
            }
            String deviation = "(" + number(initial.injectionVoltageReference(reec)) + ")-u[1]";
            String high = "0.5*((" + deviation + "-(" + number(reec.injectionDeadbandHigh()) + "))+ABS(" + deviation + "-(" + number(reec.injectionDeadbandHigh()) + ")))";
            String low = "0.5*((" + deviation + "-(" + number(reec.injectionDeadbandLow()) + "))-ABS(" + deviation + "-(" + number(reec.injectionDeadbandLow()) + ")))";
            f("DZ", List.of(uf), 0.0, "(" + number(reec.injectionGain()) + ")*(" + high + "+" + low + ")");
            String injection = f("IV", List.of(n("DZ")), 0.0, clamp("u[1]", number(reec.injectionMin()), number(reec.injectionMax())));
            if (reec.injectionHoldTime() != 0.0) {
                String held = reec.injectionHoldTime() < 0.0 ? "u[4]" : number(reec.frozenInjection());
                f("IJ", List.of(frt, run, since, injection), 0.0, "u[1]*u[4]+u[2]*" + step("(" + number(Math.abs(reec.injectionHoldTime())) + ")-u[3]") + "*" + held);
            } else {
                f("IJ", List.of(frt, injection), 0.0, "u[1]*u[2]");
            }
            String iqCommand = f("IC", List.of(iqBase, n("IJ"), iqMax), iq0, clamp("u[1]+u[2]", "-u[3]", "u[3]"));
            String idCommand = f("IDC", List.of(uf, ipMax), id0, clamp("(" + number(logic.activePowerOrder()) + ")/(" + floored + ")", "0", "u[2]"));

            // REGC_A: the active current's lag, its rise at most Rrpwr, within the low voltage power logic's limit if on.
            f("GDE", List.of(idCommand, n("GD")), 0.0, min("(u[1]-u[2])/" + number(regc.converterTimeConstant()), number(regc.activeCurrentRecoveryRate())));
            integral("GD", n("GDE"), id0);
            if (regc.lowVoltagePowerLogic()) {
                lag("UG", u, u0, regc.lvplVoltageFilterTimeConstant());
                double slope = regc.lvplGain() / (regc.lvplBreakpoint() - regc.lvplZeroCrossing());
                String ramp = "0.5*((" + number(slope) + ")*(u[2]-(" + number(regc.lvplZeroCrossing()) + "))+ABS((" + number(slope) + ")*(u[2]-("
                        + number(regc.lvplZeroCrossing()) + "))))";
                String above = step("u[2]-(" + number(regc.lvplBreakpoint()) + ")");
                recorded("ID", List.of(n("GD"), n("UG")), id0, min("u[1]", above + "*" + number(Wt4bModel.NO_LIMIT) + "+(1-" + above + ")*" + ramp));
            } else {
                recorded("ID", List.of(n("GD")), id0, "u[1]");
            }
            // The reactive current's lag, rate limited after a dip the way the initial reactive power's sign says.
            String limitedTime = step("(" + number(regc.reactiveCurrentRateLimitTime()) + ")-u[4]");
            String raw = "(u[1]-u[2])/" + number(regc.converterTimeConstant());
            String qRate = raw;
            if (logic.reactiveCurrentRateLimitedUp()) {
                qRate = min(raw, "u[3]*" + limitedTime + "*(" + number(regc.reactiveCurrentRateMax()) + ")+(1-u[3]*" + limitedTime + ")*" + number(Wt4bModel.NO_LIMIT));
            } else if (logic.reactiveCurrentRateLimitedDown()) {
                qRate = max(raw, "u[3]*" + limitedTime + "*(" + number(regc.reactiveCurrentRateMin()) + ")-(1-u[3]*" + limitedTime + ")*" + number(Wt4bModel.NO_LIMIT));
            }
            f("GQE", List.of(iqCommand, n("IQ"), run, since), 0.0, qRate);
            devices.add(scoped(integrator(n("IQ"), n("IQ"), n("GQE"), 1.0, iq0)));

            // The injector: the voltage through its lag, the current it injects read on its meter, its EMF a step ahead.
            String vf = lag("VF", vd, u0, Wt4bLogic.INJECTOR_VOLTAGE_TIME_CONSTANT_S);
            String vg = lag("VG", vq, 0.0, Wt4bLogic.INJECTOR_VOLTAGE_TIME_CONSTANT_S);
            devices.add(EmtpVoltageSourceConverters.park(n("IMD"), n("IMD"), i, theta, "COS", -ibpk, id0, false));
            devices.add(EmtpVoltageSourceConverters.park(n("IMQ"), n("IMQ"), i, theta, "SIN", -ibpk, iq0, false));
            double kp = Wt4bLogic.injectorCurrentGainPu(settings.nominalFrequencyHz());
            f("ED", List.of(vf, jd, jq, n("IMD")), ed0, "u[1]+(" + number(coupling.resistance()) + ")*u[2]+(" + number(coupling.reactance()) + ")*u[3]+("
                    + number(kp) + ")*(u[2]-u[4])");
            f("EQ", List.of(vg, jd, jq, n("IMQ")), eq0, "u[1]+(" + number(coupling.reactance()) + ")*u[2]-(" + number(coupling.resistance()) + ")*u[3]-("
                    + number(kp) + ")*(u[3]-u[4])");
            for (int k = 0; k < 3; k++) {
                double ahead = PHASE_OFFSETS[k] + omega * dt;
                f("E" + "abc".charAt(k), List.of(n("ED"), n("EQ"), theta), vbpk * (ed0 * Math.cos(theta0 + PHASE_OFFSETS[k]) - eq0 * Math.sin(theta0 + PHASE_OFFSETS[k])),
                        number(vbpk) + "*(u[1]*COS(u[3]+(" + number(ahead) + "))-u[2]*SIN(u[3]+(" + number(ahead) + ")))");
            }
            return devices;
        }

        /**
         * A REEC_A PI {@code <id>_<code>} on {@code error}, as upstream's: {@code Kp (e + I)} within {@code [min, max]} - expressions of
         * {@code u[2]...}, {@code bounds} their inputs -, its integral {@code I} rising at {@code running Ki/Kp (e + (y - Kp (e + I)) / (Kp
         * Ni))} from {@code integral0}: frozen through a dip, wound back past its bounds.
         */
        private String pi(String code, String error, double proportionalGain, double integralGain, String min, String max, List<String> bounds,
                          double integral0, String running) {
            List<String> inputs = new ArrayList<>(List.of(n(code + "U")));
            inputs.addAll(bounds);
            String unlimited = f(code + "U", List.of(error, n(code + "I")), proportionalGain * integral0, "(" + number(proportionalGain) + ")*(u[1]+u[2])");
            String y = f(code, inputs, proportionalGain * integral0, clamp("u[1]", min, max));
            f(code + "N", List.of(running, error, y, unlimited), 0.0, "u[1]*(" + number(integralGain / proportionalGain) + ")*(u[2]+(u[3]-u[4])/("
                    + number(proportionalGain * Wt4bLogic.PI_ANTI_WINDUP_NI) + "))");
            integral(code + "I", n(code + "N"), integral0);
            return y;
        }

        /** A voltage-dependent current limit at {@code voltage}: its first point's value, plus each segment's rise up to it - a step, two
         * points at one voltage, rising whole past it. */
        private String table(String code, List<Wt4bModel.CurrentLimitPoint> points, String voltage) {
            List<String> terms = new ArrayList<>();
            for (int k = 1; k < points.size(); k++) {
                Wt4bModel.CurrentLimitPoint low = points.get(k - 1);
                Wt4bModel.CurrentLimitPoint high = points.get(k);
                double rise = high.current() - low.current();
                if (rise == 0.0) {
                    continue;
                }
                String term;
                if (high.voltage() > low.voltage()) {
                    term = "(" + number(rise / (high.voltage() - low.voltage())) + ")*(" + clamp("u[1]", number(low.voltage()), number(high.voltage())) + "-("
                            + number(low.voltage()) + "))";
                } else {
                    term = "(" + number(rise) + ")*" + step("u[1]-(" + number(low.voltage()) + ")");
                }
                terms.add(f(code + k, List.of(voltage), 0.0, term));
            }
            List<String> inputs = terms.isEmpty() ? List.of(voltage) : terms;
            StringBuilder sum = new StringBuilder(number(points.getFirst().current()));
            for (int k = 1; k <= terms.size(); k++) {
                sum.append("+u[").append(k).append("]");
            }
            if (terms.isEmpty()) {
                sum.append("+0*u[1]");
            }
            return f(code, inputs, Wt4bModel.ElectricalControl.limitAt(points, initial.voltage()), sum.toString());
        }
    }

    private static String min(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")-ABS((" + a + ")-(" + b + ")))";
    }

    private static String max(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")+ABS((" + a + ")-(" + b + ")))";
    }

    /** {@code x} within {@code [low, high]}. */
    private static String clamp(String x, String low, String high) {
        return max(min(x, high), low);
    }

    /** An electrical device: its header - part, name, its pins twice, its nodes - and its data line. */
    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",", data));
    }
}
