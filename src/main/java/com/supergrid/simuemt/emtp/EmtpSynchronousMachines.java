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
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.excitation.Ac1AModel;
import com.powsybl.simuemt.extensions.excitation.Ac6AInitialState;
import com.powsybl.simuemt.extensions.excitation.Ac6AModel;
import com.powsybl.simuemt.extensions.excitation.Ac8BInitialState;
import com.powsybl.simuemt.extensions.excitation.Ac8BModel;
import com.powsybl.simuemt.extensions.excitation.Dc1AModel;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.excitation.ExponentialSaturation;
import com.powsybl.simuemt.extensions.excitation.RectifierRegulation;
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
 * <p>An enabled {@link Dc1AModel} drives it as ATP's TACS has it: the error {@code Vref + Vs - Vt - VF} on the unfiltered terminal
 * voltage, {@code Vref = Vt0 + Vr0 / Ka}; its regulator {@code Ka / (1 + sTa)} within its dynamic limits, at rest on {@code Vr0 =
 * (Ke + Se(Efd0)) Efd0}; its exciter {@code Te dEfd/dt = Vr - (Ke + Se(Efd)) Efd} - an integrator ({@code _c_fs}, {@code 1 / (Te s)})
 * on a function reading the field voltage it drives -, {@code Se(E) = Aex exp(Bex E)} fitted through its two saturation points
 * ({@link ExponentialSaturation}); and its rate feedback {@code Kf s / (1 + sTf)} off the field voltage, left out when {@code Kf = 0}.
 *
 * <p>An enabled {@link Ac1AModel} drives it as ATP's TACS has it, the field current observed: its transducer; the error {@code Vref +
 * Vs - VC - VF}, {@code Vref = Vt0 + Vr0 / Ka}; its lead-lag, left out when {@code Tb = 0}; {@code VA = Ka / (1 + sTa)} within its
 * dynamic limits, then held within {@code [VRMIN, VRMAX]} - static -; its exciter {@code Te dVe/dt = VB - Ve (Ke + Se(Ve)) - Kd Ifd},
 * an integrator, at rest on the {@code Ve0} that delivers {@code Efd0} into {@code Ifd0 = Efd0} ({@link
 * RectifierRegulation#exciterVoltageFor}); the field voltage {@code max(Ve, 1e-3) FEX(IN)}, {@code IN = min(Kc Ifd / Ve, 1)}, {@link
 * RectifierRegulation}'s three arcs blended at its two knots by ATP's smooth steps; and its rate feedback {@code Kf s / (1 + sTf)}
 * off {@code Ve}, from its rest.
 *
 * <p>An enabled {@link Ac6AModel} drives it as ATP's TACS has it, at rest on upstream's {@link Ac6AInitialState}: the error {@code
 * Vref + Vs - VC}; {@code KA (1 + sTK) / (1 + sTA)} on it; the limited lead-lag {@code (1 + sTC) / (1 + sTB)} within {@code [VAMIN,
 * VAMAX]}, its state following the limited output (Dynawo's {@code LimitedLeadLag}); the field current limiter {@code clamp(KH (VFE -
 * VFELIM), 0, VHMAX)} through its lead-lag {@code (1 + sTJ) / (1 + sTH)}, taken off; {@code VR} within {@code [Vt VRMIN, Vt VRMAX]};
 * the exciter {@code TE dVE/dt = VR - VFE}, {@code VFE = KE VE + SE(VE) VE + KD Ifd}, an integrator whose input a smooth step cuts while
 * {@code VE} is at 0 and still falling; and AC1A's rectifier.
 *
 * <p>An enabled {@link Ac8BModel} drives it as ATP's TACS has it, at rest on upstream's {@link Ac8BInitialState}: the error {@code Vref
 * + Vs - VC}; its PID {@code KPR e + KIR/s e + (KDR/TDR)(e - e/(1 + sTDR))} within {@code [VRMIN, VRMAX]}, the integrator's input cut
 * by ATP's limiter steps while past a limit and pushed further (Dynawo's {@code LimitedPI}); {@code EFE = KA / (1 + sTA)} within the
 * same limits, a static gain when {@code TA = 0}; the exciter {@code TE dVE/dt = EFE - VFE}, held while {@code VFE} is at {@code VFEMAX}
 * and rising or {@code VE} at {@code VEMIN} and falling; and AC1A's rectifier.
 *
 * <p>An enabled {@link Pss1AModel} with any of them adds its output to that error, as ATP's TACS has it: the machine observes its rotor's speed
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
            if (isEnabled(extension) && !(extension instanceof SimplifiedAvrModel) && !(extension instanceof St1AModel) && !(extension instanceof Dc1AModel)
                    && !(extension instanceof Ac1AModel) && !(extension instanceof Ac6AModel) && !(extension instanceof Ac8BModel)
                    && !(extension instanceof Pss1AModel)
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
        if (excitation instanceof Dc1AModel dc1a) {
            devices.addAll(dc1a(dc1a, name, initial, pss != null));
            return devices;
        }
        if (excitation instanceof Ac1AModel ac1a) {
            devices.addAll(ac1a(ac1a, name, initial, pss != null));
            return devices;
        }
        if (excitation instanceof Ac6AModel ac6a) {
            devices.addAll(ac6a(ac6a, generator, name, initial, pss != null));
            return devices;
        }
        if (excitation instanceof Ac8BModel ac8b) {
            devices.addAll(ac8b(ac8b, generator, name, initial, pss != null));
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

    /** DC1A's devices, from the observed {@code vd} and {@code vq} to the field voltage's control signal; the stabilizer's output
     * {@code VS} added to its error if {@code stabilized}. */
    private static List<Device> dc1a(Dc1AModel dc1a, String name, MachineInitialState initial, boolean stabilized) {
        double efd0 = initial.efd();
        double ka = dc1a.getRegulatorGain();
        double ke = dc1a.getExciterConstant();
        ExponentialSaturation saturation = ExponentialSaturation.fit(dc1a.getSaturationVoltage1(), dc1a.getSaturationFactor1(),
                dc1a.getSaturationVoltage2(), dc1a.getSaturationFactor2());
        double vr0 = (ke + saturation.aex() * Math.exp(saturation.bex() * efd0)) * efd0;
        String field = fieldVoltageSignal(name);
        List<Device> devices = new ArrayList<>();
        List<String> errorInputs = new ArrayList<>(List.of(signal(name, "vd"), signal(name, "vq")));
        StringBuilder error = new StringBuilder(number(initial.terminalVoltage() + vr0 / ka) + "-SQRT(u[1]*u[1]+u[2]*u[2])");
        if (stabilized) {
            errorInputs.add(signal(name, "VS"));
            error.append("+u[").append(errorInputs.size()).append("]");
        }
        if (dc1a.getRateFeedbackGain() != 0.0 && dc1a.getRateFeedbackTimeConstant() > 0.0) {
            String feedback = signal(name, "VRF");
            devices.add(firstOrder(name + "_VRF", feedback, field, 0.0, 1.0, 0.0, dc1a.getRateFeedbackGain(), dc1a.getRateFeedbackTimeConstant()));
            errorInputs.add(feedback);
            error.append("-u[").append(errorInputs.size()).append("]");
        }
        String errorSignal = signal(name, "VERR");
        devices.add(function(name + "_VERR", errorSignal, errorInputs, vr0 / ka, error.toString()));
        String regulator = signal(name, "VR");
        devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_VR;4;4;" + regulator + "," + errorSignal + ",0,0,",
                number(vr0) + "," + number(dc1a.getMaxOutput()) + "," + number(dc1a.getMinOutput()) + ",1,1,2," + number(ka) + ",S1,",
                "1", ";", "1 " + number(dc1a.getRegulatorTimeConstant()))));
        // The exciter: 1 / (Te s) on Vr - (Ke + Aex exp(Bex Efd)) Efd, at rest on 0 with Efd at Efd0.
        String buildup = signal(name, "VEX");
        devices.add(function(name + "_VEX", buildup, List.of(regulator, field), 0.0,
                "u[1]-((" + number(ke) + ")+(" + number(saturation.aex()) + ")*EXP((" + number(saturation.bex()) + ")*u[2]))*u[2]"));
        devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_EFD;2;2;" + field + "," + buildup + ",", number(efd0) + ",1,1,2,1,S1,",
                "1", ";", "0 " + number(dc1a.getExciterTimeConstant()))));
        return devices;
    }

    /** The floor under an AC exciter's voltage, which divides into {@code IN}: ATP's. */
    private static final double EXCITER_VOLTAGE_FLOOR = 1.0e-3;
    /** The sharpness of the smooth steps blending the rectifier's arcs: ATP's. */
    private static final double FEX_SWITCH_EPS = 1.0e-6;

    /** AC1A's devices, from the observed {@code vd}, {@code vq} and field current to the field voltage's control signal; the
     * stabilizer's output {@code VS} added to its error if {@code stabilized}. */
    private static List<Device> ac1a(Ac1AModel ac1a, String name, MachineInitialState initial, boolean stabilized) {
        double efd0 = initial.efd();
        double vt0 = initial.terminalVoltage();
        double ka = ac1a.getRegulatorGain();
        double ke = ac1a.getExciterConstant();
        double kc = ac1a.getRectifierLoadingFactor();
        ExponentialSaturation saturation = ExponentialSaturation.fit(ac1a.getSaturationVoltage1(), ac1a.getSaturationFactor1(),
                ac1a.getSaturationVoltage2(), ac1a.getSaturationFactor2());
        double ve0 = RectifierRegulation.exciterVoltageFor(efd0, kc, efd0);
        double vr0 = ve0 * (ke + saturation.aex() * Math.exp(saturation.bex() * ve0)) + ac1a.getDemagnetizingFactor() * efd0;
        String vt = signal(name, "VT");
        String fieldCurrent = signal(name, FIELD_CURRENT);
        String ve = signal(name, "VE");
        List<Device> devices = new ArrayList<>();
        devices.add(function(name + "_VT", vt, List.of(signal(name, "vd"), signal(name, "vq")), vt0, "SQRT(u[1]*u[1]+u[2]*u[2])"));
        String measured = vt;
        if (ac1a.getTransducerTimeConstant() > 0.0) {
            measured = signal(name, "VC");
            devices.add(firstOrder(name + "_VC", measured, vt, vt0, 1.0, 1.0, 0.0, ac1a.getTransducerTimeConstant()));
        }
        List<String> errorInputs = new ArrayList<>(List.of(measured));
        StringBuilder error = new StringBuilder(number(vt0 + vr0 / ka) + "-u[1]");
        if (stabilized) {
            errorInputs.add(signal(name, "VS"));
            error.append("+u[").append(errorInputs.size()).append("]");
        }
        if (ac1a.getRateFeedbackGain() != 0.0 && ac1a.getRateFeedbackTimeConstant() > 0.0) {
            // Off Ve's departure from its rest, so that the washout starts at rest.
            String departure = signal(name, "DVE");
            String feedback = signal(name, "VRF");
            devices.add(function(name + "_DVE", departure, List.of(ve), 0.0, "u[1]-" + number(ve0)));
            devices.add(firstOrder(name + "_VRF", feedback, departure, 0.0, 1.0, 0.0, ac1a.getRateFeedbackGain(), ac1a.getRateFeedbackTimeConstant()));
            errorInputs.add(feedback);
            error.append("-u[").append(errorInputs.size()).append("]");
        }
        String errorSignal = signal(name, "VERR");
        devices.add(function(name + "_VERR", errorSignal, errorInputs, vr0 / ka, error.toString()));
        String shaped = errorSignal;
        if (ac1a.getLagTimeConstant() > 0.0) {
            shaped = signal(name, "LL1");
            devices.add(firstOrder(name + "_LL1", shaped, errorSignal, vr0 / ka, 1.0, 1.0, ac1a.getLeadTimeConstant(), ac1a.getLagTimeConstant()));
        }
        String regulator = signal(name, "VA");
        devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_VA;4;4;" + regulator + "," + shaped + ",0,0,",
                number(vr0) + "," + number(ac1a.getRegulatorOutputMax()) + "," + number(ac1a.getRegulatorOutputMin()) + ",1,1,2," + number(ka) + ",S1,",
                "1", ";", "1 " + number(ac1a.getRegulatorTimeConstant()))));
        String drive = signal(name, "VB");
        devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_VB;2;2;" + drive + "," + regulator + ",",
                number(ac1a.getFieldVoltageMax()) + "," + number(ac1a.getFieldVoltageMin()) + ",1,S1,")));
        // The exciter: 1 / (Te s) on VB - Ve (Ke + Aex exp(Bex Ve)) - Kd Ifd, at rest on 0 with Ve at Ve0.
        String buildup = signal(name, "VEX");
        devices.add(function(name + "_VEX", buildup, List.of(drive, ve, fieldCurrent), 0.0,
                "u[1]-u[2]*((" + number(ke) + ")+(" + number(saturation.aex()) + ")*EXP((" + number(saturation.bex()) + ")*u[2]))-("
                        + number(ac1a.getDemagnetizingFactor()) + ")*u[3]"));
        devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_VE;2;2;" + ve + "," + buildup + ",", number(ve0) + ",1,1,2,1,S1,",
                "1", ";", "0 " + number(ac1a.getExciterTimeConstant()))));
        devices.addAll(rectifier(name, ve, kc, efd0, ve0));
        return devices;
    }

    /** An AC exciter's rectifier, from its exciter voltage {@code ve} at rest on {@code ve0}: the field voltage {@code max(Ve, floor)
     * FEX(IN)}, {@code IN = min(Kc Ifd / max(Ve, floor), 1)}, {@link RectifierRegulation}'s arcs blended by ATP's smooth steps. */
    private static List<Device> rectifier(String name, String ve, double kc, double efd0, double ve0) {
        String fieldCurrent = signal(name, FIELD_CURRENT);
        List<Device> devices = new ArrayList<>();
        String floored = "0.5*(u[1]+" + number(EXCITER_VOLTAGE_FLOOR) + "+ABS(u[1]-" + number(EXCITER_VOLTAGE_FLOOR) + "))";
        if (kc == 0.0) {
            devices.add(function(name + "_EF", fieldVoltageSignal(name), List.of(ve), efd0, floored));
            return devices;
        }
        double in0 = kc * efd0 / ve0;
        String loadingRaw = signal(name, "INR");
        String loading = signal(name, "IN");
        devices.add(function(name + "_INR", loadingRaw, List.of(ve, fieldCurrent), in0, "(" + number(kc) + ")*u[2]/(" + floored + ")"));
        devices.add(function(name + "_IN", loading, List.of(loadingRaw), in0, "0.5*(u[1]+1-ABS(u[1]-1))"));
        String step1 = signal(name, "S1");
        String step2 = signal(name, "S2");
        devices.add(function(name + "_S1", step1, List.of(loading), 0.0, smoothStep(RectifierRegulation.FIRST_KNOT)));
        devices.add(function(name + "_S2", step2, List.of(loading), 0.0, smoothStep(RectifierRegulation.SECOND_KNOT)));
        String arc = signal(name, "M2");
        String underRoot = "(" + number(RectifierRegulation.MODE2_RADIUS_SQUARED) + "-u[1]*u[1])";
        devices.add(function(name + "_M2", arc, List.of(loading), 0.0, "SQRT(0.5*(" + underRoot + "+ABS" + underRoot + "))"));
        String factor = signal(name, "FEX");
        devices.add(function(name + "_FEX", factor, List.of(loading, step1, step2, arc), RectifierRegulation.fex(in0),
                "(1-u[2])*(1-" + number(RectifierRegulation.MODE1_SLOPE) + "*u[1])+u[2]*(1-u[3])*u[4]+u[3]*"
                        + number(RectifierRegulation.MODE3_SLOPE) + "*(1-u[1])"));
        devices.add(function(name + "_EF", fieldVoltageSignal(name), List.of(ve, factor), efd0, floored + "*u[2]"));
        return devices;
    }

    /** AC6A's devices, from the observed {@code vd}, {@code vq} and field current to the field voltage's control signal; the
     * stabilizer's output {@code VS} added to its error if {@code stabilized}. */
    private static List<Device> ac6a(Ac6AModel ac6a, Generator generator, String name, MachineInitialState initial, boolean stabilized) {
        double efd0 = initial.efd();
        double vt0 = initial.terminalVoltage();
        Ac6AInitialState state = Ac6AInitialState.of(generator, ac6a, efd0);
        ExponentialSaturation saturation = ac6a.saturation();
        String vt = signal(name, "VT");
        String fieldCurrent = signal(name, FIELD_CURRENT);
        String ve = signal(name, "VE");
        List<Device> devices = new ArrayList<>();
        devices.add(function(name + "_VT", vt, List.of(signal(name, "vd"), signal(name, "vq")), vt0, "SQRT(u[1]*u[1]+u[2]*u[2])"));
        String measured = vt;
        if (ac6a.getTransducerTimeConstant() > 0.0) {
            measured = signal(name, "VC");
            devices.add(firstOrder(name + "_VC", measured, vt, vt0, 1.0, 1.0, 0.0, ac6a.getTransducerTimeConstant()));
        }
        double error0 = state.reference() - state.voltage();
        String errorSignal = signal(name, "VERR");
        devices.add(function(name + "_VERR", errorSignal, stabilized ? List.of(measured, signal(name, "VS")) : List.of(measured), error0,
                number(state.reference()) + "-u[1]" + (stabilized ? "+u[2]" : "")));
        // KA (1 + sTK) / (1 + sTA).
        String amplified = signal(name, "K6");
        devices.add(ac6a.getRegulatorTimeConstant() > 0.0
                ? firstOrder(name + "_K6", amplified, errorSignal, ac6a.getRegulatorGain() * error0, ac6a.getRegulatorGain(), 1.0,
                        ac6a.getRegulatorLeadTimeConstant(), ac6a.getRegulatorTimeConstant())
                : function(name + "_K6", amplified, List.of(errorSignal), ac6a.getRegulatorGain() * error0,
                        number(ac6a.getRegulatorGain()) + "*u[1]"));
        String regulator = signal(name, "VA");
        devices.addAll(limitedLeadLag(name + "_VA", regulator, amplified, ac6a.getLeadTimeConstant(), ac6a.getLagTimeConstant(),
                ac6a.getRegulatorOutputMin(), ac6a.getRegulatorOutputMax(), state.regulator()));
        // VFE = KE VE + Aex exp(Bex VE) VE + KD Ifd; its limiter clamp(KH (VFE - VFELIM), 0, VHMAX) through its lead-lag.
        String exciterField = signal(name, "VFE");
        devices.add(function(name + "_VFE", exciterField, List.of(ve, fieldCurrent), state.exciterField(),
                "(" + number(ac6a.getExciterConstant()) + ")*u[1]+(" + number(saturation.aex()) + ")*EXP((" + number(saturation.bex()) + ")*u[1])*u[1]+("
                        + number(ac6a.getDemagnetizingFactor()) + ")*u[2]"));
        String excess = signal(name, "LR6");
        devices.add(function(name + "_LR6", excess, List.of(exciterField), 0.0,
                "(" + number(ac6a.getFieldCurrentFeedbackGain()) + ")*(u[1]-" + number(ac6a.getFieldCurrentFeedbackThreshold()) + ")"));
        String limited = signal(name, "L6");
        devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_L6;2;2;" + limited + "," + excess + ",",
                number(ac6a.getFieldCurrentFeedbackMax()) + ",0,1,S1,")));
        String feedback = limited;
        if (ac6a.getFieldCurrentFeedbackLagTimeConstant() > 0.0) {
            feedback = signal(name, "VH");
            devices.add(firstOrder(name + "_VH", feedback, limited, state.feedback(), 1.0, 1.0, ac6a.getFieldCurrentFeedbackLeadTimeConstant(),
                    ac6a.getFieldCurrentFeedbackLagTimeConstant()));
        }
        // VR = clamp(VA - VH, Vt VRMIN, Vt VRMAX).
        String floored = signal(name, "VRL");
        String vr = signal(name, "VR");
        double vr0 = state.exciterField();
        String floor = "(" + number(ac6a.getFieldVoltageMin()) + ")*u[3]";
        String ceiling = "(" + number(ac6a.getFieldVoltageMax()) + ")*u[2]";
        devices.add(function(name + "_VRL", floored, List.of(regulator, feedback, vt), vr0, "0.5*(u[1]-u[2]+" + floor + "+ABS(u[1]-u[2]-" + floor + "))"));
        devices.add(function(name + "_VR", vr, List.of(floored, vt), vr0, "0.5*(u[1]+" + ceiling + "-ABS(u[1]-" + ceiling + "))"));
        // TE dVE/dt = VR - VFE, cut while VE is at 0 and still falling: rate (1 - step(-VE) (1 - step(rate))).
        String gated = signal(name, "VEY");
        String rate = "(u[1]-u[2])";
        devices.add(function(name + "_VEY", gated, List.of(vr, exciterField, ve), 0.0,
                rate + "*(1-" + limiterStep("(-u[3])") + "*(1-" + limiterStep(rate) + "))"));
        devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_VE;2;2;" + ve + "," + gated + ",", number(state.exciterVoltage()) + ",1,1,2,1,S1,",
                "1", ";", "0 " + number(ac6a.getExciterTimeConstant()))));
        devices.addAll(rectifier(name, ve, ac6a.getRectifierLoadingFactor(), efd0, state.exciterVoltage()));
        return devices;
    }

    /** AC8B's devices, from the observed {@code vd}, {@code vq} and field current to the field voltage's control signal; the
     * stabilizer's output {@code VS} added to its error if {@code stabilized}. */
    private static List<Device> ac8b(Ac8BModel ac8b, Generator generator, String name, MachineInitialState initial, boolean stabilized) {
        double efd0 = initial.efd();
        double vt0 = initial.terminalVoltage();
        Ac8BInitialState state = Ac8BInitialState.of(generator, ac8b, efd0);
        ExponentialSaturation saturation = ac8b.saturation();
        String vt = signal(name, "VT");
        String fieldCurrent = signal(name, FIELD_CURRENT);
        String ve = signal(name, "VE");
        List<Device> devices = new ArrayList<>();
        devices.add(function(name + "_VT", vt, List.of(signal(name, "vd"), signal(name, "vq")), vt0, "SQRT(u[1]*u[1]+u[2]*u[2])"));
        String measured = vt;
        if (ac8b.getTransducerTimeConstant() > 0.0) {
            measured = signal(name, "VC");
            devices.add(firstOrder(name + "_VC", measured, vt, vt0, 1.0, 1.0, 0.0, ac8b.getTransducerTimeConstant()));
        }
        double error0 = state.reference() - state.voltage();
        String errorSignal = signal(name, "VERR");
        devices.add(function(name + "_VERR", errorSignal, stabilized ? List.of(measured, signal(name, "VS")) : List.of(measured), error0,
                number(state.reference()) + "-u[1]" + (stabilized ? "+u[2]" : "")));
        // The PID's sum KPR e + (KDR/TDR)(e - lag(e)) + I, I = KIR/s on e cut while the sum is past a limit and e pushes it further.
        List<String> sumInputs = new ArrayList<>(List.of(errorSignal));
        StringBuilder sum = new StringBuilder("(" + number(ac8b.getProportionalGain()) + ")*u[1]");
        if (ac8b.getDerivativeGain() > 0.0) {
            String lag = signal(name, "DL");
            devices.add(firstOrder(name + "_DL", lag, errorSignal, error0, 1.0, 1.0, 0.0, ac8b.getDerivativeTimeConstant()));
            sumInputs.add(lag);
            sum.append("+(").append(number(ac8b.getDerivativeGain() / ac8b.getDerivativeTimeConstant())).append(")*(u[1]-u[").append(sumInputs.size()).append("])");
        }
        String sumSignal = signal(name, "PS");
        if (ac8b.getIntegralGain() > 0.0) {
            String integral = signal(name, "PI");
            String gated = signal(name, "PY");
            sumInputs.add(integral);
            sum.append("+u[").append(sumInputs.size()).append("]");
            String pushesUp = limiterStep("u[1]");
            devices.add(function(name + "_PY", gated, List.of(errorSignal, sumSignal), 0.0, "u[1]*(1-" + limiterStep("(u[2]-(" + number(ac8b.getRegulatorOutputMax()) + "))")
                    + "*" + pushesUp + "-" + limiterStep("((" + number(ac8b.getRegulatorOutputMin()) + ")-u[2])") + "*(1-" + pushesUp + "))"));
            devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_PI;2;2;" + integral + "," + gated + ",", number(state.regulatorIntegral()) + ",1,1,2,"
                    + number(ac8b.getIntegralGain()) + ",S1,", "1", ";", "0 1")));
        }
        devices.add(function(name + "_PS", sumSignal, sumInputs, state.regulatorOutput(), sum.toString()));
        String pid = signal(name, "PO");
        devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_PO;2;2;" + pid + "," + sumSignal + ",",
                number(ac8b.getRegulatorOutputMax()) + "," + number(ac8b.getRegulatorOutputMin()) + ",1,S1,")));
        String exciterDrive = signal(name, "EFE");
        if (ac8b.getRegulatorTimeConstant() > 0.0) {
            devices.add(new Device("_c_fslim", List.of("_c_fslim;" + name + "_EFE;4;4;" + exciterDrive + "," + pid + ",0,0,",
                    number(state.exciterField()) + "," + number(ac8b.getRegulatorOutputMax()) + "," + number(ac8b.getRegulatorOutputMin()) + ",1,1,2,"
                            + number(ac8b.getRegulatorGain()) + ",S1,", "1", ";", "1 " + number(ac8b.getRegulatorTimeConstant()))));
        } else {
            devices.add(new Device("_c_lim", List.of("_c_lim;" + name + "_EFE;2;2;" + exciterDrive + "," + pid + ",",
                    number(ac8b.getRegulatorOutputMax()) + "," + number(ac8b.getRegulatorOutputMin()) + "," + number(ac8b.getRegulatorGain()) + ",S1,")));
        }
        String exciterField = signal(name, "VFE");
        devices.add(function(name + "_VFE", exciterField, List.of(ve, fieldCurrent), state.exciterField(),
                "(" + number(ac8b.getExciterConstant()) + ")*u[1]+(" + number(saturation.aex()) + ")*EXP((" + number(saturation.bex()) + ")*u[1])*u[1]+("
                        + number(ac8b.getDemagnetizingFactor()) + ")*u[2]"));
        // TE dVE/dt = EFE - VFE, held while VFE is at VFEMAX and rising or VE at VEMIN and falling.
        String rising = signal(name, "VEW");
        devices.add(function(name + "_VEW", rising, List.of(exciterDrive, exciterField), 0.5, limiterStep("(u[1]-u[2])")));
        String gated = signal(name, "VEY");
        devices.add(function(name + "_VEY", gated, List.of(exciterDrive, exciterField, ve, rising), 0.0,
                "(u[1]-u[2])*(1-" + limiterStep("(u[2]-(" + number(ac8b.getExciterFieldMax()) + "))") + "*u[4]-"
                        + limiterStep("((" + number(ac8b.getExciterVoltageMin()) + ")-u[3])") + "*(1-u[4]))"));
        devices.add(new Device("_c_fs", List.of("_c_fs;" + name + "_VE;2;2;" + ve + "," + gated + ",", number(state.exciterVoltage()) + ",1,1,2,1,S1,",
                "1", ";", "0 " + number(ac8b.getExciterTimeConstant()))));
        devices.addAll(rectifier(name, ve, ac8b.getRectifierLoadingFactor(), efd0, state.exciterVoltage()));
        return devices;
    }

    /** ATP's limiter step {@code 0.5 (1 + x / sqrt(x^2 + eps))} on the expression {@code x} ({@link NumericalAids#LIMITER_STEP_EPSILON}). */
    private static String limiterStep(String x) {
        return "0.5*(1+" + x + "/SQRT(" + x + "*" + x + "+" + number(NumericalAids.LIMITER_STEP_EPSILON) + "))";
    }

    /**
     * Dynawo's limited lead-lag {@code (1 + sT1) / (1 + sT2)} from {@code input} to {@code output}, as ATP writes it: {@code y =
     * clamp(T1/T2 (u - x), min, max)}, {@code x} a transfer function {@code ((T1 - T2) / T1) / (1 + sT1)} on {@code y}; without lead, a
     * lag whose limits stop its state; without lag, the limits alone. At rest on {@code output0}.
     */
    private static List<Device> limitedLeadLag(String device, String output, String input, double lead, double lag, double min, double max,
                                               double output0) {
        if (lag <= 0.0) {
            return List.of(new Device("_c_lim", List.of("_c_lim;" + device + ";2;2;" + output + "," + input + ",", number(max) + "," + number(min) + ",1,S1,")));
        }
        if (lead <= 0.0) {
            return List.of(new Device("_c_fslim", List.of("_c_fslim;" + device + ";4;4;" + output + "," + input + ",0,0,",
                    number(output0) + "," + number(max) + "," + number(min) + ",1,1,2,1,S1,", "1", ";", "1 " + number(lag))));
        }
        double feedback = (lead - lag) / lead;
        String state = output + "X";
        String raw = output + "R";
        return List.of(
                firstOrder(device + "X", state, output, feedback * output0, feedback, 1.0, 0.0, lead),
                function(device + "R", raw, List.of(input, state), output0, number(lead / lag) + "*(u[1]-u[2])"),
                new Device("_c_lim", List.of("_c_lim;" + device + ";2;2;" + output + "," + raw + ",", number(max) + "," + number(min) + ",1,S1,")));
    }

    /** ATP's smooth unit step at {@code threshold} on {@code u[1]}: {@code 0.5 (1 + d / sqrt(d^2 + eps))}. */
    private static String smoothStep(double threshold) {
        String d = "(u[1]-" + number(threshold) + ")";
        return "0.5*(1+" + d + "/SQRT(" + d + "*" + d + "+" + number(FEX_SWITCH_EPS) + "))";
    }

    /** A function ({@code _c_fu}) of {@code inputs}, {@code u[1]} on, its output {@code output} at {@code history} at the start. */
    private static Device function(String device, String output, List<String> inputs, double history, String expression) {
        List<String> lines = new ArrayList<>(List.of("_c_fu;" + device + ";" + (inputs.size() + 1) + ";" + (inputs.size() + 1) + ";" + output + ","
                + String.join(",", inputs) + ",", number(history) + ",S1,"));
        lines.addAll(wrapped(expression));
        return new Device("_c_fu", lines);
    }

    /** The longest expression line written: EMTP cuts a netlist line short past some 100 characters, and reads an expression on as
     * many lines as it takes, as EMTPWorks writes one wrapped at 90. */
    private static final int EXPRESSION_LINE = 80;

    /** {@code expression} on lines of at most {@link #EXPRESSION_LINE} characters, each ending on an operator - never inside a
     * number, its exponent's sign included. */
    static List<String> wrapped(String expression) {
        List<String> lines = new ArrayList<>();
        String rest = expression;
        while (rest.length() > EXPRESSION_LINE) {
            int cut = -1;
            for (int k = EXPRESSION_LINE; k > 0 && cut < 0; k--) {
                char c = rest.charAt(k);
                if ((c == '+' || c == '-' || c == '*' || c == '/') && Character.toUpperCase(rest.charAt(k - 1)) != 'E') {
                    cut = k + 1;
                }
            }
            if (cut <= 0) {
                break;
            }
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut);
        }
        lines.add(rest);
        return lines;
    }

    /** Whether the machine's excitation system reads its field current: an ST1A's ceiling or field-current limiter, an AC1A. */
    private static boolean readsFieldCurrent(Generator generator) {
        return regulator(generator) instanceof St1AModel st1a && (st1a.getRectifierLoadingFactor() != 0.0 || st1a.getFieldCurrentLimiterGain() != 0.0)
                || regulator(generator) instanceof Ac1AModel || regulator(generator) instanceof Ac6AModel || regulator(generator) instanceof Ac8BModel;
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

    /** The machine's enabled excitation system this writes - a simplified regulator, an ST1A, a DC1A or an AC1A -, or {@code null}. */
    private static ExcitationSystemModel regulator(Generator generator) {
        ExcitationSystemModel excitation = generator.getExtension(ExcitationSystemModel.class);
        return (excitation instanceof SimplifiedAvrModel || excitation instanceof St1AModel || excitation instanceof Dc1AModel
                || excitation instanceof Ac1AModel || excitation instanceof Ac6AModel || excitation instanceof Ac8BModel) && excitation.isEnabled()
                ? excitation : null;
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
