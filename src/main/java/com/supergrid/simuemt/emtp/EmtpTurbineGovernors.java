/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.PowerReferenceStep;
import com.powsybl.simuemt.extensions.governor.GastModel;
import com.powsybl.simuemt.extensions.governor.GoverDtrI8Model;
import com.powsybl.simuemt.extensions.governor.GoverProportionalModel;
import com.powsybl.simuemt.extensions.governor.HygovInitialState;
import com.powsybl.simuemt.extensions.governor.HygovModel;
import com.powsybl.simuemt.extensions.governor.IeeeG2Model;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.Device;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * A synchronous machine's turbine-governor, as ATP's TACS has it ({@code AtpTacsAvrPssWriter}), block for block: from the machine's
 * speed deviation {@code dw} to its one mass's power ({@code Pm_1}), per unit, every block at rest on the load flow's mechanical power
 * {@code Pm0}, its power scoped ({@code control:<name>_PM}). Its reference stepped by a {@link PowerReferenceStep} at its instant, on the time step, as ATP's.
 *
 * <ul>
 *     <li>TGOV1: {@code Pm0 - dw / R}, the valve's lag within its limits - its state stopped there, TGOV1's non-windup limit -, the
 *     turbine's lead-lag; {@code Pm = turbine - Dt dw}.</li>
 *     <li>GAST: the fuel demand {@code Pm0 - dw / R} held below the load limit {@code AT + KT (AT - y3)}, the fuel valve's limited
 *     lag, the turbine's and the exhaust temperature's lags; {@code Pm = y2 - Dturb dw}.</li>
 *     <li>IEEEG2: {@code dw} through the governor's lag and the lead-lag, the demand {@code Pm0 - K y2} within its limits, the water
 *     column {@code (1 - sT4) / (1 + sT4 / 2)}.</li>
 *     <li>GoverDTRI8: {@code Pm0 / (1 + dw) - K dw} through one lag.</li>
 *     <li>GoverProportional: on the turbine's rating, {@code Pm0 - K dw} within its limits, or the reference alone.</li>
 *     <li>HYGOV: the error {@code PmRef - R c - dw} through its filter, within the gate's velocity, over {@code r} into the limited PI
 *     {@code 1 + 1 / (s Tr)}, the servomotor's lag; the water column the integral of {@code (H - (q / g)^2) / Tw}; {@code Pm = At (q /
 *     g)^2 (q - qNL) - Dturb dw g} ({@link HygovInitialState}).</li>
 * </ul>
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpTurbineGovernors {

    private EmtpTurbineGovernors() {
    }

    /** Whether {@code governor}, enabled, is one this writes. */
    static boolean isWritten(TurbineGovernorModel governor) {
        return governor instanceof Tgov1Model || governor instanceof GastModel || governor instanceof IeeeG2Model || governor instanceof GoverDtrI8Model
                || governor instanceof GoverProportionalModel || governor instanceof HygovModel;
    }

    /**
     * The governor's devices, from {@code deviation} - the machine's speed deviation - to {@code massPower}, the machine's mass's power
     * control signal; {@code step}, if any, stepping its reference.
     */
    static List<Device> devices(Generator generator, TurbineGovernorModel governor, String name, String deviation, String massPower, double pm0,
                                PowerReferenceStep step, double timeStepSeconds) {
        String stepped = step == null ? "" : "+" + number(step.deltaPu()) + "*" + EmtpVoltageSourceConverters.step("t-("
                + number(NumericalAids.switchingInstant(step.timeSeconds(), timeStepSeconds)) + ")");
        List<Device> devices = new ArrayList<>();
        if (governor instanceof Tgov1Model tgov1) {
            String demand = name + "_GOV";
            String valve = name + "_VALVE";
            String turbine = name + "_TURB";
            devices.add(function(name + "_GOV", demand, List.of(deviation), pm0, number(pm0) + stepped + "-u[1]/" + number(tgov1.getDroop())));
            devices.add(limitedLag(name + "_VALVE", valve, demand, tgov1.getGovernorTimeConstant(), tgov1.getValveMin(), tgov1.getValveMax(), pm0));
            devices.add(firstOrder(name + "_TURB", turbine, valve, pm0, 1.0, 1.0, tgov1.getTurbineLeadTimeConstant(), tgov1.getTurbineLagTimeConstant()));
            devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(turbine, deviation), pm0, "u[1]-" + number(tgov1.getTurbineDamping()) + "*u[2]")));
        } else if (governor instanceof GastModel gast) {
            gast.checkInitialPower(generator, pm0);
            String input = name + "_GAU";
            String valve = name + "_GAV";
            String turbine = name + "_GAY";
            String temperature = name + "_GAT";
            // The least of the demand and the load limit: min(a, b) = (a + b - |a - b|) / 2.
            String demandExpression = "(" + number(pm0) + stepped + "-u[1]/" + number(gast.getDroop()) + ")";
            String limitExpression = "(" + number(gast.getLoadLimit()) + "+" + number(gast.getLoadLimitGain()) + "*(" + number(gast.getLoadLimit()) + "-u[2]))";
            devices.add(function(name + "_GAU", input, List.of(deviation, temperature), pm0, "0.5*(" + demandExpression + "+" + limitExpression + "-ABS("
                    + demandExpression + "-" + limitExpression + "))"));
            devices.add(EmtpVoltageSourceConverters.scoped(limitedLag(name + "_GAV", valve, input, gast.getValveTimeConstant(), gast.getValveMin(), gast.getValveMax(), pm0)));
            devices.add(firstOrder(name + "_GAY", turbine, valve, pm0, 1.0, 1.0, 0.0, gast.getTurbineTimeConstant()));
            devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_GAT", temperature, turbine, pm0, 1.0, 1.0, 0.0, gast.getLoadLimitTimeConstant())));
            devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(turbine, deviation), pm0, "u[1]-" + number(gast.getTurbineDamping()) + "*u[2]")));
        } else if (governor instanceof IeeeG2Model ieeeG2) {
            ieeeG2.checkInitialPower(generator, pm0);
            String speed = name + "_IGY1";
            devices.add(firstOrder(name + "_IGY1", speed, deviation, 0.0, 1.0, 1.0, 0.0, ieeeG2.getGovernorTimeConstant()));
            if (ieeeG2.getLagTimeConstant() > 0.0) {
                devices.add(firstOrder(name + "_IGY2", name + "_IGY2", speed, 0.0, 1.0, 1.0, ieeeG2.getLeadTimeConstant(), ieeeG2.getLagTimeConstant()));
                speed = name + "_IGY2";
            }
            String raw = name + "_IGR";
            String demand = name + "_IGU";
            devices.add(function(name + "_IGR", raw, List.of(speed), pm0, number(pm0) + stepped + "-" + number(ieeeG2.getGain()) + "*u[1]"));
            devices.add(EmtpVoltageSourceConverters.scoped(clamp(name + "_IGU", demand, raw, ieeeG2.getPowerMin(), ieeeG2.getPowerMax())));
            if (ieeeG2.getWaterTimeConstant() > 0.0) {
                devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_PM", massPower, demand, pm0, 1.0, 1.0, -ieeeG2.getWaterTimeConstant(), ieeeG2.getWaterTimeConstant() / 2.0)));
            } else {
                devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(demand), pm0, "u[1]")));
            }
        } else if (governor instanceof GoverDtrI8Model goverDtrI8) {
            String input = name + "_GDU";
            devices.add(function(name + "_GDU", input, List.of(deviation), pm0, "(" + number(pm0) + stepped + ")/(1+u[1])-" + number(goverDtrI8.getGain()) + "*u[1]"));
            devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_PM", massPower, input, pm0, 1.0, 1.0, 0.0, goverDtrI8.getTimeConstant())));
        } else if (governor instanceof GoverProportionalModel proportional) {
            double toTurbine = proportional.toTurbineRating(generator);
            String reference = number(pm0 * toTurbine) + stepped;
            String turbine = name + "_GPT";
            if (proportional.regulates(generator, pm0)) {
                devices.add(function(name + "_GPR", name + "_GPR", List.of(deviation), pm0 * toTurbine, reference + "-" + number(proportional.getGain()) + "*u[1]"));
                devices.add(clamp(name + "_GPT", turbine, name + "_GPR", proportional.getPowerMinPu(), proportional.getPowerMaxPu()));
            } else {
                devices.add(function(name + "_GPT", turbine, List.of(deviation), pm0 * toTurbine, reference + "+0*u[1]"));
            }
            devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(turbine), pm0, "u[1]/" + number(toTurbine))));
        } else if (governor instanceof HygovModel hygov) {
            devices.addAll(hygov(generator, hygov, name, deviation, massPower, pm0, stepped));
        }
        return devices;
    }

    private static List<Device> hygov(Generator generator, HygovModel hygov, String name, String deviation, String massPower, double pm0, String stepped) {
        HygovInitialState state = HygovInitialState.of(generator, hygov, pm0);
        String demand = name + "_HYPO";
        String filtered = name + "_HYF";
        String limited = name + "_HYL";
        String gate = name + "_HYG";
        String flow = name + "_HYQ";
        String ratio = name + "_HYH";
        List<Device> devices = new ArrayList<>();
        devices.add(function(name + "_HYE", name + "_HYE", List.of(demand, deviation), 0.0, number(state.reference()) + stepped + "-"
                + number(hygov.getPermanentDroop()) + "*u[1]-u[2]"));
        devices.add(firstOrder(name + "_HYF", filtered, name + "_HYE", 0.0, 1.0, 1.0, 0.0, hygov.getFilterTimeConstant()));
        devices.add(clamp(name + "_HYL", limited, filtered, -hygov.getGateVelocityLimit(), hygov.getGateVelocityLimit()));
        devices.add(function(name + "_HYU", name + "_HYU", List.of(limited), 0.0, "u[1]/" + number(hygov.getTemporaryDroop())));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_HYP", demand, name + "_HYU", 1.0, 1.0 / hygov.getGovernorTimeConstant(), hygov.getGateMin(),
                hygov.getGateMax(), state.gate(), state.gate()));
        devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_HYG", gate, demand, state.gate(), 1.0, 1.0, 0.0, hygov.getGateTimeConstant())));
        // q / max(g, floor), max(a, b) = (a + b + |a - b|) / 2.
        String floor = number(HygovModel.GATE_FLOOR);
        devices.add(function(name + "_HYH", ratio, List.of(flow, gate), state.flow() / Math.max(state.gate(), HygovModel.GATE_FLOOR),
                "u[1]/(0.5*(u[2]+" + floor + "+ABS(u[2]-" + floor + ")))"));
        devices.add(function(name + "_HYW", name + "_HYW", List.of(ratio), 0.0, number(hygov.getHead()) + "-u[1]*u[1]"));
        devices.add(integrator(name + "_HYQ", flow, name + "_HYW", state.flow(), 1.0 / hygov.getWaterInertiaTimeConstant()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(ratio, flow, deviation, gate), pm0, number(hygov.getTurbineGain()) + "*u[1]*u[1]*(u[2]-"
                + number(hygov.getNoLoadFlow()) + ")-" + number(hygov.getTurbineDamping()) + "*u[3]*u[4]")));
        return devices;
    }

    /** A lag {@code 1 / (1 + sT)} from {@code input} to {@code output} at {@code output0}, its state stopped within {@code [min, max]}. */
    private static Device limitedLag(String device, String output, String input, double lag, double min, double max, double output0) {
        return new Device("_c_fslim", List.of("_c_fslim;" + device + ";4;4;" + output + "," + input + ",0,0,",
                number(output0) + "," + number(max) + "," + number(min) + ",1,1,2,1,S1,", "1", ";", "1 " + number(lag)));
    }

    /** {@code input} held within {@code [min, max]}. */
    private static Device clamp(String device, String output, String input, double min, double max) {
        return new Device("_c_lim", List.of("_c_lim;" + device + ";2;2;" + output + "," + input + ",", number(max) + "," + number(min) + ",1,S1,"));
    }

    /** {@code gain / s} from {@code input} to {@code output}, at {@code output0}. */
    private static Device integrator(String device, String output, String input, double output0, double gain) {
        return new Device("_c_fs", List.of("_c_fs;" + device + ";2;2;" + output + "," + input + ",", number(output0) + ",1,1,2," + number(gain) + ",S1,",
                "1", ";", "0 1"));
    }
}
