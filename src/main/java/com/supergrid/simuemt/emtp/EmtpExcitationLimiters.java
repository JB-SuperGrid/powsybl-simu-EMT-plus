/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.excitation.limiter.KundurVoltsPerHertzLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Oel1BModel;
import com.powsybl.simuemt.extensions.excitation.limiter.OverexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.PfVarType2InitialState;
import com.powsybl.simuemt.extensions.excitation.limiter.PfVarType2Model;
import com.powsybl.simuemt.extensions.excitation.limiter.PowerFactorVarControllerModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Scl1CInitialState;
import com.powsybl.simuemt.extensions.excitation.limiter.Scl1CModel;
import com.powsybl.simuemt.extensions.excitation.limiter.StatorCurrentLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Uel2CInitialState;
import com.powsybl.simuemt.extensions.excitation.limiter.Uel2CModel;
import com.powsybl.simuemt.extensions.excitation.limiter.UnderexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.VoltsPerHertzLimiterModel;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.Device;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * A synchronous machine's excitation limiters, as ATP's TACS has them ({@code AtpTacsAvrPssWriter}), block for block, at rest on the
 * load flow:
 *
 * <ul>
 *     <li>OEL1B ({@link Oel1BModel}): its pickup and dropout on the field current by ATP's smooth steps ({@link
 *     NumericalAids#OEL_COMPARATOR_EPSILON}), its timer the integral of {@code SR - (1 - FL) OT / Treset} within {@code [0, 1000]}, its
 *     activation {@code OA} the smooth step past its delay, its output {@code OV = OA max(min(KMX (IFDREF - ifd), 0), VOELMIN)} - summed
 *     into the regulator's error, or, taking over, onto its output.</li>
 *     <li>Kundur's V/Hz limiter ({@link KundurVoltsPerHertzLimiterModel}): the terminal voltage over the speed {@code 1 + dw} against its
 *     limit, the sharp step, its delay's timer the SCL1C's, the PI's integral stopped at its bounds, the derivative a lag's difference,
 *     the output within {@code [0, max]} - taken off the regulator's error.</li>
 * </ul>
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpExcitationLimiters {

    /** ATP's OEL timer's reset time constant, seconds. */
    private static final double OEL_TIMER_RESET_SECONDS = 0.5;

    /** An OEL1B's output signal {@code OV}, whether it takes over rather than sums, and its acting flag's signal, 1 while it pulls the
     * field current down. */
    record Oel(String output, boolean takeover, String acting) {
    }

    private EmtpExcitationLimiters() {
    }

    /** The machine's enabled OEL1B, or {@code null}. */
    static Oel1BModel oel1B(Generator generator) {
        return generator.getExtension(OverexcitationLimiterModel.class) instanceof Oel1BModel oel && oel.isEnabled() ? oel : null;
    }

    /** The machine's enabled Kundur V/Hz limiter, or {@code null}. */
    static KundurVoltsPerHertzLimiterModel voltsPerHertz(Generator generator) {
        return generator.getExtension(VoltsPerHertzLimiterModel.class) instanceof KundurVoltsPerHertzLimiterModel vhz && vhz.isEnabled() ? vhz : null;
    }

    /** {@code generator}'s OEL1B's devices, onto {@code devices}, from the field current {@code fieldCurrent} (pu); its output, or {@code null}
     * without one. */
    static Oel oel(List<Device> devices, Generator generator, String name, String fieldCurrent, double fieldCurrent0) {
        Oel1BModel oel = oel1B(generator);
        if (oel == null) {
            return null;
        }
        double limit = oel.getFieldCurrentLimit();
        String timer = name + "_OELT";
        devices.add(function(name + "_OELU", name + "_OELU", List.of(fieldCurrent, timer), smoothStep(fieldCurrent0 - limit), smoothStep("u[1]-" + number(limit))
                + "-(1-" + smoothStep("u[1]-" + number(limit - oel.getHysteresis())) + ")*u[2]*" + number(1.0 / OEL_TIMER_RESET_SECONDS)));
        devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_OELT;4;4;" + timer + "," + name + "_OELU,0,0,", "0,1000,0,1,1,2,1,S1,", "1", ";", "0 1")));
        String output = name + "_OELV";
        String proportional = "(" + number(oel.getGain()) + "*(" + number(oel.getLimiterReference()) + "-u[2]))";
        String negative = "(0.5*(" + proportional + "-ABS(" + proportional + ")))";
        String floor = number(oel.getMinOutput());
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_OELV", output, List.of(timer, fieldCurrent), 0.0, smoothStep("u[1]-"
                + number(oel.getPickupDelay())) + "*(0.5*(" + negative + "+(" + floor + ")+ABS(" + negative + "-(" + floor + "))))")));
        String acting = name + "_OELA";
        devices.add(function(acting, acting, List.of(timer, fieldCurrent), 0.0, smoothStep("u[1]-" + number(oel.getPickupDelay())) + "*"
                + EmtpSynchronousMachines.limiterStep("(u[2]-" + number(oel.getLimiterReference()) + ")")));
        return new Oel(output, oel.isTakeoverMode(), acting);
    }

    /**
     * {@code generator}'s V/Hz limiter's devices, onto {@code devices}, from the observed {@code vd}, {@code vq} and the speed deviation
     * {@code deviation}; its output {@code VVHZ}, which the regulator's error takes off, or {@code null} without one.
     */
    static String voltsPerHertz(List<Device> devices, Generator generator, String name, String vd, String vq, String deviation) {
        KundurVoltsPerHertzLimiterModel vhz = voltsPerHertz(generator);
        if (vhz == null) {
            return null;
        }
        String error = name + "_VHZE";
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_VHZE", error, List.of(vd, vq, deviation), vhz.restingError(generator),
                "SQRT(u[1]*u[1]+u[2]*u[2])/(1+u[3])-" + number(vhz.getLimit()))));
        String active;
        List<String> inputs = new ArrayList<>(List.of(error));
        String input = name + "_VHZU";
        if (vhz.getDelay() > 0.0) {
            String timer = name + "_VHZT";
            devices.add(function(name + "_VHZTU", name + "_VHZTU", List.of(error, timer), 0.0, EmtpSynchronousMachines.limiterStep("u[1]") + "-(1-"
                    + EmtpSynchronousMachines.limiterStep("u[1]") + ")*u[2]*" + number(1.0 / KundurVoltsPerHertzLimiterModel.TIMER_RESET_TIME_CONSTANT)));
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_VHZT;4;4;" + timer + "," + name + "_VHZTU,0,0,", "0,1000,0,1,1,2,1,S1,", "1", ";",
                    "0 1")));
            inputs.add(timer);
            active = EmtpSynchronousMachines.limiterStep("(u[2]-" + number(vhz.getDelay()) + ")");
        } else {
            active = EmtpSynchronousMachines.limiterStep("u[1]");
        }
        devices.add(function(name + "_VHZU", input, inputs, 0.0, active + "*u[1]"));
        List<String> sumInputs = new ArrayList<>(List.of(input));
        StringBuilder sum = new StringBuilder(number(vhz.getProportionalGain()) + "*u[1]");
        if (vhz.getIntegralGain() > 0.0) {
            String integrator = name + "_VHZI";
            String pushesUp = EmtpSynchronousMachines.limiterStep("u[1]");
            devices.add(function(name + "_VHZY", name + "_VHZY", List.of(input, integrator), 0.0, "u[1]*(1-" + EmtpSynchronousMachines.limiterStep("(u[2]-"
                    + number(vhz.getOutputMax()) + ")") + "*" + pushesUp + "-" + EmtpSynchronousMachines.limiterStep("(-u[2])") + "*(1-" + pushesUp + "))"));
            devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_VHZI;2;2;" + integrator + "," + name + "_VHZY,", "0,1,1,2," + number(vhz.getIntegralGain())
                    + ",S1,", "1", ";", "0 1")));
            sumInputs.add(integrator);
            sum.append("+u[").append(sumInputs.size()).append(']');
        }
        if (vhz.getDerivativeGain() > 0.0) {
            String lag = name + "_VHZD";
            devices.add(EmtpSynchronousMachines.firstOrder(lag, lag, input, 0.0, 1.0, 1.0, 0.0, vhz.getDerivativeTimeConstant()));
            sumInputs.add(lag);
            sum.append("+").append(number(vhz.getDerivativeGain() / vhz.getDerivativeTimeConstant())).append("*(u[1]-u[").append(sumInputs.size()).append("])");
        }
        devices.add(function(name + "_VHZS", name + "_VHZS", sumInputs, 0.0, sum.toString()));
        String output = name + "_VHZO";
        devices.add(EmtpVoltageSourceConverters.scoped(new Device("_c_lim", List.of("_c_lim;" + name + "_VHZO;2;2;" + output + "," + name + "_VHZS,",
                number(vhz.getOutputMax()) + ",0,1,S1,"))));
        return output;
    }

    /** The machine's enabled UEL2C, or {@code null}. */
    static Uel2CModel uel2C(Generator generator) {
        return generator.getExtension(UnderexcitationLimiterModel.class) instanceof Uel2CModel uel && uel.isEnabled() ? uel : null;
    }

    /** The machine's enabled SCL1C, or {@code null}. */
    static Scl1CModel scl1C(Generator generator) {
        return generator.getExtension(StatorCurrentLimiterModel.class) instanceof Scl1CModel scl && scl.isEnabled() ? scl : null;
    }

    /** The machine's enabled PF/var controller type II, or {@code null}. */
    static PfVarType2Model pfVar(Generator generator) {
        return generator.getExtension(PowerFactorVarControllerModel.class) instanceof PfVarType2Model controller && controller.isEnabled() ? controller : null;
    }

    /** Whether {@code generator}'s limiters read its terminal powers: a UEL2C, an SCL1C or a PF/var controller. */
    static boolean readsPowers(Generator generator) {
        return uel2C(generator) != null || scl1C(generator) != null || pfVar(generator) != null;
    }

    /** Why {@code generator}'s limiters cannot be written yet - empty if they can: a UEL2C's voltage exponents but 0, 1 and 2. */
    static List<String> unsupportedReasons(Generator generator) {
        List<String> reasons = new ArrayList<>();
        Uel2CModel uel = uel2C(generator);
        if (uel != null) {
            for (double exponent : new double[] {uel.getActivePowerVoltageExponent(), uel.getReactivePowerVoltageExponent()}) {
                if (exponent != 0.0 && exponent != 1.0 && exponent != 2.0) {
                    reasons.add("its UEL2C's voltage exponent " + exponent + " but 0, 1 and 2 is not written yet");
                }
            }
        }
        return reasons;
    }

    /** A UEL2C's output signal {@code VUEL}, whether it takes over, what it sums at rest, and its acting flag's signal, 1 while past its
     * characteristic. */
    record Uel(String output, boolean takeover, double summingOutput0, String acting, Uel2CInitialState initial, Generator generator) {
    }

    /**
     * {@code generator}'s UEL2C's devices, onto {@code devices}, from the terminal's voltage {@code vt}, powers {@code pt} and {@code qt}
     * and the exciter's rate feedback {@code rateFeedback} - {@code null} for none -, as ATP's: every filter at its input's load-flow value
     * ({@link Uel2CInitialState}), the characteristic its first segment plus a ramp per inner point, the gain fixed or adjusted, the
     * limited PI, the two limited lead-lags; {@code null} without one.
     */
    static Uel uel(List<Device> devices, Generator generator, String name, String vt, String pt, String qt, String rateFeedback) {
        Uel2CModel uel = uel2C(generator);
        if (uel == null) {
            return null;
        }
        Uel2CInitialState initial = Uel2CInitialState.of(generator, uel);
        String v = filter(devices, name + "_UV", vt, uel.getVoltageFilterTimeConstant(), initial.voltage());
        String vb = name + "_UB";
        devices.add(function(vb, vb, List.of(v), initial.biasedVoltage(), max(min("u[1]/" + number(uel.getVoltageBias()), "1"), "u[1]")));
        String rawPower = name + "_UA";
        devices.add(function(rawPower, rawPower, List.of(pt, vb), initial.activePowerInput(), "u[1]/(" + power("u[2]", uel.getActivePowerVoltageExponent()) + ")"));
        String pin = filter(devices, name + "_UP", rawPower, uel.getActivePowerFilterTimeConstant(), initial.activePowerInput());
        List<Uel2CModel.Point> points = uel.getCharacteristic();
        StringBuilder characteristic = new StringBuilder(number(points.get(0).reactivePower()) + "+(" + number(uel.slope(0)) + ")*(u[1]-("
                + number(points.get(0).activePower()) + "))");
        for (int k = 1; k < points.size() - 1; k++) {
            String excess = "u[1]-(" + number(points.get(k).activePower()) + ")";
            characteristic.append("+(").append(number(0.5 * (uel.slope(k) - uel.slope(k - 1)))).append(")*(").append(excess).append("+ABS(").append(excess)
                    .append("))");
        }
        String rawReference = name + "_UD";
        devices.add(function(rawReference, rawReference, List.of(pin, vb), initial.reactivePowerReference(), "(" + characteristic + ")*"
                + power("u[2]", uel.getReactivePowerVoltageExponent())));
        String reference = filter(devices, name + "_UR", rawReference, uel.getReactivePowerReferenceTimeConstant(), initial.reactivePowerReference());
        String measured = filter(devices, name + "_UQ", qt, uel.getReactivePowerFilterTimeConstant(), initial.reactivePower());
        List<String> errorInputs = new ArrayList<>(List.of(reference, measured));
        StringBuilder error = new StringBuilder("(u[1]-u[2]");
        if (rateFeedback != null && uel.getStabilizerGain() != 0.0) {
            errorInputs.add(rateFeedback);
            error.append("-(").append(number(uel.getStabilizerGain())).append(")*u[").append(errorInputs.size()).append(']');
        }
        error.append(")");
        if (uel.isFixedGain()) {
            error.append("*").append(number(uel.getFixedGainReduction()));
        } else {
            String raw = name + "_UK";
            devices.add(function(raw, raw, List.of(vt, qt, pt), initial.gain(), "(u[1]*u[1]/" + number(uel.getCharacteristicReactance()) + "+u[2])/SQRT((u[1]*u[1]/"
                    + number(uel.getCharacteristicReactance()) + "+u[2])*(u[1]*u[1]/" + number(uel.getCharacteristicReactance()) + "+u[2])+u[3]*u[3]+1E-9)"));
            String gain = name + "_UKL";
            if (uel.getAdjustableGainTimeConstant() > 0.0) {
                devices.add(new Device("_c_fslim", List.of("_c_fslim;" + gain + ";4;4;" + gain + "," + raw + ",0,0,", number(initial.gain()) + ","
                        + number(Uel2CModel.MAX_ADJUSTABLE_GAIN) + "," + number(Uel2CModel.MIN_ADJUSTABLE_GAIN) + ",1,1,2,1,S1,", "1", ";", "1 "
                        + number(uel.getAdjustableGainTimeConstant()))));
            } else {
                devices.add(clamp(gain, raw, Uel2CModel.MIN_ADJUSTABLE_GAIN, Uel2CModel.MAX_ADJUSTABLE_GAIN));
            }
            errorInputs.add(gain);
            error.append("*u[").append(errorInputs.size()).append(']');
        }
        String errorSignal = name + "_UX";
        devices.add(function(errorSignal, errorSignal, errorInputs, initial.error(), error.toString()));
        String pi = name + "_UO";
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_UPI", pi, errorSignal, uel.getProportionalGain(), uel.getIntegralGain(), uel.getIntegratorMin(),
                uel.getIntegratorMax(), initial.integrator(), uel.getProportionalGain() * initial.error() + initial.integrator()));
        String first = name + "_U1Y";
        devices.addAll(EmtpSynchronousMachines.limitedLeadLag(first, first, pi, uel.getLeadTimeConstant1(), uel.getLagTimeConstant1(), uel.getFirstStageMin(),
                uel.getFirstStageMax(), initial.firstStage()));
        String output = name + "_UEL";
        devices.addAll(EmtpSynchronousMachines.limitedLeadLag(output, output, first, uel.getLeadTimeConstant2(), uel.getLagTimeConstant2(), uel.getOutputMin(),
                uel.getOutputMax(), initial.output()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_UELS", name + "_UELS", List.of(output), initial.output(), "u[1]")));
        String acting = name + "_UACT";
        devices.add(function(acting, acting, List.of(errorSignal), 0.0, EmtpSynchronousMachines.limiterStep("(u[1])")));
        return new Uel(output, uel.isTakeoverMode(), initial.summingOutput(), acting, initial, generator);
    }

    /** An SCL1C's output term {@code VSCL}, less what it gives at rest, and its acting flag's signal. */
    record Scl(String output, double output0, String acting) {
    }

    /**
     * {@code generator}'s SCL1C's devices, onto {@code devices}, from the terminal's voltage and powers, as ATP's: every filter at its
     * input's load-flow value ({@link Scl1CInitialState}), each PI at rest, the logic's comparisons the sharp step its PIs stop with, the
     * fixed delay's timer the integral of {@code rise - (1 - rise) timer / Tr} within {@code [0, 1000]}; {@code null} without one.
     */
    static Scl scl(List<Device> devices, Generator generator, String name, String vt, String pt, String qt) {
        Scl1CModel scl = scl1C(generator);
        if (scl == null) {
            return null;
        }
        Scl1CInitialState initial = Scl1CInitialState.of(generator, scl);
        int k = scl.getExponent();
        String rawReactive = name + "_NQR";
        devices.add(function(rawReactive, rawReactive, List.of(qt, vt), initial.reactiveCurrent(), "u[1]/u[2]"));
        String reactiveCurrent = filter(devices, name + "_NQ", rawReactive, scl.getReactiveCurrentTimeConstant(), initial.reactiveCurrent());
        String rawCurrent = name + "_NIR";
        devices.add(function(rawCurrent, rawCurrent, List.of(pt, qt, vt), initial.current(), "SQRT(u[1]*u[1]+u[2]*u[2])/u[3]"));
        String current = filter(devices, name + "_NI", rawCurrent, scl.getCurrentTimeConstant(), initial.current());
        String error = name + "_NE";
        devices.add(function(error, error, List.of(current), Math.pow(initial.current(), k) - scl.getPickupCurrent(), integerPower("u[1]", k) + "-"
                + number(scl.getPickupCurrent())));
        String overexcited = name + "_NOX";
        String underexcited = name + "_NUX";
        String acting = name + "_NK";
        if (scl.isReactivePowerLogic()) {
            String active = name + "_NA";
            if (scl.isInverseTime()) {
                String inverse = filter(devices, name + "_NV", rawCurrent, scl.getInverseTimeConstant(), initial.current());
                devices.add(function(active, active, List.of(inverse), 0.0, EmtpSynchronousMachines.limiterStep("(u[1]-" + number(scl.getPickupCurrent()) + ")")));
            } else {
                String timer = name + "_NT";
                devices.add(function(name + "_NTU", name + "_NTU", List.of(error, timer), 0.0, EmtpSynchronousMachines.limiterStep("(u[1])") + "-(1-"
                        + EmtpSynchronousMachines.limiterStep("(u[1])") + ")*u[2]*" + number(1.0 / Scl1CModel.TIMER_RESET_TIME_CONSTANT)));
                devices.add(new Device("_c_fslim", List.of("_c_fslim;" + timer + ";4;4;" + timer + "," + name + "_NTU,0,0,", "0,1000,0,1,1,2,1,S1,", "1", ";",
                        "0 1")));
                devices.add(function(active, active, List.of(timer), 0.0, EmtpSynchronousMachines.limiterStep("(u[1]-" + number(scl.getFixedDelay()) + ")")));
            }
            String db = number(scl.getReactivePowerDeadband());
            devices.add(function(overexcited, overexcited, List.of(active, qt, error), 0.0, "u[1]*" + EmtpSynchronousMachines.limiterStep("(u[2]-" + db + ")") + "*u[3]"));
            devices.add(function(underexcited, underexcited, List.of(active, qt, error), 0.0, "u[1]*" + EmtpSynchronousMachines.limiterStep("(-" + db + "-u[2])")
                    + "*u[3]"));
            devices.add(function(acting, acting, List.of(error, active, qt), 0.0, EmtpSynchronousMachines.limiterStep("(u[1])") + "*u[2]*("
                    + EmtpSynchronousMachines.limiterStep("(u[3]-" + db + ")") + "+" + EmtpSynchronousMachines.limiterStep("(-" + db + "-u[3])") + ")"));
        } else {
            String deadband = number(scl.getReactiveCurrentDeadband());
            String y = integerPower("u[1]", k);
            devices.add(function(overexcited, overexcited, List.of(reactiveCurrent, error), 0.0, min(y + "-(" + deadband + ")", "u[2]")));
            devices.add(function(underexcited, underexcited, List.of(reactiveCurrent, error), 0.0, min("-(" + deadband + ")-" + y, "u[2]")));
            devices.add(function(acting, acting, List.of(reactiveCurrent, error), 0.0, EmtpSynchronousMachines.limiterStep("(u[2])") + "*"
                    + EmtpSynchronousMachines.limiterStep("(" + max(y + "-(" + deadband + ")", "-(" + deadband + ")-" + y) + ")")));
        }
        String overPi = name + "_NOO";
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_NOP", overPi, overexcited, scl.getOverexcitedProportionalGain(), scl.getOverexcitedIntegralGain(),
                scl.getOutputMin(), scl.getOutputMax(), initial.overexcitedIntegrator(), initial.overexcitedIntegrator()));
        String underPi = name + "_NUO";
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_NUP", underPi, underexcited, scl.getUnderexcitedProportionalGain(), scl.getUnderexcitedIntegralGain(),
                scl.getOutputMin(), scl.getOutputMax(), initial.underexcitedIntegrator(), initial.underexcitedIntegrator()));
        String output = name + "_NS";
        devices.add(EmtpVoltageSourceConverters.scoped(function(output, output, List.of(underPi, overPi), initial.output(), max("u[1]", "0") + "-" + max("u[2]", "0"))));
        return new Scl(output, initial.output(), acting);
    }

    /**
     * {@code generator}'s PF/var controller type II's devices, onto {@code devices}, as ATP's: its reference what it reads at the load flow
     * ({@link PfVarType2InitialState}), the reactive power or the normalized power factor, held while its current or its voltage is out of
     * its band or a limiter acts - {@code actings}, each 1 while it does -, through the limiters' PI; its output {@code VPF} or {@code
     * VVAR}, which the regulator's error takes, or {@code null} without one.
     */
    static String pfVar(List<Device> devices, Generator generator, String name, String vt, String pt, String qt, List<String> actings) {
        PfVarType2Model controller = pfVar(generator);
        if (controller == null) {
            return null;
        }
        PfVarType2InitialState initial = PfVarType2InitialState.of(generator, controller);
        String measured = qt;
        if (controller.getMode() == PfVarType2Model.Mode.POWER_FACTOR) {
            measured = name + "_CM";
            devices.add(function(measured, measured, List.of(pt, qt), initial.reference(), "1+u[2]*ABS(u[2])/(SQRT(u[1]*u[1]+u[2]*u[2])*(SQRT(u[1]*u[1]+u[2]*u[2])"
                    + "+ABS(u[1]))+1E-12)"));
        }
        List<String> inputs = new ArrayList<>(List.of(measured, pt, qt, vt));
        StringBuilder gates = new StringBuilder(EmtpSynchronousMachines.limiterStep("(SQRT(u[2]*u[2]+u[3]*u[3])/u[4]-" + number(controller.getMinCurrent()) + ")")
                + "*" + EmtpSynchronousMachines.limiterStep("(u[4]-" + number(controller.getMinVoltage()) + ")") + "*"
                + EmtpSynchronousMachines.limiterStep("(" + number(controller.getMaxVoltage()) + "-u[4])"));
        for (String acting : actings) {
            inputs.add(acting);
            gates.append("*(1-u[").append(inputs.size()).append("])");
        }
        String input = name + "_CU";
        devices.add(function(input, input, inputs, 0.0, gates + "*(" + number(initial.reference()) + "-u[1])"));
        String output = name + "_CPO";
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_CP", output, input, controller.getProportionalGain(), controller.getIntegralGain(),
                -controller.getOutputLimit(), controller.getOutputLimit(), 0.0, 0.0));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_CPV", name + "_CPV", List.of(output), 0.0, "u[1]")));
        return output;
    }

    /** {@code input} through {@code 1 / (1 + sT)} at {@code input0}, or as it is for {@code T = 0}; the signal it gives. */
    private static String filter(List<Device> devices, String device, String input, double timeConstant, double input0) {
        if (timeConstant <= 0.0) {
            return input;
        }
        devices.add(EmtpSynchronousMachines.firstOrder(device, device, input, input0, 1.0, 1.0, 0.0, timeConstant));
        return device;
    }

    private static Device clamp(String device, String input, double min, double max) {
        return new Device("_c_lim", List.of("_c_lim;" + device + ";2;2;" + device + "," + input + ",", number(max) + "," + number(min) + ",1,S1,"));
    }

    /** {@code x^e}, {@code e} 0, 1 or 2 ({@link #unsupportedReasons}). */
    private static String power(String x, double exponent) {
        return exponent == 0.0 ? "1" : exponent == 1.0 ? x : x + "*" + x;
    }

    /** {@code x^k} as a product. */
    private static String integerPower(String x, int k) {
        return String.join("*", java.util.Collections.nCopies(k, x));
    }

    private static String min(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")-ABS((" + a + ")-(" + b + ")))";
    }

    private static String max(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")+ABS((" + a + ")-(" + b + ")))";
    }

    /** ATP's OEL comparator {@code 0.5 (1 + x / sqrt(x^2 + eps))} of the expression {@code x}. */
    private static String smoothStep(String x) {
        return "0.5*(1+(" + x + ")/SQRT((" + x + ")*(" + x + ")+" + number(NumericalAids.OEL_COMPARATOR_EPSILON) + "))";
    }

    private static double smoothStep(double x) {
        return 0.5 * (1.0 + x / Math.sqrt(x * x + NumericalAids.OEL_COMPARATOR_EPSILON));
    }
}
