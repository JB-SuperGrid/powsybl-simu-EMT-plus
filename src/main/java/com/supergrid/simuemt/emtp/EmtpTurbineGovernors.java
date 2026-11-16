/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.FastValving;
import com.powsybl.simuemt.extensions.event.PowerReferenceStep;
import com.powsybl.simuemt.extensions.governor.GastModel;
import com.powsybl.simuemt.extensions.governor.GovHydro4Model;
import com.powsybl.simuemt.extensions.governor.GovSteam1Model;
import com.powsybl.simuemt.extensions.governor.GoverDtrI8Model;
import com.powsybl.simuemt.extensions.governor.GoverProportionalModel;
import com.powsybl.simuemt.extensions.governor.HygovInitialState;
import com.powsybl.simuemt.extensions.governor.HygovModel;
import com.powsybl.simuemt.extensions.governor.IeeeG1Model;
import com.powsybl.simuemt.extensions.governor.IeeeG2Model;
import com.powsybl.simuemt.extensions.governor.TGov3Model;
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
                || governor instanceof GoverProportionalModel || governor instanceof HygovModel || governor instanceof IeeeG1Model
                || governor instanceof GovSteam1Model || governor instanceof GovHydro4Model || governor instanceof TGov3Model;
    }

    /**
     * The governor's devices, from {@code deviation} - the machine's speed deviation - to {@code massPower}, the machine's mass's power
     * control signal; {@code step}, if any, stepping its reference; {@code fastValving}, if any, closing a TGOV3's intercept valve.
     */
    static List<Device> devices(Generator generator, TurbineGovernorModel governor, String name, String deviation, String massPower, double pm0,
                                PowerReferenceStep step, FastValving fastValving, double timeStepSeconds) {
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
        } else if (governor instanceof IeeeG1Model ieeeG1) {
            devices.addAll(ieeeG1(generator, ieeeG1, name, deviation, massPower, pm0, stepped));
        } else if (governor instanceof GovSteam1Model govSteam1) {
            devices.addAll(govSteam1(generator, govSteam1, name, deviation, massPower, pm0, stepped));
        } else if (governor instanceof GovHydro4Model govHydro4) {
            devices.addAll(govHydro4(generator, govHydro4, name, deviation, massPower, pm0, stepped));
        } else if (governor instanceof TGov3Model tgov3) {
            devices.addAll(tgov3(generator, tgov3, name, deviation, massPower, pm0, stepped, fastValving, timeStepSeconds));
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

    /**
     * IEEEG1: {@code dw} through the governor's lead-lag; the valve the integral of {@code (PmRef - K x - G) / T3} within its rates,
     * stopped at its limits ({@link EmtpSynchronousMachines#limitedPi}); the four stages' lags in cascade, a stage of time constant 0
     * passing its input through; {@code Pm} the stages' high- and low-pressure fractions summed.
     */
    private static List<Device> ieeeG1(Generator generator, IeeeG1Model ieeeG1, String name, String deviation, String massPower, double pm0, String stepped) {
        double reference0 = ieeeG1.initialReference(generator, pm0);
        List<Device> devices = new ArrayList<>();
        String speed = leadLag(devices, name + "_IEX", deviation, ieeeG1.getLeadTimeConstant(), ieeeG1.getLagTimeConstant());
        String valve = name + "_IEGO";
        devices.add(function(name + "_IEE", name + "_IEE", List.of(speed, valve), 0.0, "(" + number(reference0) + stepped + "-" + number(ieeeG1.getGain())
                + "*u[1]-u[2])/" + number(ieeeG1.getValveTimeConstant())));
        devices.add(clamp(name + "_IER", name + "_IER", name + "_IEE", ieeeG1.getValveClosingRate(), ieeeG1.getValveOpeningRate()));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_IEG", valve, name + "_IER", 0.0, 1.0, ieeeG1.getPowerMin(), ieeeG1.getPowerMax(), reference0,
                reference0));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_IEV", name + "_IEV", List.of(valve), reference0, "u[1]")));
        devices.add(stages(devices, name + "_IEY", valve, reference0, ieeeG1.getStageTimeConstants(), ieeeG1.getHighPressureFractions(),
                ieeeG1.getLowPressureFractions(), name + "_PM", massPower, pm0));
        return devices;
    }

    /**
     * GovSteam1: IEEEG1's, {@code dw} first through the intentional deadband ({@link #deadband}), the valve's backlash ({@link
     * #backlash}) fed back in place of the valve if so given, and the valve's characteristic ({@link #characteristic}) between the
     * backlash and the stages ({@link GovSteam1Model#initialState}).
     */
    private static List<Device> govSteam1(Generator generator, GovSteam1Model govSteam1, String name, String deviation, String massPower, double pm0,
                                          String stepped) {
        GovSteam1Model.InitialState initial = govSteam1.initialState(generator, pm0);
        List<Device> devices = new ArrayList<>();
        String banded = govSteam1.isIntentionalDeadbandApplied()
                ? deadband(devices, name + "_GSD", deviation, govSteam1.getIntentionalDeadband(), govSteam1.getIntentionalDeadbandHysteresis()) : deviation;
        String speed = leadLag(devices, name + "_GSX", banded, govSteam1.getLeadTimeConstant(), govSteam1.getLagTimeConstant());
        String valve = name + "_GSGO";
        String backlash = name + "_GSB";
        devices.add(function(name + "_GSE", name + "_GSE", List.of(speed, govSteam1.isUnintentionalDeadbandInFeedback() ? backlash : valve), 0.0, "("
                + number(initial.reference()) + stepped + "-" + number(govSteam1.getGain()) + "*u[1]-u[2])/" + number(govSteam1.getValveTimeConstant())));
        devices.add(clamp(name + "_GSR", name + "_GSR", name + "_GSE", govSteam1.getValveClosingRate(), govSteam1.getValveOpeningRate()));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_GSG", valve, name + "_GSR", 0.0, 1.0, govSteam1.getPowerMin(), govSteam1.getPowerMax(),
                initial.valve(), initial.valve()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GSV", name + "_GSV", List.of(valve), initial.valve(), "u[1]")));
        devices.addAll(backlash(name + "_GSB", backlash, valve, govSteam1.getUnintentionalDeadband(), initial.backlash()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GSBS", name + "_GSBS", List.of(backlash), initial.backlash(), "u[1]")));
        String stage = backlash;
        if (govSteam1.isValveCharacteristicApplied()) {
            List<GovSteam1Model.ValvePoint> points = govSteam1.getValveCharacteristic();
            double[] openings = points.stream().mapToDouble(GovSteam1Model.ValvePoint::opening).toArray();
            double[] slopes = new double[points.size() - 1];
            for (int k = 0; k < slopes.length; k++) {
                slopes[k] = govSteam1.slope(k);
            }
            stage = name + "_GSS";
            devices.add(characteristic(name + "_GSS", stage, backlash, points.get(0).flow(), openings, slopes, initial.flow()));
        }
        devices.add(stages(devices, name + "_GSY", stage, initial.flow(), govSteam1.getStageTimeConstants(), govSteam1.getHighPressureFractions(),
                govSteam1.getLowPressureFractions(), name + "_PM", massPower, pm0));
        return devices;
    }

    /**
     * GovHydro4: {@code dw} through GovSteam1's intentional deadband; the error {@code PRef - d - RPerm gv - RTemp (gv - x)}, {@code x} the
     * dashpot's lag on the backlash's output; the pilot servo's lag over {@code TG} within the gate's rates, integrated into the gate,
     * stopped at its limits; GovSteam1's backlash; the gate's characteristic, kept above {@link GovHydro4Model#FLOW_FLOOR} under the
     * division; HYGOV's water column ({@link GovHydro4Model#initialState}).
     */
    private static List<Device> govHydro4(Generator generator, GovHydro4Model govHydro4, String name, String deviation, String massPower, double pm0,
                                          String stepped) {
        GovHydro4Model.InitialState initial = govHydro4.initialState(generator, pm0);
        List<Device> devices = new ArrayList<>();
        String banded = govHydro4.getIntentionalDeadband() > 0.0 || govHydro4.getIntentionalDeadbandHysteresis() > 0.0
                ? deadband(devices, name + "_GHD", deviation, govHydro4.getIntentionalDeadband(), govHydro4.getIntentionalDeadbandHysteresis()) : deviation;
        String backlash = name + "_GHB";
        String dashpot = name + "_GHX";
        String gate = name + "_GHGO";
        devices.add(firstOrder(name + "_GHX", dashpot, backlash, initial.backlash(), 1.0, 1.0, 0.0, govHydro4.getDashpotTimeConstant()));
        devices.add(function(name + "_GHE", name + "_GHE", List.of(banded, backlash, dashpot), 0.0, number(initial.reference()) + stepped + "-u[1]-"
                + number(govHydro4.getPermanentDroop()) + "*u[2]-" + number(govHydro4.getTemporaryDroop()) + "*(u[2]-u[3])"));
        devices.add(firstOrder(name + "_GHF", name + "_GHF", name + "_GHE", 0.0, 1.0 / govHydro4.getGateTimeConstant(), 1.0, 0.0,
                govHydro4.getPilotTimeConstant()));
        devices.add(clamp(name + "_GHR", name + "_GHR", name + "_GHF", govHydro4.getGateClosingRate(), govHydro4.getGateOpeningRate()));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_GHG", gate, name + "_GHR", 0.0, 1.0, govHydro4.getGateMin(), govHydro4.getGateMax(),
                initial.gate(), initial.gate()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GHV", name + "_GHV", List.of(gate), initial.gate(), "u[1]")));
        devices.addAll(backlash(name + "_GHB", backlash, gate, govHydro4.getUnintentionalDeadband(), initial.backlash()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GHBS", name + "_GHBS", List.of(backlash), initial.backlash(), "u[1]")));
        List<GovHydro4Model.GatePoint> points = govHydro4.getGateCharacteristic();
        double[] openings = points.stream().mapToDouble(GovHydro4Model.GatePoint::opening).toArray();
        double[] slopes = new double[points.size() - 1];
        for (int k = 0; k < slopes.length; k++) {
            slopes[k] = govHydro4.slope(k);
        }
        String nominalHeadFlow = name + "_GHS";
        devices.add(characteristic(name + "_GHS", nominalHeadFlow, backlash, points.get(0).flow(), openings, slopes, initial.nominalHeadFlow()));
        String flow = name + "_GHQ";
        String ratio = name + "_GHH";
        String floor = number(GovHydro4Model.FLOW_FLOOR);
        devices.add(function(name + "_GHH", ratio, List.of(flow, nominalHeadFlow), initial.flow() / Math.max(initial.nominalHeadFlow(), GovHydro4Model.FLOW_FLOOR),
                "u[1]/(0.5*(u[2]+" + floor + "+ABS(u[2]-" + floor + ")))"));
        devices.add(function(name + "_GHW", name + "_GHW", List.of(ratio), 0.0, number(govHydro4.getHead()) + "-u[1]*u[1]"));
        devices.add(integrator(name + "_GHQ", flow, name + "_GHW", initial.flow(), 1.0 / govHydro4.getWaterInertiaTimeConstant()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(ratio, flow, deviation, backlash), pm0,
                number(govHydro4.getTurbineGain()) + "*u[1]*u[1]*(u[2]-" + number(govHydro4.getNoLoadFlow()) + ")-" + number(govHydro4.getTurbineDamping())
                        + "*u[3]*u[4]")));
        return devices;
    }

    /**
     * TGOV3: {@code dw} through the governor's lead-lag; the valve a plain integral of {@code (PmRef - K x - Gl) / T3} within its rates,
     * {@code Gl} its output within its limits - the integral winding up past them, as Dynawo's; the steam bowl's lag; the reheater's
     * pressure the integral of {@code (y4 - F) / T5}, stopped at its limit; its outflow {@code F} the characteristic of the pressure,
     * times the intercept valve's opening under fast valving - {@code 1 - clamp(t / TA, 0, 1) + clamp((t - TB) / (TC - TB), 0, 1)} of
     * the time since -; the crossover's lag; {@code Pm = K1 y4 + K2 F + K3 y6} ({@link TGov3Model#initialState}).
     */
    private static List<Device> tgov3(Generator generator, TGov3Model tgov3, String name, String deviation, String massPower, double pm0, String stepped,
                                      FastValving fastValving, double timeStepSeconds) {
        TGov3Model.InitialState initial = tgov3.initialState(generator, pm0);
        List<Device> devices = new ArrayList<>();
        String speed = leadLag(devices, name + "_TGX", deviation, tgov3.getLeadTimeConstant(), tgov3.getLagTimeConstant());
        String integral = name + "_TGI";
        String valve = name + "_TGG";
        devices.add(function(name + "_TGE", name + "_TGE", List.of(speed, valve), 0.0, "(" + number(initial.reference()) + stepped + "-"
                + number(tgov3.getGain()) + "*u[1]-u[2])/" + number(tgov3.getValveTimeConstant())));
        devices.add(clamp(name + "_TGR", name + "_TGR", name + "_TGE", tgov3.getValveClosingRate(), tgov3.getValveOpeningRate()));
        devices.add(EmtpVoltageSourceConverters.scoped(integrator(name + "_TGI", integral, name + "_TGR", initial.flow(), 1.0)));
        devices.add(EmtpVoltageSourceConverters.scoped(clamp(name + "_TGG", valve, integral, tgov3.getPowerMin(), tgov3.getPowerMax())));
        String steamBowl = name + "_TGY4";
        devices.add(firstOrder(name + "_TGY4", steamBowl, valve, initial.flow(), 1.0, 1.0, 0.0, tgov3.getSteamBowlTimeConstant()));
        String outflow = name + "_TGF";
        String pressure = name + "_TGPO";
        devices.add(function(name + "_TGU", name + "_TGU", List.of(steamBowl, outflow), 0.0, "u[1]-u[2]"));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_TGP", pressure, name + "_TGU", 0.0, 1.0 / tgov3.getReheaterTimeConstant(),
                TGov3Model.PRESSURE_MIN, tgov3.getPressureMax(), initial.pressure(), initial.pressure()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_TGPR", name + "_TGPR", List.of(pressure), initial.pressure(), "u[1]")));
        String admitted = pressure;
        if (fastValving != null) {
            String start = number(NumericalAids.switchingInstant(fastValving.timeSeconds(), timeStepSeconds));
            String closed = "(t-" + start + ")/" + number(tgov3.getInterceptClosingTime());
            String reopened = "(t-" + start + "-" + number(tgov3.getInterceptReopeningStart()) + ")/" + number(tgov3.getInterceptReopenedTime()
                    - tgov3.getInterceptReopeningStart());
            admitted = name + "_TGA";
            devices.add(function(name + "_TGA", admitted, List.of(pressure), initial.pressure(), "u[1]*(1-" + unitClamp(closed) + "+" + unitClamp(reopened) + ")"));
        }
        List<TGov3Model.FlowPoint> points = tgov3.getFlowCharacteristic();
        double[] pressures = points.stream().mapToDouble(TGov3Model.FlowPoint::pressure).toArray();
        double[] slopes = new double[points.size() - 1];
        for (int k = 0; k < slopes.length; k++) {
            slopes[k] = tgov3.slope(k);
        }
        devices.add(characteristic(name + "_TGF", outflow, admitted, points.get(0).flow(), pressures, slopes, initial.flow()));
        String crossover = name + "_TGY6";
        devices.add(firstOrder(name + "_TGY6", crossover, outflow, initial.flow(), 1.0, 1.0, 0.0, tgov3.getCrossoverTimeConstant()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(steamBowl, outflow, crossover), pm0,
                number(tgov3.getSteamBowlFraction()) + "*u[1]+" + number(tgov3.getReheaterFraction()) + "*u[2]+" + number(tgov3.getCrossoverFraction()) + "*u[3]")));
        return devices;
    }

    /** {@code clamp(x, 0, 1)}: {@code min(max(x, 0), 1)} of the expression {@code x}. */
    private static String unitClamp(String x) {
        String low = "(0.5*((" + x + ")+ABS(" + x + ")))";
        return "(0.5*(" + low + "+1-ABS(" + low + "-1)))";
    }

    /** {@code input} through {@code (1 + s lead) / (1 + s lag)}, at rest at 0 - passed through without a lag; the signal it gives. */
    private static String leadLag(List<Device> devices, String device, String input, double lead, double lag) {
        if (lag <= 0.0) {
            return input;
        }
        devices.add(firstOrder(device, device, input, 0.0, 1.0, 1.0, lead, lag));
        return device;
    }

    /**
     * The intentional deadband on {@code input}: {@code max(u - db, 0) + min(u + db, 0)}, its hysteresis {@code Eps} switched on by
     * ATP's limiter steps on the input's distance past the band scaled by {@link NumericalAids#DEADBAND_STEP_SCALE}; the signal it gives.
     */
    private static String deadband(List<Device> devices, String device, String input, double band, double hysteresis) {
        String b = number(band);
        String scale = number(NumericalAids.DEADBAND_STEP_SCALE);
        devices.add(function(device, device, List.of(input), 0.0, "0.5*(u[1]-" + b + "+ABS(u[1]-" + b + "))+0.5*(u[1]+" + b + "-ABS(u[1]+" + b + "))+"
                + number(hysteresis) + "*(" + EmtpSynchronousMachines.limiterStep(scale + "*(u[1]-" + b + ")") + "-"
                + EmtpSynchronousMachines.limiterStep(scale + "*(-u[1]-" + b + ")") + ")"));
        return device;
    }

    /** The backlash {@code output} on {@code input}: the integral of {@code input}'s distance past either side of the gap over {@link
     * NumericalAids#BACKLASH_TRACKING_TIME_CONSTANT}, at {@code output0}. */
    private static List<Device> backlash(String device, String output, String input, double gap, double output0) {
        String g = number(gap);
        return List.of(function(device + "U", device + "U", List.of(input, output), 0.0, "0.5*(u[1]-" + g + "-u[2]+ABS(u[1]-" + g + "-u[2]))+0.5*(u[1]+" + g
                        + "-u[2]-ABS(u[1]+" + g + "-u[2]))"),
                integrator(device, output, device + "U", output0, 1.0 / NumericalAids.BACKLASH_TRACKING_TIME_CONSTANT));
    }

    /**
     * A piecewise-linear characteristic of {@code input}: {@code y0 + s0 (u - x0) + sum over the inner breakpoints of (sk - sk-1) max(u
     * - xk, 0)}, ATP's; at {@code output0}.
     */
    private static Device characteristic(String device, String output, String input, double y0, double[] x, double[] slopes, double output0) {
        StringBuilder expression = new StringBuilder(number(y0) + "+(" + number(slopes[0]) + ")*(u[1]-(" + number(x[0]) + "))");
        for (int k = 1; k < x.length - 1; k++) {
            String excess = "u[1]-(" + number(x[k]) + ")";
            expression.append("+(").append(number(0.5 * (slopes[k] - slopes[k - 1]))).append(")*(").append(excess).append("+ABS(").append(excess).append("))");
        }
        return function(device, output, List.of(input), output0, expression.toString());
    }

    /**
     * The steam stages' lags in cascade from {@code input} at {@code rest}, a stage of time constant 0 passing its input through; the
     * returned device the power {@code massPower}, the stages' high- and low-pressure fractions summed, scoped.
     */
    private static Device stages(List<Device> devices, String device, String input, double rest, double[] timeConstants, double[] highPressure,
                                 double[] lowPressure, String powerDevice, String massPower, double pm0) {
        String stage = input;
        List<String> inputs = new ArrayList<>();
        StringBuilder power = new StringBuilder();
        for (int i = 0; i < timeConstants.length; i++) {
            if (timeConstants[i] > 0.0) {
                devices.add(firstOrder(device + (i + 1), device + (i + 1), stage, rest, 1.0, 1.0, 0.0, timeConstants[i]));
                stage = device + (i + 1);
            }
            double fraction = highPressure[i] + lowPressure[i];
            if (fraction != 0.0) {
                if (!inputs.contains(stage)) {
                    inputs.add(stage);
                }
                power.append(power.isEmpty() ? "" : "+").append(number(fraction)).append("*u[").append(inputs.indexOf(stage) + 1).append(']');
            }
        }
        return EmtpVoltageSourceConverters.scoped(function(powerDevice, massPower, inputs, pm0, power.toString()));
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
