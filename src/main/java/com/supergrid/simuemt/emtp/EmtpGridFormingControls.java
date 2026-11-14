/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.SingleSequenceCurrentControl;
import com.powsybl.simuemt.extensions.hvdc.control.SteadyStateCurrentLimit;
import com.powsybl.simuemt.extensions.hvdc.control.TransientDamping;
import com.powsybl.simuemt.extensions.hvdc.control.VirtualAdmittance;
import com.powsybl.simuemt.extensions.hvdc.control.VirtualSynchronousMachine;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.clampBetween;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.clampExpression;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.integrator;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.measurement;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.park;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.step;
import static com.supergrid.simuemt.emtp.EmtpVoltageSourceConverters.windupGate;

/**
 * A {@link GridFormingControl} in EMTP's control blocks, as upstream's backends write it: the converter an internal EMF behind a virtual
 * admittance, its angle a {@link VirtualSynchronousMachine}'s, its magnitude on its reactive power droop; the current through the
 * admittance - integrated in the machine's frame, {@code (Xv / wb) di/dt = E - v - Rv i - j w Xv i} - the current reference, within the
 * current limit, which the single-sequence current loop follows. The swing {@code 2H dw/dt = P - P* - D (w - 1)}, its power reference
 * less a {@code DcVoltageDroop}'s share, damped by a {@link TransientDamping}'s {@code - Dt (w - wf)}; with a {@link
 * SteadyStateCurrentLimit} - WECC's REGFM_B1 -, its speed deviation within {@code +/- dw_max}, its angle the PLL's plus {@code delta_IT},
 * a limited integrator held within bounds the active current pulls in, and its EMF's magnitude within the bounds the reactive current
 * left sets. Every quantity drawn from the bus, as a grid-following control's; the Park transform on the EMF's angle; every block at
 * rest on the load flow.
 *
 * <p>Refused, until written: a power-frequency droop.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpGridFormingControls {

    private static final double MEASUREMENT_EPS = 1.0e-9;

    private EmtpGridFormingControls() {
    }

    /** Why {@code control} cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(GridFormingControl control) {
        List<String> reasons = new ArrayList<>();
        if (!(control.getSynchronization() instanceof VirtualSynchronousMachine)) {
            reasons.add("its grid-forming synchronization but the virtual synchronous machine is not written yet");
        }
        return reasons;
    }

    /**
     * {@code converter}'s grid-forming control, its bus voltages {@code v} and its EMFs' currents {@code i}, the bus at {@code busAngle0}
     * rad in the load flow: the Park transform, the measurements ({@code control:<id>_P}, {@code _Q}, {@code _VAC}, {@code _VDC}), the
     * angle {@code <id>_TH} and the EMF's components {@code <id>_ED}, {@code <id>_EQ} in its frame.
     */
    static void write(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedConverterModel model,
                      GridFormingControl control, AveragedVscInitialState initial, String[] v, String[] i, double busAngle0, double omega) {
        String id = EmtpVoltageSourceConverters.sanitize(converter.getId());
        double tau = control.getMeasurement().timeConstant();
        VirtualAdmittance admittance = control.getVirtualAdmittance();
        double rv = admittance.resistance();
        double xv = admittance.reactance();
        // The load flow's EMF behind the virtual admittance, its angle delta ahead of the bus's; everything in its frame.
        double[] emf0 = initial.internalEmf(admittance);
        double e0 = Math.hypot(emf0[0], emf0[1]);
        double delta = Math.atan2(emf0[1], emf0[0]);
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);
        double id0 = initial.directCurrent() * cos + initial.quadratureCurrent() * sin;
        double iq0 = -initial.directCurrent() * sin + initial.quadratureCurrent() * cos;
        double angle0 = busAngle0 + delta;

        String theta = id + "_TH";
        String vd = id + "_VD";
        String vq = id + "_VQ";
        String idPu = id + "_ID";
        String iqPu = id + "_IQ";
        double vbpk = initial.peakPhaseVoltageBase();
        double ibpk = initial.peakPhaseCurrentBase();
        devices.add(park(vd, vd, v, theta, "COS", vbpk, initial.terminalVoltage() * cos, false));
        devices.add(park(vq, vq, v, theta, "SIN", vbpk, -initial.terminalVoltage() * sin, true));
        devices.add(park(idPu, idPu, i, theta, "COS", -ibpk, id0, false));
        devices.add(park(iqPu, iqPu, i, theta, "SIN", -ibpk, iq0, true));
        String p = measurement(devices, id + "_P", List.of(vd, vq, idPu, iqPu), "u[1]*u[3]+u[2]*u[4]", tau, initial.activePower());
        String q = measurement(devices, id + "_Q", List.of(vd, vq, idPu, iqPu), "u[2]*u[3]-u[1]*u[4]", tau, initial.reactivePower());
        String vac = measurement(devices, id + "_VAC", List.of(vd, vq), "SQRT(u[1]*u[1]+u[2]*u[2])", tau, initial.terminalVoltage());
        String vdc = measurement(devices, id + "_VDC", List.of(id + "_VDP", id + "_VDR"), "(u[1]-u[2])/" + number(initial.dcNominalVoltage() * 1.0e3), tau,
                initial.dcVoltage() / initial.dcNominalVoltage());

        // The EMF's magnitude on its reactive power droop - within the reactive current limit's bounds, if any.
        String emf = id + "_GE";
        String droopEmf = number(e0) + "+(" + number(control.getVoltageDroop().droop()) + ")*(u[1]-(" + number(initial.reactivePower()) + "))";
        SteadyStateCurrentLimit limit = control.getSteadyStateCurrentLimit().orElse(null);
        if (limit != null) {
            devices.add(function(emf, emf, List.of(q, id + "_GLEL", id + "_GLEU"), e0, clampBetween(droopEmf, "u[2]", "u[3]")));
        } else {
            devices.add(function(emf, emf, List.of(q), e0, droopEmf));
        }

        // The current through the virtual admittance, in the machine's frame, delivered to the bus; limited, drawn from it.
        String speed = id + "_GW";
        String dState = id + "_GXD";
        String qState = id + "_GXQ";
        double rate = omega / xv;
        devices.add(function(id + "_GXDR", id + "_GXDR", List.of(emf, vd, dState, qState, speed), 0.0,
                "(u[1]-u[2]-(" + number(rv) + ")*u[3]+(" + number(xv) + ")*u[5]*u[4])*" + number(rate)));
        devices.add(function(id + "_GXQR", id + "_GXQR", List.of(vq, qState, dState, speed), 0.0,
                "(-u[1]-(" + number(rv) + ")*u[2]-(" + number(xv) + ")*u[4]*u[3])*" + number(rate)));
        devices.add(integrator(dState, dState, id + "_GXDR", 1.0, -id0));
        devices.add(integrator(qState, qState, id + "_GXQR", 1.0, -iq0));
        String magnitude = "SQRT(u[1]*u[1]+u[2]*u[2])";
        String ratio = "(" + number(control.getCurrentLimit().maxCurrent()) + ")/(0.5*(" + magnitude + "+" + number(MEASUREMENT_EPS) + "+ABS(" + magnitude + "-"
                + number(MEASUREMENT_EPS) + ")))";
        String scale = "0.5*(1+" + ratio + "-ABS(1-" + ratio + "))";
        String dRef = id + "_GRD";
        String qRef = id + "_GRQ";
        devices.add(function(dRef, dRef, List.of(dState, qState), id0, "-u[1]*" + scale));
        devices.add(function(qRef, qRef, List.of(dState, qState), iq0, "-u[2]*" + scale));

        // The virtual synchronous machine, on the power reference less the DC voltage's droop, damped by its damper winding's term.
        VirtualSynchronousMachine vsm = (VirtualSynchronousMachine) control.getSynchronization();
        String reference = control.getDcVoltageDroop().map(droop -> number(initial.activePower()) + "-(" + number(droop.gain()) + ")*(u[3]-("
                + number(initial.dcVoltage() / initial.dcNominalVoltage()) + "))").orElse(number(initial.activePower()));
        List<String> swingInputs = new ArrayList<>(List.of(p, speed, vdc));
        String damper = "";
        if (control.getTransientDamping().isPresent()) {
            TransientDamping damping = control.getTransientDamping().get();
            devices.add(firstOrder(id + "_GWF", id + "_GWF", speed, 1.0, 1.0, 1.0, 0.0, damping.timeConstant()));
            swingInputs.add(id + "_GWF");
            damper = "-(" + number(damping.gain()) + ")*(u[2]-u[4])";
        }
        String swing = "(u[1]-(" + reference + ")-(" + number(vsm.dampingCoefficient()) + ")*(u[2]-1)" + damper + ")/" + number(2.0 * vsm.inertiaConstant());
        String acceleration = id + "_GA";
        if (limit != null) {
            // REGFM_B1: the speed deviation a limited integrator's, within +/- dw_max.
            devices.add(function(id + "_GAR", id + "_GAR", swingInputs, 0.0, swing));
            devices.add(function(acceleration, acceleration, List.of(id + "_GAR", speed), 0.0,
                    "u[1]*" + windupGate("(u[2]-1)", number(limit.maxSpeedDeviation()), "u[1]")));
        } else {
            devices.add(function(acceleration, acceleration, swingInputs, 0.0, swing));
        }
        devices.add(integrator(speed, speed, acceleration, 1.0, 1.0));

        // The angle: the machine's own, or the PLL's plus delta_IT with a steady-state current limit.
        String deviation = id + "_GTD";
        if (limit == null) {
            devices.add(function(deviation, deviation, List.of(speed), 0.0, "(" + number(omega) + ")*(u[1]-1)"));
        } else {
            steadyStateCurrentLimit(devices, id, limit, xv, initial, omega, speed, vd, vq, idPu, iqPu, vac, delta, tau);
        }
        devices.add(integrator(id + "_GTI", id + "_GTI", deviation, 1.0, 0.0));
        devices.add(function(theta, theta, List.of(id + "_GTI"), angle0, "(" + number(angle0) + ")+(" + number(omega) + ")*t+u[1]"));

        // The current loop, decoupled, as a grid-following control's.
        SingleSequenceCurrentControl loop = (SingleSequenceCurrentControl) control.getCurrentControl();
        double r = model.getPhaseReactorResistance();
        double x = model.getPhaseReactorReactance();
        String ud = pi(devices, id + "_UD", dRef, idPu, loop, r * id0);
        String uq = pi(devices, id + "_UQ", qRef, iqPu, loop, r * iq0);
        double ed0 = initial.directEmf() * cos + initial.quadratureEmf() * sin;
        double eq0 = -initial.directEmf() * sin + initial.quadratureEmf() * cos;
        devices.add(function(id + "_ED", id + "_ED", List.of(vd, iqPu, ud), ed0, "u[1]+(" + number(x) + ")*u[2]-u[3]"));
        devices.add(function(id + "_EQ", id + "_EQ", List.of(vq, idPu, uq), eq0, "u[1]-(" + number(x) + ")*u[2]-u[3]"));
    }

    /**
     * A {@link SteadyStateCurrentLimit}: its PLL on the bus's q-axis voltage in the frame {@code delta_IT} behind the EMF's, its speed
     * deviation within {@link SteadyStateCurrentLimit#PLL_MAX_SPEED_DEVIATION}; the active current delivered in that frame; the EMF's
     * magnitude bounds {@code <id>_GLEU}, {@code <id>_GLEL}; {@code delta_IT} a limited integrator of {@code ws (w - 1) - dw_PLL} within
     * bounds of their own, limited integrators of {@code kI (+/-Id_max - Id)}; the angle's rate {@code <id>_GTD = dw_PLL + d(delta_IT)}.
     */
    private static void steadyStateCurrentLimit(List<EmtpSynchronousMachines.Device> devices, String id, SteadyStateCurrentLimit limit, double xv,
                                                AveragedVscInitialState initial, double omega, String speed, String vd, String vq, String idPu, String iqPu,
                                                String vac, double delta, double tau) {
        String internal = id + "_GLDY";
        String pllQ = id + "_GLQ";
        devices.add(function(pllQ, pllQ, List.of(vd, vq, internal), 0.0, "u[1]*SIN(u[3])+u[2]*COS(u[3])"));
        double pllLimit = SteadyStateCurrentLimit.PLL_MAX_SPEED_DEVIATION * omega;
        String pllIntegral = id + "_GLP";
        devices.add(function(id + "_GLPR", id + "_GLPR", List.of(pllQ, pllIntegral), 0.0,
                "(" + number(limit.pll().integralGain()) + ")*u[1]*" + windupGate("u[2]", number(pllLimit), "u[1]")));
        devices.add(integrator(pllIntegral, pllIntegral, id + "_GLPR", 1.0, 0.0));
        String pllDeviation = id + "_GLW";
        devices.add(function(pllDeviation, pllDeviation, List.of(pllQ, pllIntegral), 0.0,
                clampExpression("(" + number(limit.pll().proportionalGain()) + ")*u[1]+u[2]", number(pllLimit))));

        // The active current delivered, in the PLL's frame; the reactive current limit's bounds on the EMF's magnitude.
        String current = measurement(devices, id + "_GLI", List.of(idPu, iqPu, internal), "-(u[1]*COS(u[3])-u[2]*SIN(u[3]))", tau, -initial.directCurrent());
        String eps = number(MEASUREMENT_EPS);
        String reactive = "SQRT(0.5*((" + number(limit.steadyStateMaxCurrent() * limit.steadyStateMaxCurrent()) + "-u[2]*u[2])+" + eps + "+ABS(("
                + number(limit.steadyStateMaxCurrent() * limit.steadyStateMaxCurrent()) + "-u[2]*u[2])-" + eps + ")))";
        devices.add(function(id + "_GLEQ", id + "_GLEQ", List.of(vac, current), 0.0, reactive));
        for (String bound : List.of("U", "L")) {
            String sign = "U".equals(bound) ? "+" : "-";
            String square = "((u[1]" + sign + "(" + number(xv) + ")*u[3])*(u[1]" + sign + "(" + number(xv) + ")*u[3])+((" + number(xv) + ")*u[2])*(("
                    + number(xv) + ")*u[2]))";
            devices.add(function(id + "_GLE" + bound, id + "_GLE" + bound, List.of(vac, current, id + "_GLEQ"), initial.terminalVoltage(),
                    "SQRT(0.5*(" + square + "+" + eps + "+ABS(" + square + "-" + eps + ")))"));
        }
        double maxAngle = limit.maxAngle(xv);
        double maxActive = limit.maxActiveCurrent();
        String gain = number(limit.integralGain());
        String upper = limitedIntegrator(devices, id + "_GLH", List.of(current), "(" + gain + ")*(" + number(maxActive) + "-u[1])", "0", number(maxAngle), maxAngle);
        String lower = limitedIntegrator(devices, id + "_GLN", List.of(current), "(" + gain + ")*(" + number(-maxActive) + "-u[1])", number(-maxAngle), "0",
                -maxAngle);
        limitedIntegrator(devices, id + "_GLD", List.of(speed, pllDeviation, lower, upper), "(" + number(omega) + ")*(u[1]-1)-u[2]", "u[3]", "u[4]", delta);
        devices.add(function(id + "_GTD", id + "_GTD", List.of(pllDeviation, id + "_GLDE"), 0.0, "u[1]+u[2]"));
    }

    /**
     * A limited integrator, as the specification draws it: its state {@code <prefix>X} integrating {@code rate} - an expression of {@code
     * inputs}' {@code u[k]} - but held where it would pass {@code low} or {@code high} (expressions of the same inputs, or numbers), drawn
     * back within {@link SteadyStateCurrentLimit#BOUND_TRACKING_TIME_CONSTANT} when a bound moves past it; its output {@code <prefix>Y} the
     * state within its bounds, its effective rate {@code <prefix>E}.
     */
    private static String limitedIntegrator(List<EmtpSynchronousMachines.Device> devices, String prefix, List<String> inputs, String rate, String low,
                                            String high, double initialValue) {
        String state = prefix + "X";
        String output = prefix + "Y";
        String effective = prefix + "E";
        int n = inputs.size();
        List<String> withState = new ArrayList<>(inputs);
        withState.add(state);
        String x = "u[" + (n + 1) + "]";
        devices.add(function(output, output, withState, initialValue, clampBetween(x, low, high)));
        withState.add(output);
        String y = "u[" + (n + 2) + "]";
        String raw = "(" + rate + ")";
        String held = "(1-" + step(x + "-(" + high + ")") + "*" + step(raw) + "-" + step("(" + low + ")-" + x) + "*" + step("-" + raw) + ")";
        devices.add(function(effective, effective, withState, 0.0, raw + "*" + held + "+(" + y + "-" + x + ")*"
                + number(1.0 / SteadyStateCurrentLimit.BOUND_TRACKING_TIME_CONSTANT)));
        devices.add(integrator(state, state, effective, 1.0, initialValue));
        return output;
    }

    /** A PI {@code KP (ref - meas) + KI int(ref - meas)}, its integral at {@code initialOutput}. */
    private static String pi(List<EmtpSynchronousMachines.Device> devices, String name, String reference, String measured, SingleSequenceCurrentControl loop,
                             double initialOutput) {
        devices.add(function(name + "E", name + "E", List.of(reference, measured), 0.0, "u[1]-u[2]"));
        devices.add(integrator(name + "I", name + "I", name + "E", loop.integralGain(), initialOutput));
        devices.add(function(name, name, List.of(name + "E", name + "I"), initialOutput, "(" + number(loop.proportionalGain()) + ")*u[1]+u[2]"));
        return name;
    }
}
