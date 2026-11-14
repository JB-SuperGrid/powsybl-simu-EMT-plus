/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.extensions.GeneratorFortescue;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.circuit.MachineSequenceImpedances;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.excitation.SimplifiedAvrModel;
import com.powsybl.simuemt.extensions.excitation.St1AModel;
import com.powsybl.simuemt.extensions.excitation.limiter.DiscontinuousExcitationControlModel;
import com.powsybl.simuemt.extensions.excitation.limiter.OverexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.PowerFactorVarControllerModel;
import com.powsybl.simuemt.extensions.excitation.limiter.StatorCurrentLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.UnderexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.VoltsPerHertzLimiterModel;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.ClassicalDefinitions.RotorCircuit;
import com.powsybl.simuemt.extensions.machine.ClassicalMachineEquivalent;
import com.powsybl.simuemt.extensions.machine.ClassicalMachineModel;
import com.powsybl.simuemt.extensions.machine.MachineSaturation;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.extensions.pss.Pss1AModel;
import com.powsybl.simuemt.extensions.pss.PssModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;

/**
 * A synchronous machine - upstream's {@link SubtransientMachineModel}, or its {@link ClassicalMachineModel} as the subtransient
 * equivalent ATP simulates it as ({@link ClassicalMachineEquivalent}) - as EMTP's synchronous machine ({@code _SM}): one device per
 * stator phase, from the bus phase to its star point, solidly earthed inside the machine (connection 0); and the machine's data, in
 * EMTP's per-unit form (data type 3) on its own base - the bus's nominal voltage, its rated power and its air-gap-line field
 * current.
 *
 * <p>Each axis's rotor is the circuit the machine's data describe under the exact definitions ({@link RotorCircuit#fromExact}), as
 * ATP's Type-59 internal cards have it: a field and a damper winding on the d axis, two damper windings on the q axis, every mutual
 * reactance on an axis its magnetizing reactance {@code X - Xl}, each winding's self-reactance that plus its own leakage, and its
 * resistance per unit of the base impedance - the circuit's per-second resistance over the base angular frequency. EMTP given the
 * open-circuit data to convert itself takes them under the classical definitions: on upstream's two-bus machine, a field leakage of
 * 0.1475 pu where the exact one is 0.1507 (its machine report, 2026-11-13).
 *
 * <p>The stator: its resistance, its leakage reactance, and as its zero-sequence reactance the reactance upstream's backends write
 * each stator phase behind ({@link MachineSequenceImpedances#statorBranchReactance}). The shaft: one mass, the machine's inertia
 * constant {@code H}, and its damping {@code D} - a power per unit of speed deviation - as the speed-deviation damping {@code DSR = D
 * S / wm^2}, a torque per mechanical rad/s, as ATP's mass card has it. EMTP starts the machine from its own steady state, the
 * machine there a source at the load flow's terminal voltage ({@link MachineInitialState}), displaced by its bus's vector group; its
 * mechanical power is then held where that steady state put it.
 *
 * <p>Its field voltage {@code vf} is held too, as upstream's backends hold it: a constant ({@code _c_cst}) at the load flow's {@link
 * MachineInitialState#efd()} drives the machine's field-voltage control, per unit. Left uncontrolled, EMTP holds {@code Ef = w Mdf
 * vf / rf} instead, the field voltage falling as the machine speeds up: shorted at the far end of its line, the two-bus machine's
 * voltage came out 0.25% low 0.39 s later, 1.76 rad ahead (2026-11-13), where Park's equations with {@code vf/w} for the field
 * voltage put it within 4e-5. The control signal goes on each phase's header right after its stator pin - the star point has no pin
 * of its own when earthed inside -, named after the machine, {@code GEN_Vf}.
 *
 * <p>An enabled {@link SimplifiedAvrModel} drives that control instead, as ATP's TACS has it: the machine observes its {@code vd}
 * and {@code vq} per unit, on the header after the control signal ({@code GEN_vd}, {@code GEN_vq}); a function ({@code _c_fu}) the
 * voltage error {@code Vref - sqrt(vd^2 + vq^2)}, {@code Vref = Vt0 + Efd0 / Ka} - the reference that puts the regulator at
 * equilibrium on the load flow, in place of the model's own setpoint, as upstream's backends compute it -; and a transfer function
 * with limits ({@code _c_fslim}) {@code Ka / (1 + sTa)} from it to {@code GEN_Vf}, starting at {@code Efd0}, held within the model's
 * minimum and maximum output - its state with it: EMTP's limits are dynamic, as a limited TACS s-block's.
 *
 * <p>An enabled {@link St1AModel} drives it as ATP's TACS has it, from the same observed signals and the field current per unit of
 * the air-gap line's ({@code if}, between {@code vd} and {@code vq} on the header) if its ceiling or its field-current limiter reads
 * it: the terminal voltage through its transducer {@code 1 / (1 + sTr)}; the error {@code Vref + Vs - VC - VF} held within its input
 * limits - static -; its two lead-lags, a stage of {@code Tb = 0} left out; its regulator {@code Ka / (1 + sTa)} within its output
 * limits - dynamic, a static gain when {@code Ta = 0} -; its field-current limiter {@code Klr max(Ifd - Ilr, 0)} subtracted; the
 * field voltage held between {@code Vt VRMIN} and {@code Vt VRMAX - Kc Ifd} on the unfiltered terminal voltage; and its rate feedback
 * {@code Kf s / (1 + sTf)} off the field voltage, left out when {@code Kf = 0}. Every block at rest on the load flow, {@code Vref =
 * Vt0 + Efd0 / Ka} as for the simplified regulator.
 *
 * <p>An enabled {@link Pss1AModel} with either adds its output to that error, as ATP's TACS has it: the machine observes its rotor's speed
 * per unit ({@code Omega_1}, its one mass's, on the header before {@code vd} and {@code vq}); a function its deviation {@code w - 1};
 * three transfer functions ({@code _c_fs}) the washout {@code Ks sTw / (1 + sTw)} and the two lead-lags {@code (1 + sT1) / (1 + sT2)},
 * {@code (1 + sT3) / (1 + sT4)}, each at rest at 0; and a limiter ({@code _c_lim}) holding the output within its limit - static, as
 * ATP's clamp after the second lead-lag. A stabilizer without an enabled regulator drives nothing, as in upstream's backends.
 *
 * <p>An enabled {@link Tgov1Model} drives the machine's mechanical power instead of holding it, per unit, through its one mass's power
 * control ({@code Pm_1}, 4 on the mass's row; its signal {@code GEN_Pm_1} before the field voltage's) - not the machine's total
 * power control, which EMTP reads a hundred times too large per unit, whatever the machine's rating (2026-11-14): from the same
 * speed deviation, its valve {@code 1 / (1 + sT1)} on {@code Pm0 - dw / R} held within its limits - its state with it, as TGOV1's
 * non-windup limit -, its turbine {@code (1 + sT2) / (1 + sT3)}, and {@code Pm = turbine - Dt dw}, every state at {@code Pm0}, the
 * load flow's mechanical power ({@link MachineInitialState#mechanicalPower()}), as ATP's TACS has it.
 *
 * <p>Refused, until written: zero-sequence data ({@code GeneratorFortescue}), saturation, and any other enabled excitation system,
 * stabilizer, limiter or turbine-governor - one disabled holds what it would have regulated, as in upstream's backends.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpSynchronousMachines {

    /** EMTP's data type for a machine given as its windings' per-unit circuit. */
    private static final int PER_UNIT_DATA = 3;
    /** A field and one damper winding on the d axis; two damper windings on the q axis. */
    private static final int D_WINDINGS = 2;
    private static final int Q_WINDINGS = 2;
    /** EMTPWorks' defaults: damping factor, rotor speed tolerance, its iterations, no maximum precision, voltage tolerance. */
    private static final String PRECISION = "PR=100 1e-06 300 0 1e-06,";
    /** Observe and scope requests EMTPWorks' form writes, every one off but for a regulator's. */
    private static final int OBSERVABLES = 31;
    /** The positions of {@code vd} and {@code vq} among them; 2 asks for the signal to be observed. */
    private static final int OBSERVE_VD = 10;
    private static final int OBSERVE_IF = 13;
    private static final int OBSERVE_VQ = 16;
    private static final String OBSERVED = "2";

    /** The observed speed of the machine's one mass, per unit: EMTP finds it by its {@code Omega_1}. */
    private static final String ROTOR_SPEED = "Omega_1";

    /** The machine's observed field current, per unit of the air-gap line's: EMTP finds it by its {@code if}. */
    private static final String FIELD_CURRENT = "if";

    /** The controlled power of the machine's one mass, per unit: EMTP finds it by its {@code Pm_1}. */
    private static final String MASS_POWER = "Pm_1";

    /** A control device: its part and its lines. */
    record Device(String part, List<String> lines) {
    }

    private EmtpSynchronousMachines() {
    }

    /** Whether {@code generator} is a machine this writes. */
    static boolean isMachine(Generator generator) {
        GeneratorModel model = generator.getExtension(GeneratorModel.class);
        return model instanceof SubtransientMachineModel || model instanceof ClassicalMachineModel;
    }

    /** Why {@code generator}, a machine, cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(Generator generator) {
        List<String> reasons = new ArrayList<>();
        if (Double.isNaN(generator.getRatedS()) || generator.getRatedS() <= 0.0) {
            reasons.add("it has no rated power");
        }
        if (generator.getExtension(GeneratorFortescue.class) != null) {
            reasons.add("its zero-sequence data are not written yet");
        }
        SubtransientMachineModel machine = subtransient(generator);
        if (MachineSaturation.of(machine) != null) {
            reasons.add("its saturation is not written yet");
        }
        try {
            rotor(machine, true);
            rotor(machine, false);
        } catch (IllegalArgumentException e) {
            reasons.add(e.getMessage());
        }
        for (Object extension : generator.getExtensions()) {
            if (isEnabled(extension) && !(extension instanceof SimplifiedAvrModel) && !(extension instanceof St1AModel) && !(extension instanceof Pss1AModel)
                    && !(extension instanceof Tgov1Model)) {
                reasons.add("its " + extension.getClass().getSimpleName() + " is not written yet");
            }
        }
        return reasons;
    }

    /** Whether {@code control} is an excitation system, a stabilizer, a governor or a limiter, enabled. */
    private static boolean isEnabled(Object control) {
        return control instanceof ExcitationSystemModel c && c.isEnabled()
                || control instanceof PssModel p && p.isEnabled()
                || control instanceof TurbineGovernorModel g && g.isEnabled()
                || control instanceof OverexcitationLimiterModel o && o.isEnabled()
                || control instanceof UnderexcitationLimiterModel u && u.isEnabled()
                || control instanceof StatorCurrentLimiterModel s && s.isEnabled()
                || control instanceof VoltsPerHertzLimiterModel v && v.isEnabled()
                || control instanceof PowerFactorVarControllerModel f && f.isEnabled()
                || control instanceof DiscontinuousExcitationControlModel d && d.isEnabled();
    }

    /**
     * The machine's device - its three phases' header lines, then its data - its stator on bus {@code bus}'s phase nodes {@code
     * phaseNodes}, its terminal voltage displaced by {@code offsetDegrees}, the bus's vector group's.
     */
    static List<String> lines(Generator generator, String name, String[] phaseNodes, double offsetDegrees, EmtpSimulationSettings settings) {
        SubtransientMachineModel machine = subtransient(generator);
        Bus bus = generator.getTerminal().getBusBreakerView().getBus();
        double nominalKv = bus.getVoltageLevel().getNominalV();
        double ratedMva = generator.getRatedS();
        double frequency = settings.nominalFrequencyHz();
        double omega = 2.0 * Math.PI * frequency;
        double terminalKv = nominalKv * MachineInitialState.subtransient(generator, machine, 0.0).terminalVoltage();
        double angle = MachineInitialState.terminalAngleDegrees(generator, offsetDegrees);
        RotorCircuit d = rotor(machine, true);
        RotorCircuit q = rotor(machine, false);
        double mechanicalSpeed = omega * 2.0 / machine.getNumberOfPoles();
        double speedDeviationDamping = machine.getDamping() * ratedMva * 1.0e6 / (mechanicalSpeed * mechanicalSpeed);

        boolean regulated = regulator(generator) != null;
        boolean stabilized = stabilizer(generator) != null;
        boolean governed = governor(generator) != null;
        List<String> controls = new ArrayList<>();
        if (governed) {
            controls.add(signal(name, MASS_POWER));
        }
        controls.add(fieldVoltageSignal(name));
        List<String> observed = new ArrayList<>();
        if (stabilized || governed) {
            observed.add(signal(name, ROTOR_SPEED));
        }
        // In the order of the observe requests: vd, the field current, vq.
        if (regulated) {
            observed.add(signal(name, "vd"));
            if (readsFieldCurrent(generator)) {
                observed.add(signal(name, FIELD_CURRENT));
            }
            observed.add(signal(name, "vq"));
        }
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            // Pin k on the bus phase, the control signals - the mechanical power's before the field voltage's -, the observed signals - the
            // masses' first -, a blank pin.
            int pins = 2 + controls.size() + observed.size();
            List<String> pinSignals = new ArrayList<>(controls);
            pinSignals.addAll(observed);
            lines.add("_SM;" + name + "abc".charAt(p) + ";" + (pins + 2) + ";" + pins + ";" + phaseNodes[p] + ","
                    + pinSignals.stream().map(signal -> signal + ",").collect(java.util.stream.Collectors.joining()) + ",");
        }
        lines.add("V=" + number(terminalKv) + " " + number(terminalKv) + " " + number(terminalKv) + ",1kVRMSLL,");
        lines.add("P=" + number(angle) + " " + number(Math.IEEEremainder(angle - 120.0, 360.0)) + " "
                + number(Math.IEEEremainder(angle + 120.0, 360.0)) + ",1,");
        // Frequency, poles, star earthed (0), not at standstill, operating at the rated frequency.
        lines.add("f=" + number(frequency) + " " + machine.getNumberOfPoles() + " 0 0 " + number(frequency) + ",");
        // Rated voltage and power, air-gap-line field current; no permanent magnet.
        lines.add("R=" + number(nominalKv) + " " + number(ratedMva) + " " + number(machine.getFieldCurrentAirGapLine()) + " 0 0 0,");
        // One machine on its bus, no saturation.
        lines.add("S=0 1 1 0 0 0 0 0,");
        lines.add("Ra=" + number(machine.getStatorResistance()) + " " + number(machine.getStatorLeakageReactance()) + " "
                + number(MachineSequenceImpedances.statorBranchReactance(machine)));
        double xad = d.magnetizingReactance();
        lines.add("ds=" + PER_UNIT_DATA + " " + D_WINDINGS + " " + number(machine.getDirectSynchronousReactance()));
        lines.add("Rf=" + number(d.fieldResistance() / omega) + " " + number(xad + d.fieldReactance()) + " " + number(xad));
        lines.add("RD1=" + number(d.damperResistance() / omega) + " " + number(xad + d.damperReactance()) + " " + number(xad) + " " + number(xad));
        double xaq = q.magnetizingReactance();
        lines.add("qs=" + PER_UNIT_DATA + " " + Q_WINDINGS + " " + number(machine.getQuadratureSynchronousReactance()));
        lines.add("RQ1=" + number(q.fieldResistance() / omega) + " " + number(xaq + q.fieldReactance()) + " " + number(xaq));
        lines.add("RQ2=" + number(q.damperResistance() / omega) + " " + number(xaq + q.damperReactance()) + " " + number(xaq) + " " + number(xaq));
        // A shaft of its own, one mass - the rotor's, no exciter's -: all the external torque, H, DSR; its speed observed for a
        // stabilizer or a governor (1), its power controlled by a governor (4).
        lines.add("MS=0 1 1 0,");
        lines.add("100 " + number(machine.getInertiaConstant()) + " " + number(speedDeviationDamping) + " 0 0 0");
        lines.add("0 " + (stabilized || governed ? 1 : 0) + " 0 0 " + (governed ? 4 : 0));
        // H in place of the moment of inertia; SI units for the scopes, the masses' observed signals per unit.
        lines.add("MSu=1 0 1,");
        lines.add(PRECISION);
        // Its controls per unit: field voltage, torque, power, field voltage vf, speed - vf held by its constant or driven by its
        // regulator; the mechanical power its mass's.
        lines.add("CTRLu=1,");
        lines.add("CTRL=0 0 0 1 0,");
        // The scopes in SI, the observed signals per unit.
        lines.add("OBSu=0 1,");
        List<String> observe = new ArrayList<>(Collections.nCopies(OBSERVABLES, "0"));
        if (regulated) {
            observe.set(OBSERVE_VD, OBSERVED);
            if (readsFieldCurrent(generator)) {
                observe.set(OBSERVE_IF, OBSERVED);
            }
            observe.set(OBSERVE_VQ, OBSERVED);
        }
        lines.add("OBS=" + String.join(" ", observe) + ",");
        return lines;
    }

    /**
     * The control devices that drive the machine: its speed's deviation, if a stabilizer or a governor reads it; its governor's; and
     * the constant that holds its field voltage at the load flow's, per unit, from the start (step mode), or its regulator's voltage
     * error and lag.
     */
    static List<Device> controls(Generator generator, String name) {
        MachineInitialState initial = MachineInitialState.subtransient(generator, subtransient(generator), 0.0);
        double efd = initial.efd();
        Pss1AModel pss = stabilizer(generator);
        Tgov1Model tgov1 = governor(generator);
        String deviation = signal(name, "DW");
        List<Device> devices = new ArrayList<>();
        if (pss != null || tgov1 != null) {
            devices.add(new Device("_c_fu", List.of("_c_fu;" + name + "_DW;2;2;" + deviation + "," + signal(name, ROTOR_SPEED) + ",", "0,S1,", "u[1]-1")));
        }
        if (tgov1 != null) {
            double pm0 = initial.mechanicalPower();
            String demand = signal(name, "GOV");
            String valve = signal(name, "VALVE");
            String turbine = signal(name, "TURB");
            devices.add(new Device("_c_fu", List.of("_c_fu;" + name + "_GOV;2;2;" + demand + "," + deviation + ",", number(pm0) + ",S1,",
                    number(pm0) + "-u[1]/" + number(tgov1.getDroop()))));
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_VALVE;4;4;" + valve + "," + demand + ",0,0,",
                    number(pm0) + "," + number(tgov1.getValveMax()) + "," + number(tgov1.getValveMin()) + ",1,1,2,1,S1,",
                    "1", ";", "1 " + number(tgov1.getGovernorTimeConstant()))));
            devices.add(firstOrder(name + "_TURB", turbine, valve, pm0, 1.0, 1.0, tgov1.getTurbineLeadTimeConstant(), tgov1.getTurbineLagTimeConstant()));
            devices.add(new Device("_c_fu", List.of("_c_fu;" + name + "_PM;3;3;" + signal(name, MASS_POWER) + "," + turbine + "," + deviation + ",",
                    number(pm0) + ",S1,", "u[1]-" + number(tgov1.getTurbineDamping()) + "*u[2]")));
        }
        ExcitationSystemModel excitation = regulator(generator);
        if (excitation == null) {
            devices.add(new Device("_c_cst", List.of("_c_cst;" + name + "_VF;1;1;" + fieldVoltageSignal(name) + ",", number(efd) + ",S1,")));
            return devices;
        }
        if (pss != null) {
            String washout = signal(name, "XW");
            String firstLeadLag = signal(name, "Y1");
            String unlimited = signal(name, "VSP");
            devices.add(firstOrder(name + "_XW", washout, deviation, 0.0, pss.getGain(), 0.0, pss.getWashoutTimeConstant(), pss.getWashoutTimeConstant()));
            devices.add(firstOrder(name + "_Y1", firstLeadLag, washout, 0.0, 1.0, 1.0, pss.getLeadTimeConstant(), pss.getLagTimeConstant()));
            devices.add(firstOrder(name + "_VSP", unlimited, firstLeadLag, 0.0, 1.0, 1.0, pss.getLeadTimeConstant2(), pss.getLagTimeConstant2()));
            devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_VSL;2;2;" + signal(name, "VS") + "," + unlimited + ",",
                    number(pss.getOutputLimit()) + "," + number(-pss.getOutputLimit()) + ",1,S1,")));
        }
        if (excitation instanceof St1AModel st1a) {
            devices.addAll(st1a(st1a, name, initial, pss != null));
            return devices;
        }
        SimplifiedAvrModel avr = (SimplifiedAvrModel) excitation;
        double ka = avr.getGain();
        String error = signal(name, "VERR");
        devices.addAll(List.of(
                // Its output, its input, no reset; starting at Efd0, within the model's limits; Ka times 1 over 1 + Ta s, the
                // coefficients in increasing powers of s.
                new Device("_c_fslim", List.of("_c_fslim;" + name + "_AVR;4;4;" + fieldVoltageSignal(name) + "," + error + ",0,0,",
                        number(efd) + "," + number(avr.getMaxOutput()) + "," + number(avr.getMinOutput()) + ",1,1,2," + number(ka) + ",S1,",
                        "1", ";", "1 " + number(avr.getTimeConstant()))),
                new Device("_c_fu", List.of("_c_fu;" + name + "_VERR;" + (pss == null ? "3;3;" : "4;4;") + error + "," + signal(name, "vd") + ","
                        + signal(name, "vq") + "," + (pss == null ? "" : signal(name, "VS") + ","),
                        number(efd / ka) + ",S1,",
                        number(initial.terminalVoltage() + efd / ka) + (pss == null ? "" : "+u[3]") + "-SQRT(u[1]*u[1]+u[2]*u[2])"))));
        return devices;
    }

    /** ST1A's devices, from the observed {@code vd}, {@code vq} and field current to the field voltage's control signal; the
     * stabilizer's output {@code VS} added to its error if {@code stabilized}. */
    private static List<Device> st1a(St1AModel st1a, String name, MachineInitialState initial, boolean stabilized) {
        double efd0 = initial.efd();
        double vt0 = initial.terminalVoltage();
        double ka = st1a.getRegulatorGain();
        double vi0 = efd0 / ka;
        String vt = signal(name, "VT");
        String fieldCurrent = signal(name, FIELD_CURRENT);
        List<Device> devices = new ArrayList<>();
        devices.add(function(name + "_VT", vt, List.of(signal(name, "vd"), signal(name, "vq")), vt0, "SQRT(u[1]*u[1]+u[2]*u[2])"));
        String measured = vt;
        if (st1a.getTransducerTimeConstant() > 0.0) {
            measured = signal(name, "VC");
            devices.add(firstOrder(name + "_VC", measured, vt, vt0, 1.0, 1.0, 0.0, st1a.getTransducerTimeConstant()));
        }
        boolean rateFeedback = st1a.getRateFeedbackGain() != 0.0 && st1a.getRateFeedbackTimeConstant() > 0.0;
        List<String> errorInputs = new ArrayList<>(List.of(measured));
        StringBuilder error = new StringBuilder(number(vt0 + vi0) + "-u[1]");
        if (stabilized) {
            errorInputs.add(signal(name, "VS"));
            error.append("+u[").append(errorInputs.size()).append("]");
        }
        if (rateFeedback) {
            String feedback = signal(name, "VRF");
            devices.add(firstOrder(name + "_VRF", feedback, fieldVoltageSignal(name), 0.0, 1.0, 0.0, st1a.getRateFeedbackGain(),
                    st1a.getRateFeedbackTimeConstant()));
            errorInputs.add(feedback);
            error.append("-u[").append(errorInputs.size()).append("]");
        }
        String errorSignal = signal(name, "VERR");
        devices.add(function(name + "_VERR", errorSignal, errorInputs, vi0, error.toString()));
        String clamped = signal(name, "VI");
        devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_VI;2;2;" + clamped + "," + errorSignal + ",",
                number(st1a.getInputMax()) + "," + number(st1a.getInputMin()) + ",1,S1,")));
        String shaped = clamped;
        if (st1a.getLagTimeConstant() > 0.0) {
            String stage = signal(name, "LL1");
            devices.add(firstOrder(name + "_LL1", stage, shaped, vi0, 1.0, 1.0, st1a.getLeadTimeConstant(), st1a.getLagTimeConstant()));
            shaped = stage;
        }
        if (st1a.getLagTimeConstant2() > 0.0) {
            String stage = signal(name, "LL2");
            devices.add(firstOrder(name + "_LL2", stage, shaped, vi0, 1.0, 1.0, st1a.getLeadTimeConstant2(), st1a.getLagTimeConstant2()));
            shaped = stage;
        }
        String regulator = signal(name, "VA");
        if (st1a.getRegulatorTimeConstant() > 0.0) {
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_VA;4;4;" + regulator + "," + shaped + ",0,0,",
                    number(efd0) + "," + number(st1a.getRegulatorOutputMax()) + "," + number(st1a.getRegulatorOutputMin()) + ",1,1,2," + number(ka) + ",S1,",
                    "1", ";", "1 " + number(st1a.getRegulatorTimeConstant()))));
        } else {
            devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_VA;2;2;" + regulator + "," + shaped + ",",
                    number(st1a.getRegulatorOutputMax()) + "," + number(st1a.getRegulatorOutputMin()) + "," + number(ka) + ",S1,")));
        }
        String limited = regulator;
        if (st1a.getFieldCurrentLimiterGain() != 0.0) {
            limited = signal(name, "FC");
            String excess = "(u[2]-" + number(st1a.getFieldCurrentLimit()) + ")";
            devices.add(function(name + "_FC", limited, List.of(regulator, fieldCurrent), efd0,
                    "u[1]-" + number(0.5 * st1a.getFieldCurrentLimiterGain()) + "*(" + excess + "+ABS" + excess + ")"));
        }
        // The field voltage between its floor Vt VRMIN and its ceiling Vt VRMAX - Kc Ifd: max(x, m) = (x + m + |x - m|) / 2, then min.
        String ceiling = signal(name, "CEIL");
        double ifd0 = efd0;
        boolean loaded = st1a.getRectifierLoadingFactor() != 0.0;
        devices.add(function(name + "_CEIL", ceiling, loaded ? List.of(vt, fieldCurrent) : List.of(vt),
                st1a.getFieldVoltageMax() * vt0 - st1a.getRectifierLoadingFactor() * ifd0,
                "(" + number(st1a.getFieldVoltageMax()) + ")*u[1]" + (loaded ? "-(" + number(st1a.getRectifierLoadingFactor()) + ")*u[2]" : "")));
        String floored = signal(name, "EFL");
        String floor = "(" + number(st1a.getFieldVoltageMin()) + ")*u[2]";
        devices.add(function(name + "_EFL", floored, List.of(limited, vt), efd0, "0.5*(u[1]+" + floor + "+ABS(u[1]-" + floor + "))"));
        devices.add(function(name + "_EF", fieldVoltageSignal(name), List.of(floored, ceiling), efd0, "0.5*(u[1]+u[2]-ABS(u[1]-u[2]))"));
        return devices;
    }

    /** A function ({@code _c_fu}) of {@code inputs}, {@code u[1]} on, its output {@code output} at {@code history} at the start. */
    private static Device function(String device, String output, List<String> inputs, double history, String expression) {
        return new Device("_c_fu", List.of("_c_fu;" + device + ";" + (inputs.size() + 1) + ";" + (inputs.size() + 1) + ";" + output + ","
                + String.join(",", inputs) + ",", number(history) + ",S1,", expression));
    }

    /** Whether the machine's excitation system reads its field current: an ST1A's ceiling or field-current limiter. */
    private static boolean readsFieldCurrent(Generator generator) {
        return regulator(generator) instanceof St1AModel st1a && (st1a.getRectifierLoadingFactor() != 0.0 || st1a.getFieldCurrentLimiterGain() != 0.0);
    }

    /** A first-order transfer function {@code gain (n0 + n1 s) / (1 + d1 s)} from {@code input} to {@code output}, at rest at {@code
     * rest}. */
    private static Device firstOrder(String device, String output, String input, double rest, double gain, double n0, double n1, double d1) {
        return new Device("_c_fs", List.of("_c_fs;" + device + ";2;2;" + output + "," + input + ",", number(rest) + ",1,2,2," + number(gain) + ",S1,",
                number(n0) + " " + number(n1), ";", "1 " + number(d1)));
    }

    /** The machine's enabled TGOV1, or {@code null}. */
    private static Tgov1Model governor(Generator generator) {
        return generator.getExtension(TurbineGovernorModel.class) instanceof Tgov1Model tgov1 && tgov1.isEnabled() ? tgov1 : null;
    }

    /** The machine's enabled PSS1A, if its regulator is written to take its output, or {@code null}. */
    private static Pss1AModel stabilizer(Generator generator) {
        return regulator(generator) != null && generator.getExtension(PssModel.class) instanceof Pss1AModel pss && pss.isEnabled() ? pss : null;
    }

    /** The machine's enabled excitation system this writes - a simplified regulator or an ST1A -, or {@code null}. */
    private static ExcitationSystemModel regulator(Generator generator) {
        ExcitationSystemModel excitation = generator.getExtension(ExcitationSystemModel.class);
        return (excitation instanceof SimplifiedAvrModel || excitation instanceof St1AModel) && excitation.isEnabled() ? excitation : null;
    }

    /** The machine's field-voltage control signal: EMTP finds it by its {@code Vf}. */
    private static String fieldVoltageSignal(String name) {
        return signal(name, "Vf");
    }

    private static String signal(String name, String quantity) {
        return name + "_" + quantity;
    }

    private static SubtransientMachineModel subtransient(Generator generator) {
        GeneratorModel model = generator.getExtension(GeneratorModel.class);
        return model instanceof ClassicalMachineModel classical ? ClassicalMachineEquivalent.of(classical) : (SubtransientMachineModel) model;
    }

    /** The d axis's rotor - field, then damper - or the q axis's, the two dampers in the same roles. */
    private static RotorCircuit rotor(SubtransientMachineModel machine, boolean directAxis) {
        double xl = machine.getStatorLeakageReactance();
        return directAxis
                ? RotorCircuit.fromExact(machine.getDirectSynchronousReactance(), xl, machine.getDirectTransientReactance(),
                        machine.getDirectSubtransientReactance(), machine.getOpenCircuitTransientTimeConstant(),
                        machine.getDirectOpenCircuitSubtransientTimeConstant(), "d")
                : RotorCircuit.fromExact(machine.getQuadratureSynchronousReactance(), xl, machine.getQuadratureTransientReactance(),
                        machine.getQuadratureSubtransientReactance(), machine.getQuadratureOpenCircuitTransientTimeConstant(),
                        machine.getQuadratureOpenCircuitSubtransientTimeConstant(), "q");
    }
}
