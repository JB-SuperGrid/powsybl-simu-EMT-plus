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
import com.powsybl.simuemt.extensions.governor.GovCt2Model;
import com.powsybl.simuemt.extensions.governor.GovHydro4Model;
import com.powsybl.simuemt.extensions.governor.GovSteam1Model;
import com.powsybl.simuemt.extensions.governor.GovSteamEuModel;
import com.powsybl.simuemt.extensions.governor.GoverDtrI8Model;
import com.powsybl.simuemt.extensions.governor.GoverNordicModel;
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
                || governor instanceof GovSteam1Model || governor instanceof GovHydro4Model || governor instanceof TGov3Model
                || readsElectricalPower(governor);
    }

    /** Whether {@code governor} reads the machine's electrical power: GoverNordic, GovSteamEU and GovCT2. */
    static boolean readsElectricalPower(TurbineGovernorModel governor) {
        return governor instanceof GoverNordicModel || governor instanceof GovSteamEuModel || governor instanceof GovCt2Model;
    }

    /**
     * The governor's devices, from {@code deviation} - the machine's speed deviation - to {@code massPower}, the machine's mass's power
     * control signal; {@code electricalPower} the machine's, per unit, for one that reads it; {@code step}, if any, stepping its
     * reference; {@code fastValving}, if any, closing a TGOV3's intercept valve.
     */
    static List<Device> devices(Generator generator, TurbineGovernorModel governor, String name, String deviation, String electricalPower, String massPower,
                                double pm0, PowerReferenceStep step, FastValving fastValving, double frequencyHz, double timeStepSeconds) {
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
        } else if (governor instanceof GoverNordicModel nordic) {
            devices.addAll(goverNordic(generator, nordic, name, deviation, electricalPower, massPower, pm0, stepped));
        } else if (governor instanceof GovSteamEuModel eu) {
            devices.addAll(govSteamEu(generator, eu, name, deviation, electricalPower, massPower, pm0, stepped));
        } else if (governor instanceof GovCt2Model ct2) {
            devices.addAll(govCt2(generator, ct2, name, deviation, electricalPower, massPower, pm0, stepped, frequencyHz, timeStepSeconds));
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

    /**
     * GoverNordic, on the turbine's rating: the terminal's power through its lag; the PI's integral on {@code -dw + droop (Pe0 - Pe)};
     * the gate the integral of its rate, {@code (Kp e + I - g) / Tservo} within the gate's rate, stopped at its limits; HYGOV's water
     * column at a head of 1; {@code Pm = q (q / g)^2} back on the machine's rating.
     */
    private static List<Device> goverNordic(Generator generator, GoverNordicModel nordic, String name, String deviation, String electricalPower, String massPower,
                                            double pm0, String stepped) {
        double toTurbine = nordic.toTurbineRating(generator);
        double turbine0 = nordic.initialTurbinePower(generator, pm0);
        double pe0 = toTurbine * GovCt2Model.loadFlowElectricalPower(generator);
        List<Device> devices = new ArrayList<>();
        String power = name + "_GNPE";
        devices.add(firstOrder(name + "_GNPE", power, electricalPower, pe0, toTurbine, 1.0, 0.0, GoverNordicModel.POWER_TIME_CONSTANT));
        String error = name + "_GNE";
        devices.add(function(name + "_GNE", error, List.of(deviation, power), 0.0, "-u[1]+" + number(nordic.getDroop()) + "*(" + number(pe0) + stepped + "-u[2])"));
        String integral = name + "_GNI";
        devices.add(integrator(name + "_GNI", integral, error, turbine0, nordic.getIntegralGain()));
        String gate = name + "_GNGO";
        devices.add(function(name + "_GNRE", name + "_GNRE", List.of(error, integral, gate), 0.0, "(" + number(nordic.getProportionalGain()) + "*u[1]+u[2]-u[3])/"
                + number(GoverNordicModel.SERVO_TIME_CONSTANT)));
        devices.add(clamp(name + "_GNRR", name + "_GNRR", name + "_GNRE", -GoverNordicModel.GATE_RATE_MAX, GoverNordicModel.GATE_RATE_MAX));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_GNG", gate, name + "_GNRR", 0.0, 1.0, GoverNordicModel.GATE_MIN, GoverNordicModel.GATE_MAX,
                turbine0, turbine0));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GNV", name + "_GNV", List.of(gate), turbine0, "u[1]")));
        String flow = name + "_GNQ";
        String ratio = name + "_GNH";
        String floor = number(HygovModel.GATE_FLOOR);
        devices.add(function(name + "_GNH", ratio, List.of(flow, gate), 1.0, "u[1]/(0.5*(u[2]+" + floor + "+ABS(u[2]-" + floor + ")))"));
        devices.add(function(name + "_GNW", name + "_GNW", List.of(ratio), 0.0, "1-u[1]*u[1]"));
        devices.add(EmtpVoltageSourceConverters.scoped(integrator(name + "_GNQ", flow, name + "_GNW", turbine0, 1.0)));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(flow, ratio), pm0, "u[1]*u[2]*u[2]/" + number(toTurbine))));
        return devices;
    }

    /**
     * GovSteamEU, on the turbine's rating: the frequency's and the speed's lags and dead zones, their corrections within their limits;
     * the setpoint {@code Pe0 + correction}; the power controller's PID on the terminal's lagged power - its integral stopped at its
     * limits, its derivative a lag's difference - through the transducer's lag; the demand within {@code [0, PMax]} and under the
     * emergency control; the control and intercept valves the integrals of their rate-limited errors, stopped at their limits; the boiler
     * a plain integral, its demand carrying the load flow's losses; the high-pressure stage's lag, the reheater's integral, the
     * low-pressure stage's lag ({@link GovSteamEuModel#initialState}).
     */
    private static List<Device> govSteamEu(Generator generator, GovSteamEuModel eu, String name, String deviation, String electricalPower, String massPower,
                                           double pm0, String stepped) {
        double toTurbine = eu.toTurbineRating(generator);
        GovSteamEuModel.InitialState initial = eu.initialState(generator, pm0, GovCt2Model.loadFlowElectricalPower(generator));
        double steam0 = initial.mechanicalPower();
        List<Device> devices = new ArrayList<>();
        String speed = name + "_EUSP";
        devices.add(function(name + "_EUSP", speed, List.of(deviation), 1.0, "1+u[1]"));
        String frequency = name + "_EUF";
        devices.add(firstOrder(name + "_EUF", frequency, speed, 1.0, 1.0, 1.0, 0.0, eu.simulatedFrequencyTimeConstant()));
        String correction = name + "_EUFC";
        devices.add(function(name + "_EUFG", name + "_EUFG", List.of(frequency), 0.0, number(eu.getFrequencyCorrectionGain()) + "*(" + deadZone("1-u[1]",
                eu.getFrequencyDeadband()) + ")"));
        devices.add(clamp(name + "_EUFC", correction, name + "_EUFG", eu.getFrequencyCorrectionMin(), eu.getFrequencyCorrectionMax()));
        String setpoint = name + "_EUPS";
        devices.add(function(name + "_EUPS", setpoint, List.of(correction), initial.electricalPower(), number(initial.electricalPower()) + stepped + "+u[1]"));
        String power = name + "_EUPE";
        devices.add(firstOrder(name + "_EUPE", power, electricalPower, initial.electricalPower(), toTurbine, 1.0, 0.0, eu.getPowerTimeConstant()));
        String error = name + "_EUE";
        devices.add(function(name + "_EUE", error, List.of(setpoint, power), 0.0, "u[1]-u[2]"));
        String integral = name + "_EUIO";
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_EUI", integral, error, 0.0, 1.0 / eu.getIntegralTimeConstant(), 0.0, eu.getPowerMax(), steam0,
                steam0));
        List<String> controllerInputs = new ArrayList<>(List.of(error, integral));
        String controller = number(eu.getProportionalGain()) + "*u[1]+u[2]";
        if (eu.getDerivativeGain() > 0.0) {
            devices.add(firstOrder(name + "_EUD", name + "_EUD", error, 0.0, 1.0, 1.0, 0.0, eu.simulatedDerivativeTimeConstant()));
            controllerInputs.add(name + "_EUD");
            controller += "+" + number(eu.getDerivativeGain() / eu.simulatedDerivativeTimeConstant()) + "*(u[1]-u[3])";
        }
        devices.add(function(name + "_EUPU", name + "_EUPU", controllerInputs, steam0, controller));
        String powerControl = name + "_EUPC";
        devices.add(firstOrder(name + "_EUPC", powerControl, name + "_EUPU", steam0, 1.0, 1.0, 0.0, eu.getTransducerTimeConstant()));
        String filteredSpeed = name + "_EUW";
        devices.add(firstOrder(name + "_EUW", filteredSpeed, speed, 1.0, 1.0, 1.0, 0.0, eu.getSpeedTimeConstant()));
        devices.add(function(name + "_EUWG", name + "_EUWG", List.of(filteredSpeed), 0.0, number(eu.getSpeedCorrectionGain()) + "*(" + deadZone("1-u[1]",
                eu.getSpeedDeadband()) + ")"));
        String speedCorrection = name + "_EUWC";
        devices.add(clamp(name + "_EUWC", speedCorrection, name + "_EUWG", eu.getSpeedCorrectionMin(), eu.getSpeedCorrectionMax()));
        devices.add(function(name + "_EUEMR", name + "_EUEMR", List.of(filteredSpeed), 1.0, "1-(u[1]-" + number(eu.getEmergencySpeedLow()) + ")/"
                + number(eu.getEmergencySpeedHigh() - eu.getEmergencySpeedLow())));
        String emergency = name + "_EUEM";
        devices.add(clamp(name + "_EUEM", emergency, name + "_EUEMR", 0.0, 1.0));
        devices.add(function(name + "_EUYS", name + "_EUYS", List.of(powerControl, speedCorrection), steam0, "u[1]+u[2]"));
        devices.add(clamp(name + "_EUYO", name + "_EUYO", name + "_EUYS", 0.0, eu.getPowerMax()));
        devices.add(function(name + "_EUYE", name + "_EUYE", List.of(name + "_EUYO", emergency), steam0, min("u[1]", "u[2]")));
        String demand = name + "_EUY";
        devices.add(clamp(name + "_EUY", demand, name + "_EUYE", 0.0, eu.getPowerMax()));
        String controlValves = name + "_EUCO";
        devices.add(function(name + "_EUCE", name + "_EUCE", List.of(demand, controlValves), 0.0, "(u[1]-u[2])/" + number(eu.getControlValveTimeConstant())));
        devices.add(clamp(name + "_EUCR", name + "_EUCR", name + "_EUCE", eu.getControlValveClosingRate(), eu.getControlValveOpeningRate()));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_EUC", controlValves, name + "_EUCR", 0.0, 1.0, 0.0, eu.getControlValveMax(), steam0, steam0));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_EUCV", name + "_EUCV", List.of(controlValves), steam0, "u[1]")));
        String interceptValves = name + "_EUVO";
        devices.add(function(name + "_EUVE", name + "_EUVE", List.of(demand, interceptValves), 0.0, "(" + min("u[1]/" + number(eu.getInterceptValveTransfer()), "1")
                + "-u[2])/" + number(eu.getInterceptValveTimeConstant())));
        devices.add(clamp(name + "_EUVR", name + "_EUVR", name + "_EUVE", eu.getInterceptValveClosingRate(), eu.getInterceptValveOpeningRate()));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_EUV", interceptValves, name + "_EUVR", 0.0, 1.0, 0.0, 1.0, initial.interceptValve(),
                initial.interceptValve()));
        String boiler = name + "_EUB";
        String steam = name + "_EUST";
        devices.add(function(name + "_EUST", steam, List.of(boiler, controlValves), steam0, "u[1]*u[2]"));
        devices.add(function(name + "_EUBU", name + "_EUBU", List.of(setpoint, emergency, steam), 0.0, min("u[1]+" + number(steam0 - initial.electricalPower()),
                "u[2]") + "-u[3]"));
        devices.add(integrator(name + "_EUB", boiler, name + "_EUBU", 1.0, 1.0 / eu.getBoilerTimeConstant()));
        String highPressure = name + "_EUHP";
        devices.add(firstOrder(name + "_EUHP", highPressure, steam, steam0, 1.0, 1.0, 0.0, eu.getHighPressureTimeConstant()));
        String reheater = name + "_EURO";
        String reheaterFlow = name + "_EURF";
        devices.add(function(name + "_EURU", name + "_EURU", List.of(highPressure, reheaterFlow), 0.0, "u[1]-u[2]"));
        devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_EUR", reheater, name + "_EURU", 0.0, 1.0 / eu.getReheaterTimeConstant(), 0.0, eu.getReheaterMax(),
                initial.reheater(), initial.reheater()));
        devices.add(function(name + "_EURF", reheaterFlow, List.of(reheater, interceptValves), steam0, "u[1]*u[2]"));
        String lowPressure = name + "_EULP";
        devices.add(firstOrder(name + "_EULP", lowPressure, reheaterFlow, eu.getLowPressureFraction() * steam0, eu.getLowPressureFraction(), 1.0, 0.0,
                eu.getLowPressureTimeConstant()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(highPressure, lowPressure), pm0, "(" + number(eu.getHighPressureFraction())
                + "*u[1]+u[2])/" + number(toTurbine))));
        return devices;
    }

    /**
     * GovCT2: the terminal's power through its lag; the power controller's integral; the speed error {@code -dw + PRef + controller - R
     * droop signal} through its dead zone and limits; the PID, its derivative a lag's difference; the acceleration limiter on the speed's
     * filtered derivative; the exhaust temperature's lead-lag and lag, the load limiter's PI and its slew rate; the least of the three,
     * held between {@code ValveMin} and the frequency's power limit and its slew rate; the last value's lag; the actuator's slew rate; the
     * engine's transport delay ({@code _c_dlydis}, its history the load flow's fuel) and the turbine's lead-lag; {@code Pm} less the speed
     * sensitivity ({@link GovCt2Model#initialState}).
     */
    private static List<Device> govCt2(Generator generator, GovCt2Model ct2, String name, String deviation, String electricalPower, String massPower,
                                       double pm0, String stepped, double frequencyHz, double timeStepSeconds) {
        double pe0 = GovCt2Model.loadFlowElectricalPower(generator);
        GovCt2Model.InitialState initial = ct2.initialState(generator, pm0, pe0, frequencyHz);
        List<Device> devices = new ArrayList<>();
        String pef = name + "_GCF";
        devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_GCF", pef, electricalPower, pe0, 1.0, 1.0, 0.0, ct2.getElectricalPowerTimeConstant())));
        String valve = name + "_GCVO";
        String last = name + "_GCL";
        List<String> errorInputs = new ArrayList<>(List.of(deviation));
        StringBuilder error = new StringBuilder("-u[1]+" + number(initial.reference()) + stepped);
        if (ct2.getPowerControllerGain() != 0.0) {
            devices.add(function(name + "_GCME", name + "_GCME", List.of(pef), 0.0, number(pe0) + "-u[1]"));
            double limit = GovCt2Model.POWER_CONTROLLER_LIMIT * ct2.getDroop();
            devices.addAll(EmtpSynchronousMachines.limitedPi(name + "_GCM", name + "_GCMO", name + "_GCME", 0.0, ct2.getPowerControllerGain(), -limit, limit, 0.0, 0.0));
            errorInputs.add(name + "_GCMO");
            error.append("+u[").append(errorInputs.size()).append(']');
        }
        String selected = switch (ct2.getDroopSignal()) {
            case ISOCHRONOUS -> null;
            case ELECTRICAL_POWER -> pef;
            case VALVE_STROKE -> valve;
            case GOVERNOR_OUTPUT -> last;
        };
        if (selected != null) {
            errorInputs.add(selected);
            error.append("-").append(number(ct2.getDroop())).append("*u[").append(errorInputs.size()).append(']');
        }
        devices.add(function(name + "_GCE", name + "_GCE", errorInputs, 0.0, error.toString()));
        String banded = name + "_GCE";
        if (ct2.getSpeedDeadband() > 0.0) {
            banded = name + "_GCDZ";
            devices.add(function(name + "_GCDZ", banded, List.of(name + "_GCE"), 0.0, deadZone("u[1]", ct2.getSpeedDeadband())));
        }
        String limitedError = name + "_GCEE";
        devices.add(clamp(name + "_GCEE", limitedError, banded, ct2.getSpeedErrorMin(), ct2.getSpeedErrorMax()));
        String integral = name + "_GCI";
        devices.add(integrator(name + "_GCI", integral, limitedError, initial.valve(), ct2.getIntegralGain()));
        List<String> pidInputs = new ArrayList<>(List.of(limitedError, integral));
        String pid = number(ct2.getProportionalGain()) + "*u[1]+u[2]";
        if (ct2.getDerivativeGain() != 0.0) {
            devices.add(firstOrder(name + "_GCKD", name + "_GCKD", limitedError, 0.0, 1.0, 1.0, 0.0, ct2.getDerivativeTimeConstant()));
            pidInputs.add(name + "_GCKD");
            pid += "+" + number(ct2.getDerivativeGain() / ct2.getDerivativeTimeConstant()) + "*(u[1]-u[3])";
        }
        String governor = name + "_GCN";
        devices.add(function(name + "_GCN", governor, pidInputs, initial.valve(), pid));
        devices.add(firstOrder(name + "_GCAL", name + "_GCAL", deviation, 0.0, 1.0, 1.0, 0.0, ct2.getAccelerationTimeConstant()));
        String acceleration = name + "_GCA";
        devices.add(function(name + "_GCA", acceleration, List.of(deviation, name + "_GCAL", last), 0.0, number(ct2.getAccelerationGain() * ct2.getAccelerationGainTime())
                + "*(" + number(ct2.getAccelerationSetpoint()) + "-(u[1]-u[2])/" + number(ct2.getAccelerationTimeConstant()) + ")+u[3]"));
        String exhaust = valve;
        if (ct2.isSpeedDependentFuel() || ct2.getSpeedSensitivity() < 0.0) {
            exhaust = name + "_GCU";
            String speed = "(1+u[2])";
            devices.add(function(name + "_GCU", exhaust, List.of(valve, deviation), initial.exhaustTemperature(), "u[1]" + (ct2.isSpeedDependentFuel() ? "*" + speed : "")
                    + (ct2.getSpeedSensitivity() < 0.0 ? "*EXP(" + number(ct2.getSpeedSensitivity()) + "*LOG(" + speed + "))" : "")));
        }
        devices.add(firstOrder(name + "_GCTS", name + "_GCTS", exhaust, initial.exhaustTemperature(), 1.0, 1.0, ct2.getTemperatureLeadTimeConstant(),
                ct2.getTemperatureLagTimeConstant()));
        String temperature = name + "_GCTEX";
        devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(name + "_GCTEX", temperature, name + "_GCTS", initial.exhaustTemperature(), 1.0, 1.0, 0.0,
                ct2.getLoadLimitTimeConstant())));
        double temperatureLimit = ct2.getNoLoadFuelFlow() + ct2.getLoadLimitReference() / ct2.getTurbineGain();
        double excess0 = temperatureLimit - initial.exhaustTemperature();
        devices.add(function(name + "_GCX", name + "_GCX", List.of(temperature), excess0, number(temperatureLimit) + "-u[1]"));
        devices.add(integrator(name + "_GCLI", name + "_GCLI", name + "_GCX", GovCt2Model.LOAD_LIMITER_INTEGRAL0, ct2.getLoadLimitIntegralGain()));
        double loadRaw0 = ct2.getLoadLimitProportionalGain() * excess0 + GovCt2Model.LOAD_LIMITER_INTEGRAL0;
        devices.add(function(name + "_GCLR", name + "_GCLR", List.of(name + "_GCX", name + "_GCLI"), loadRaw0, number(ct2.getLoadLimitProportionalGain())
                + "*u[1]+u[2]"));
        String loadLimit = slewRate(devices, name + "_GCLS", name + "_GCLR", loadRaw0, ct2.getRateLimitTimeConstant(), ct2.getLoadLimitRateDown(),
                ct2.getLoadLimitRateUp(), initial.loadLimit());
        String lowValue = name + "_GCR";
        devices.add(function(name + "_GCR", lowValue, List.of(loadLimit, acceleration, governor), initial.valve(), min(min(min("u[1]",
                number(GovCt2Model.LOAD_LIMIT_MAX)), "u[2]"), "u[3]")));
        List<GovCt2Model.PowerLimitPoint> points = ct2.powerLimitTable();
        double[] frequencies = points.stream().mapToDouble(GovCt2Model.PowerLimitPoint::frequencyHz).toArray();
        double[] slopes = new double[points.size() - 1];
        for (int k = 0; k < slopes.length; k++) {
            slopes[k] = ct2.powerLimitSlope(k);
        }
        devices.add(function(name + "_GCHZ", name + "_GCHZ", List.of(deviation), frequencyHz, number(frequencyHz) + "*(1+u[1])"));
        devices.add(characteristic(name + "_GCP", name + "_GCP", name + "_GCHZ", points.get(0).power(), frequencies, slopes, ct2.powerLimit(frequencyHz)));
        double powerLimit0 = (initial.valveLimit() - ct2.getNoLoadFuelFlow()) * ct2.getTurbineGain();
        String powerLimit = slewRate(devices, name + "_GCPS", name + "_GCP", ct2.powerLimit(frequencyHz), ct2.getRateLimitTimeConstant(), -ct2.getPowerLimitRate(),
                ct2.getPowerLimitRate(), powerLimit0);
        String command = name + "_GCC";
        devices.add(function(name + "_GCC", command, List.of(lowValue, powerLimit), initial.valve(), min(max("u[1]", number(ct2.getValveMin())),
                number(ct2.getNoLoadFuelFlow()) + "+u[2]/" + number(ct2.getTurbineGain()))));
        devices.add(firstOrder(name + "_GCL", last, command, initial.valve(), 1.0, 1.0, 0.0, Math.max(ct2.getLastValueTimeConstant(),
                NumericalAids.LAST_VALUE_MIN_TIME_CONSTANT)));
        String actuator = slewRate(devices, name + "_GCV", command, initial.valve(), ct2.getActuatorTimeConstant(), ct2.getValveClosingRate(), ct2.getValveOpeningRate(),
                initial.valve());
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_GCVO", valve, List.of(actuator), initial.valve(), "u[1]")));
        String fuel = name + "_GCW";
        devices.add(function(name + "_GCW", fuel, List.of(valve, deviation), initial.turbinePower(), number(ct2.getTurbineGain()) + "*(u[1]"
                + (ct2.isSpeedDependentFuel() ? "*(1+u[2])" : "+0*u[2]") + "-" + number(ct2.getNoLoadFuelFlow()) + ")"));
        if (ct2.getEngineDelay() > 0.0) {
            // EMTP's discrete delay, its output its input's that many steps before, the load flow's fuel until then.
            long steps = Math.round(ct2.getEngineDelay() / timeStepSeconds);
            devices.add(new Device("_c_dlydis", List.of("_c_dlydis;" + name + "_GCWD;2;2;" + name + "_GCWD," + fuel + ",", number(initial.turbinePower()) + ","
                    + steps + ",S1,")));
            fuel = name + "_GCWD";
        }
        String turbine = name + "_GCB";
        devices.add(firstOrder(name + "_GCB", turbine, fuel, initial.turbinePower(), 1.0, 1.0, ct2.getTurbineLeadTimeConstant(), ct2.getTurbineLagTimeConstant()));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_PM", massPower, List.of(turbine, deviation), pm0, "u[1]-"
                + number(Math.max(ct2.getSpeedSensitivity(), 0.0)) + "*(1+u[2])")));
        return devices;
    }

    /** A lag of {@code timeConstant} whose rate stays within {@code [falling, rising]}: the integral of its clamped rate, at {@code initial},
     * its input at {@code input0}; the signal it gives. */
    private static String slewRate(List<Device> devices, String device, String input, double input0, double timeConstant, double falling, double rising,
                                   double initial) {
        devices.add(function(device + "D", device + "D", List.of(input, device), (input0 - initial) / timeConstant, "(u[1]-u[2])/" + number(timeConstant)));
        devices.add(clamp(device + "R", device + "R", device + "D", falling, rising));
        devices.add(integrator(device, device, device + "R", initial, 1.0));
        return device;
    }

    /** Modelica's {@code DeadZone} of half-width {@code band} on the expression {@code x}: {@code max(x - band, 0) + min(x + band, 0)}. */
    private static String deadZone(String x, double band) {
        String b = number(band);
        return max("(" + x + ")-" + b, "0") + "+" + min("(" + x + ")+" + b, "0");
    }

    private static String min(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")-ABS((" + a + ")-(" + b + ")))";
    }

    private static String max(String a, String b) {
        return "0.5*((" + a + ")+(" + b + ")+ABS((" + a + ")-(" + b + ")))";
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
