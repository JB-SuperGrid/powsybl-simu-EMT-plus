/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.ExcitationBoost;
import com.powsybl.simuemt.extensions.excitation.limiter.Dec1AInitialState;
import com.powsybl.simuemt.extensions.excitation.limiter.Dec1AModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Dec2AModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Dec3AModel;
import com.powsybl.simuemt.extensions.excitation.limiter.DiscontinuousExcitationControlModel;
import com.powsybl.simuemt.extensions.pss.Pss2AModel;
import com.powsybl.simuemt.extensions.pss.PssModel;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.Device;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.limiterStep;

/**
 * A synchronous machine's PSS2A and discontinuous excitation controls, as ATP's TACS has them ({@code AtpTacsAvrPssWriter}), block for
 * block, every state at rest on the load flow:
 *
 * <ul>
 *     <li>PSS2A ({@link Pss2AModel}): the speed channel's two washouts and its lag; the power channel's - its first washout written as
 *     the power less its lag, started on the load flow's -, its lag of gain {@code KS2}; their sum through the ramp-tracking filter, the
 *     power channel taken back off, times {@code KS1}; two lead-lags and the output's static limit.</li>
 *     <li>DEC1A ({@link Dec1AModel}): the speed's washout, the continuous limiter's limited lead-lag, the bang-bang limiter's memory an
 *     integral driven to 1 above {@code VTM} and to 0 below {@code VTN}, the delay-reset's timer, S1 back into its OR through a lag read
 *     at 0.5, the boost; in the stabilizer's place, less its rest.</li>
 *     <li>DEC2A ({@link Dec2AModel}): its input from its trigger ({@link ExcitationBoost}), the lag and the washout, the clamp; added
 *     to the stabilizer's output.</li>
 *     <li>DEC3A ({@link Dec3AModel}): S1 open below {@code VTMIN} and for {@code TDR} after; the stabilizer's output through it.</li>
 * </ul>
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpStabilizers {

    private EmtpStabilizers() {
    }

    /** The machine's enabled PSS2A, or {@code null}. */
    static Pss2AModel pss2A(Generator generator) {
        return generator.getExtension(PssModel.class) instanceof Pss2AModel pss && pss.isEnabled() ? pss : null;
    }

    /** The machine's enabled discontinuous excitation control, or {@code null}. */
    static DiscontinuousExcitationControlModel dec(Generator generator) {
        DiscontinuousExcitationControlModel control = generator.getExtension(DiscontinuousExcitationControlModel.class);
        boolean enabled = control instanceof Dec1AModel d1 && d1.isEnabled() || control instanceof Dec2AModel d2 && d2.isEnabled()
                || control instanceof Dec3AModel d3 && d3.isEnabled();
        return enabled ? control : null;
    }

    /**
     * The PSS2A's devices, from the speed deviation {@code deviation} and the electrical power {@code power} (pu, at {@code power0} at
     * rest) to {@code output}.
     */
    static List<Device> pss2A(Pss2AModel pss, String name, String deviation, String power, double power0, String output) {
        List<Device> devices = new ArrayList<>();
        String s1 = name + "_P2S1";
        devices.add(washout(s1, deviation, pss.getSpeedWashoutTimeConstant1()));
        String s2 = name + "_P2S2";
        devices.add(washout(s2, s1, pss.getSpeedWashoutTimeConstant2()));
        String h6 = name + "_P2H6";
        devices.add(firstOrder(h6, h6, s2, 0.0, 1.0, 1.0, 0.0, pss.getSpeedLagTimeConstant()));
        String s3Lag = name + "_P2S3L";
        devices.add(firstOrder(s3Lag, s3Lag, power, power0, 1.0, 1.0, 0.0, pss.getPowerWashoutTimeConstant1()));
        String s3 = name + "_P2S3";
        devices.add(function(s3, s3, List.of(power, s3Lag), 0.0, "u[1]-u[2]"));
        String s4 = name + "_P2S4";
        devices.add(washout(s4, s3, pss.getPowerWashoutTimeConstant2()));
        String h7 = name + "_P2H7";
        devices.add(firstOrder(h7, h7, s4, 0.0, pss.getPowerLagGain(), 1.0, 0.0, pss.getPowerLagTimeConstant()));
        String current = name + "_P2SUM1";
        devices.add(function(current, current, List.of(h6, h7), 0.0, "u[1]+" + number(pss.getIntegralGain()) + "*u[2]"));
        for (int i = 1; i <= pss.getRampTrackingNumeratorOrder(); i++) {
            String stage = name + "_P2RY" + i;
            devices.add(firstOrder(stage, stage, current, 0.0, 1.0, 1.0, pss.getRampTrackingLeadTimeConstant(), pss.getRampTrackingLagTimeConstant()));
            current = stage;
        }
        for (int j = 1; j <= pss.getRampTrackingDenominatorOrder(); j++) {
            String stage = name + "_P2RL" + j;
            devices.add(firstOrder(stage, stage, current, 0.0, 1.0, 1.0, 0.0, pss.getRampTrackingLagTimeConstant()));
            current = stage;
        }
        String vp = name + "_P2VP";
        devices.add(function(vp, vp, List.of(current, h7), 0.0, number(pss.getGain1()) + "*(u[1]-u[2])"));
        String fy1 = name + "_P2Y1";
        devices.add(firstOrder(fy1, fy1, vp, 0.0, 1.0, 1.0, pss.getLeadTimeConstant(), pss.getLagTimeConstant()));
        String preLimit = name + "_P2Y2";
        devices.add(firstOrder(preLimit, preLimit, fy1, 0.0, 1.0, 1.0, pss.getLeadTimeConstant2(), pss.getLagTimeConstant2()));
        devices.add(EmtpVoltageSourceConverters.scoped(new Device("_c_lim", List.of("_c_lim;" + name + "_P2VS;2;2;" + output + "," + preLimit + ",",
                number(pss.getOutputLimit()) + "," + number(-pss.getOutputLimit()) + ",1,S1,"))));
        return devices;
    }

    /**
     * The machine's discontinuous excitation control's devices, from the stabilizer's output {@code stabilizer} - {@code null} for none - to
     * {@code output}, what the regulator's error takes in its place: {@code vd}, {@code vq} the observed axes, {@code deviation} the speed's
     * deviation, {@code regulator} the regulator's clamped output a DEC1A reads, {@code boost} a DEC2A's trigger, if any.
     */
    static List<Device> dec(DiscontinuousExcitationControlModel control, Generator generator, String name, MachineInitialState initial, String stabilizer,
                            String vd, String vq, String deviation, String regulator, ExcitationBoost boost, double timeStepSeconds, String output) {
        List<Device> devices = new ArrayList<>();
        String vs = stabilizer;
        if (vs == null) {
            vs = name + "_DECZ";
            devices.add(new Device("_c_cst", List.of("_c_cst;" + vs + ";1;1;" + vs + ",", "0,S1,")));
        }
        String vt = name + "_DECVT";
        devices.add(EmtpVoltageSourceConverters.scoped(function(vt, vt, List.of(vd, vq), initial.terminalVoltage(), "SQRT(u[1]*u[1]+u[2]*u[2])")));
        if (control instanceof Dec2AModel dec) {
            String input = name + "_DECK";
            String trigger = boost == null ? "0" : number(dec.getInputReference()) + "*" + EmtpVoltageSourceConverters.step("t-("
                    + number(NumericalAids.switchingInstant(boost.timeSeconds(), timeStepSeconds)) + ")");
            devices.add(function(input, input, List.of(vs), 0.0, trigger + "+0*u[1]"));
            if (dec.getLagTimeConstant() > 0.0) {
                devices.add(firstOrder(name + "_DECA", name + "_DECA", input, 0.0, 1.0, 1.0, 0.0, dec.getLagTimeConstant()));
                input = name + "_DECA";
            }
            String pulse = name + "_DECW";
            devices.add(washout(pulse, input, dec.getWashoutTimeConstant()));
            String clamped = name + "_DECV";
            devices.add(EmtpVoltageSourceConverters.scoped(new Device("_c_lim", List.of("_c_lim;" + clamped + ";2;2;" + clamped + "," + pulse + ",",
                    number(dec.getOutputMax()) + "," + number(dec.getOutputMin()) + ",1,S1,"))));
            devices.add(function(name + "_DECO", output, List.of(vs, clamped), 0.0, "u[1]+u[2]"));
        } else if (control instanceof Dec3AModel dec) {
            double tau = Dec1AModel.SWITCHING_TIME_CONSTANT;
            String low = limiterStep("(" + number(dec.getVoltageLevel()) + "-u[1])");
            String open;
            List<String> inputs = new ArrayList<>(List.of(vt, vs));
            if (dec.getResetDelay() > 0.0) {
                double low0 = stepValue(dec.getVoltageLevel() - initial.terminalVoltage());
                double timer0 = dec.getResetDelay() + 1.0;
                String timer = name + "_DECT";
                devices.add(function(name + "_DECTU", name + "_DECTU", List.of(vt, timer), 1.0 - low0 - low0 * timer0 / tau, "1-" + low + "-" + low + "*u[2]*"
                        + number(1.0 / tau)));
                devices.add(new Device("_c_fslim", List.of("_c_fslim;" + timer + ";4;4;" + timer + "," + name + "_DECTU,0,0,", number(timer0) + ",1000,0,1,1,2,1,S1,",
                        "1", ";", "0 1")));
                inputs.add(timer);
                String hold = limiterStep("(" + number(dec.getResetDelay()) + "-u[3])");
                open = "(" + low + "+" + hold + "-" + low + "*" + hold + ")";
            } else {
                open = low;
            }
            devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_DECO", output, inputs, 0.0, "(1-" + open + ")*u[2]")));
        } else if (control instanceof Dec1AModel dec) {
            devices.addAll(dec1A(dec, generator, name, initial, vs, vt, deviation, regulator, output));
        }
        return devices;
    }

    private static List<Device> dec1A(Dec1AModel dec, Generator generator, String name, MachineInitialState initial, String vs, String vt, String deviation,
                                      String regulator, String output) {
        Dec1AInitialState rest = Dec1AInitialState.of(generator, dec, initial.efd());
        double tau = Dec1AModel.SWITCHING_TIME_CONSTANT;
        List<Device> devices = new ArrayList<>();
        String dw = name + "_DECW";
        devices.add(washout(dw, deviation, dec.getWashoutTimeConstant()));
        String limiterInput = name + "_DECOU";
        devices.add(function(limiterInput, limiterInput, List.of(vt), dec.getLimiterGain() * (initial.terminalVoltage() - dec.getVoltageLimit()),
                number(dec.getLimiterGain()) + "*(u[1]-" + number(dec.getVoltageLimit()) + ")"));
        String limiter = name + "_DECOY";
        devices.addAll(EmtpSynchronousMachines.limitedLeadLag(limiter, limiter, limiterInput, dec.getLimiterLeadTimeConstant(), dec.getLimiterLagTimeConstant(),
                dec.getLimiterOutputMin(), dec.getLimiterOutputMax(), rest.limiterOutput()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_DECVO", name + "_DECVO", List.of(limiter), rest.limiterOutput(), "u[1]")));
        String bangBang = name + "_DECP";
        devices.add(function(name + "_DECPU", name + "_DECPU", List.of(vt, bangBang), 0.0, "(" + limiterStep("(u[1]-" + number(dec.getBangBangUpperVoltage()) + ")")
                + "*(1-u[2])-" + limiterStep("(" + number(dec.getBangBangLowerVoltage()) + "-u[1])") + "*u[2])*" + number(1.0 / tau)));
        devices.add(EmtpVoltageSourceConverters.scoped(new Device("_c_fs", List.of("_c_fs;" + bangBang + ";2;2;" + bangBang + "," + name + "_DECPU,",
                number(rest.bangBangOutput() > 0.0 ? 1.0 : 0.0) + ",1,1,2,1,S1,", "1", ";", "0 1"))));
        String latch = name + "_DECF";
        String input = "((" + limiterStep("(" + number(dec.getVoltageDipLevel()) + "-u[1])") + "+" + limiterStep("(u[2]-0.5)") + "-" + limiterStep("("
                + number(dec.getVoltageDipLevel()) + "-u[1])") + "*" + limiterStep("(u[2]-0.5)") + ")*" + limiterStep("(" + number(dec.getRegulatorLevel()) + "-u[3])")
                + ")";
        double dip0 = stepValue(dec.getVoltageDipLevel() - rest.voltage());
        double latched0 = stepValue((rest.switchClosed() ? 1.0 : 0.0) - 0.5);
        double input0 = (dip0 + latched0 - dip0 * latched0) * stepValue(dec.getRegulatorLevel() - initial.efd());
        String inputSignal = name + "_DECI";
        devices.add(function(inputSignal, inputSignal, List.of(vt, latch, regulator), input0, input));
        String held = inputSignal;
        if (dec.getResetDelay() > 0.0) {
            String timer = name + "_DECT";
            devices.add(function(name + "_DECTU", name + "_DECTU", List.of(inputSignal, timer), 1.0 - input0 - input0 * rest.timer() / tau, "1-u[1]-u[1]*u[2]*"
                    + number(1.0 / tau)));
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + timer + ";4;4;" + timer + "," + name + "_DECTU,0,0,", number(rest.timer()) + ",1000,0,1,1,2,1,S1,",
                    "1", ";", "0 1")));
            held = name + "_DECG";
            String hold = limiterStep("(" + number(dec.getResetDelay()) + "-u[2])");
            devices.add(function(held, held, List.of(inputSignal, timer), input0, "u[1]+" + hold + "-u[1]*" + hold));
        }
        String s1 = name + "_DECS";
        devices.add(EmtpVoltageSourceConverters.scoped(function(s1, s1, List.of(held, dw), rest.switchClosed() ? 1.0 : 0.0, "u[1]*"
                + limiterStep("(" + number(dec.getSpeedChangeLevel()) + "-u[2])"))));
        devices.add(firstOrder(latch, latch, s1, rest.switchClosed() ? 1.0 : 0.0, 1.0, 1.0, 0.0, tau));
        String boostInput = name + "_DECNU";
        devices.add(function(boostInput, boostInput, List.of(s1, dw), 0.0, number(dec.getBoostGain()) + "*u[1]*u[2]"));
        String boost = name + "_DECN";
        if (dec.getBoostTimeConstant() > 0.0) {
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + boost + ";4;4;" + boost + "," + boostInput + ",0,0,", "0," + number(dec.getBoostMax())
                    + ",0,1,1,2,1,S1,", "1", ";", "1 " + number(dec.getBoostTimeConstant()))));
        } else {
            devices.add(new Device("_c_lim", List.of("_c_lim;" + boost + ";2;2;" + boost + "," + boostInput + ",", number(dec.getBoostMax()) + ",0,1,S1,")));
        }
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_DECVAN", name + "_DECVAN", List.of(boost), 0.0, "u[1]")));
        String sum = name + "_DECVU";
        devices.add(function(sum, sum, List.of(vs, boost, limiter, bangBang), rest.output(), "u[1]+u[2]-0.5*(u[3]+" + number(dec.getLimiterOutputMax())
                + "*u[4]+ABS(u[3]-" + number(dec.getLimiterOutputMax()) + "*u[4]))"));
        String clamped = name + "_DECV";
        devices.add(EmtpVoltageSourceConverters.scoped(new Device("_c_lim", List.of("_c_lim;" + clamped + ";2;2;" + clamped + "," + sum + ",",
                number(dec.getOutputMax()) + "," + number(dec.getOutputMin()) + ",1,S1,"))));
        devices.add(function(name + "_DECO", output, List.of(clamped), 0.0, "u[1]-(" + number(rest.output()) + ")"));
        return devices;
    }

    /** A washout {@code sT / (1 + sT)} from {@code input} to {@code output}, at rest at 0. */
    private static Device washout(String output, String input, double timeConstant) {
        return firstOrder(output, output, input, 0.0, 1.0, 0.0, timeConstant, timeConstant);
    }

    /** {@link EmtpSynchronousMachines#limiterStep}'s value at {@code x}. */
    private static double stepValue(double x) {
        return 0.5 * (1.0 + x / Math.sqrt(x * x + NumericalAids.LIMITER_STEP_EPSILON));
    }
}
