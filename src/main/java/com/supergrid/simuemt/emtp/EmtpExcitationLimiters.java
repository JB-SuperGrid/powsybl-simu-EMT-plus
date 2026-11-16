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

    /** An OEL1B's output signal {@code OV} and whether it takes over rather than sums. */
    record Oel(String output, boolean takeover) {
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
        return new Oel(output, oel.isTakeoverMode());
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

    /** ATP's OEL comparator {@code 0.5 (1 + x / sqrt(x^2 + eps))} of the expression {@code x}. */
    private static String smoothStep(String x) {
        return "0.5*(1+(" + x + ")/SQRT((" + x + ")*(" + x + ")+" + number(NumericalAids.OEL_COMPARATOR_EPSILON) + "))";
    }

    private static double smoothStep(double x) {
        return 0.5 * (1.0 + x / Math.sqrt(x * x + NumericalAids.OEL_COMPARATOR_EPSILON));
    }
}
