/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Battery;
import com.powsybl.iidm.network.Bus;
import com.powsybl.simuemt.extensions.battery.BatteryModel;
import com.powsybl.simuemt.extensions.battery.ReecCInitialState;
import com.powsybl.simuemt.extensions.battery.ReecCModel;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * A {@link ReecCModel} battery in EMTP, as upstream's backends model it: a synchronous-frame PLL locked on its bus; its voltage, the PLL's
 * {@code vd}, through its transducer's lag; its reactive current {@code Iq} a PI ({@code Kvp}, {@code Kvi}) plus {@code Kqv} on {@code Vref
 * - V}, within {@code [Iql1, Iqh1]}; its active current its power reference - the load flow's - through a lag within {@code [Pmin, Pmax]},
 * over the voltage floored at {@link #VOLTAGE_FLOOR_PU}; within {@code Imax}, the reactive current first. Realized as an EMF {@code E = V +
 * Xc Iq + j Xc Id} in the PLL's frame behind a coupling reactance {@code Xc}, which injects {@code Id + j Iq} - rebuilt a step ahead of the
 * PLL's angle, EMTP's network taking a control signal a step after the control computes it. Every block at rest on the load flow ({@link
 * ReecCInitialState}); per unit on {@link ReecCInitialState#BASE_APPARENT_POWER_MVA}. Like upstream's backends: no state of charge (the model
 * has no energy capacity), none of REEC_C's flags, deadbands and freezes.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpBatteries {

    /** The coupling reactance, per unit, as upstream's backends size it. */
    static final double COUPLING_REACTANCE_PU = 0.1;
    /** The PLL's gains on its q-axis voltage in pu, as upstream's backends tune them: not REEC_C's. */
    private static final double PLL_PROPORTIONAL_GAIN = 60.0;
    private static final double PLL_INTEGRAL_GAIN = 1400.0;
    /** The voltage the active current is divided by, at the least. */
    static final double VOLTAGE_FLOOR_PU = 0.05;
    private static final double MEASUREMENT_EPS = 1.0e-9;

    private EmtpBatteries() {
    }

    /** Why {@code battery} cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(Battery battery) {
        return battery.getExtension(BatteryModel.class) instanceof ReecCModel ? List.of() : List.of("its model but REEC_C is not written yet");
    }

    /** {@code battery}'s devices on its bus's phases {@code phaseNodes}, its bus displaced by {@code offsetDegrees} - its vector group's. */
    static List<EmtpSynchronousMachines.Device> battery(Battery battery, String[] phaseNodes, double offsetDegrees, EmtpSimulationSettings settings) {
        ReecCModel model = (ReecCModel) battery.getExtension(BatteryModel.class);
        ReecCInitialState initial = ReecCInitialState.of(battery, model);
        Bus bus = battery.getTerminal().getBusBreakerView().getBus();
        double nominalKv = bus.getVoltageLevel().getNominalV();
        double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
        double dt = settings.timeStepSeconds();
        double vbpk = nominalKv * 1.0e3 * Math.sqrt(2.0 / 3.0);
        double angle0 = Math.toRadians(bus.getAngle() + offsetDegrees);
        double vt0 = initial.terminalVoltage();
        double id0 = initial.directCurrent();
        double iq0 = initial.quadratureCurrent();
        double ed0 = vt0 + COUPLING_REACTANCE_PU * iq0;
        double eq0 = COUPLING_REACTANCE_PU * id0;
        String id = EmtpVoltageSourceConverters.sanitize(battery.getId());
        String zero = id + "_ZERO";
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        devices.add(constant(zero, 0.0));

        // The power stage, phase by phase: the bus voltage's probe; the EMF - its stand-in in the steady state - behind the coupling reactance.
        double couplingH = COUPLING_REACTANCE_PU * nominalKv * nominalKv / ReecCInitialState.BASE_APPARENT_POWER_MVA / omega;
        String star = id + "_N";
        String[] v = new String[3];
        for (int k = 0; k < 3; k++) {
            char phase = "abc".charAt(k);
            double offset = -2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k);
            String node = phaseNodes[k];
            v[k] = id + "_V" + phase;
            devices.add(element("_cI", id + "_PR" + phase, List.of(node, "", zero, v[k], id + "_PRI" + phase, id + "_PRP" + phase), "-1,1E15,0,0,0,0,>v,0,0,"));
            String inner = id + "_K" + phase;
            String standIn = id + "_M" + phase;
            devices.add(element("_RLC", id + "_LC" + phase, List.of(inner, node), "0," + number(couplingH) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LCD" + phase, List.of(inner, node), number(NumericalAids.seriesInductanceDampingOhm(couplingH, dt)) + ",0,0,0,0,"));
            devices.add(element("_Vsine", id + "_STD" + phase, List.of(inner, standIn), number(vbpk * Math.hypot(ed0, eq0)) + ","
                    + number(settings.nominalFrequencyHz()) + "," + number(Math.toDegrees(angle0 + Math.atan2(eq0, ed0) + offset)) + ",-1,1E-15,"));
            devices.add(element("_cV", id + "_CV" + phase, List.of(standIn, star, id + "_E" + phase, id + "_CVV" + phase, id + "_CVI" + phase, id + "_CVP" + phase),
                    "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            // A step ahead of the PLL's angle.
            devices.add(function(id + "_E" + phase, id + "_E" + phase, List.of(id + "_ED", id + "_EQ", id + "_TH"), vbpk * (ed0 * Math.cos(angle0 + offset)
                    - eq0 * Math.sin(angle0 + offset)), number(vbpk) + "*(u[1]*COS(u[3]+" + angleTerm(offset + omega * dt) + ")-u[2]*SIN(u[3]+"
                    + angleTerm(offset + omega * dt) + "))"));
        }
        devices.add(element("_RLC", id + "_NREF", List.of(star), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,"));

        // The PLL: the Park transform on its angle, omega = w + Kp vq + Ki int(vq).
        String theta = id + "_TH";
        String vd = id + "_VD";
        String vq = id + "_VQ";
        devices.add(EmtpVoltageSourceConverters.park(vd, vd, v, theta, "COS", vbpk, vt0, false));
        devices.add(EmtpVoltageSourceConverters.park(vq, vq, v, theta, "SIN", vbpk, 0.0, true));
        devices.add(EmtpVoltageSourceConverters.integrator(id + "_PLI", id + "_PLI", vq, PLL_INTEGRAL_GAIN, 0.0));
        devices.add(function(id + "_PLW", id + "_PLW", List.of(vq, id + "_PLI"), 0.0, number(PLL_PROPORTIONAL_GAIN) + "*u[1]+u[2]"));
        devices.add(EmtpVoltageSourceConverters.integrator(id + "_PLT", id + "_PLT", id + "_PLW", 1.0, 0.0));
        devices.add(function(theta, theta, List.of(id + "_PLT"), angle0, "(" + number(angle0) + ")+(" + number(omega) + ")*t+u[1]"));

        // The voltage through the transducer; the reactive current.
        String vt = id + "_VT";
        devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(vt, vt, vd, vt0, 1.0, 1.0, 0.0, model.getTransducerTimeConstant())));
        String error = id + "_VE";
        devices.add(function(error, error, List.of(vt), initial.voltageReference() - vt0, number(initial.voltageReference()) + "-u[1]"));
        devices.add(EmtpVoltageSourceConverters.integrator(id + "_QI", id + "_QI", error, model.getVoltageRegulatorIntegralGain(), initial.integralState()));
        String iq = id + "_IQ";
        devices.add(EmtpVoltageSourceConverters.scoped(function(iq, iq, List.of(error, id + "_QI"), iq0,
                clamp("(" + number(model.getVoltageRegulatorProportionalGain() + model.getReactiveCurrentGain()) + ")*u[1]+u[2]",
                        number(model.getReactiveCurrentLimitMin()), number(model.getReactiveCurrentLimitMax())))));

        // The active current: the power reference through its lag, within its limits, over the floored voltage; within what Imax leaves.
        String reference = id + "_PREF";
        devices.add(constant(reference, initial.activePower()));
        String power = id + "_PF";
        devices.add(new EmtpSynchronousMachines.Device("_c_fslim", List.of("_c_fslim;" + power + ";4;4;" + power + "," + reference + ",0,0,",
                number(initial.activePower()) + "," + number(model.getActivePowerMax()) + "," + number(model.getActivePowerMin()) + ",1,1,2,1,S1,", "1", ";",
                "1 " + number(model.getActivePowerFilterTimeConstant()))));
        String headroom = id + "_IDL";
        double imax2 = model.getCurrentLimitMax() * model.getCurrentLimitMax();
        String squared = "(" + number(imax2) + ")-u[1]*u[1]";
        devices.add(function(headroom, headroom, List.of(iq), Math.sqrt(Math.max(imax2 - iq0 * iq0, MEASUREMENT_EPS)),
                "SQRT(0.5*((" + squared + ")+" + number(MEASUREMENT_EPS) + "+ABS((" + squared + ")-" + number(MEASUREMENT_EPS) + ")))"));
        String floored = "0.5*(u[2]+" + number(VOLTAGE_FLOOR_PU) + "+ABS(u[2]-" + number(VOLTAGE_FLOOR_PU) + "))";
        String idPu = id + "_ID";
        devices.add(EmtpVoltageSourceConverters.scoped(function(idPu, idPu, List.of(power, vt, headroom), id0, clamp("u[1]/(" + floored + ")", "(-u[3])", "u[3]"))));

        // The EMF in the PLL's frame.
        devices.add(function(id + "_ED", id + "_ED", List.of(vt, iq), ed0, "u[1]+(" + number(COUPLING_REACTANCE_PU) + ")*u[2]"));
        devices.add(function(id + "_EQ", id + "_EQ", List.of(idPu), eq0, "(" + number(COUPLING_REACTANCE_PU) + ")*u[1]"));
        return devices;
    }

    /** {@code x} within {@code [low, high]}. */
    private static String clamp(String x, String low, String high) {
        String below = "0.5*((" + x + ")+(" + high + ")-ABS((" + x + ")-(" + high + ")))";
        return "0.5*((" + below + ")+(" + low + ")+ABS((" + below + ")-(" + low + ")))";
    }

    /** An angle added to another in an expression, parenthesized. */
    private static String angleTerm(double radians) {
        return "(" + number(radians) + ")";
    }

    private static EmtpSynchronousMachines.Device constant(String name, double value) {
        return new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + name + ";1;1;" + name + ",", number(value) + ",S1,"));
    }

    /** An electrical device: its header - part, name, its pins twice, its nodes - and its data line. */
    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",", data));
    }
}
