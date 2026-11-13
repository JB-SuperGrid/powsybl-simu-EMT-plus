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
import com.powsybl.simuemt.extensions.excitation.limiter.DiscontinuousExcitationControlModel;
import com.powsybl.simuemt.extensions.excitation.limiter.OverexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.PowerFactorVarControllerModel;
import com.powsybl.simuemt.extensions.excitation.limiter.StatorCurrentLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.UnderexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.VoltsPerHertzLimiterModel;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.ClassicalDefinitions.RotorCircuit;
import com.powsybl.simuemt.extensions.machine.ClassicalMachineEquivalent;
import com.powsybl.simuemt.extensions.machine.ClassicalMachineModel;
import com.powsybl.simuemt.extensions.machine.MachineSaturation;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
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
    /** Field voltage, torque, power, field voltage {@code vf}, speed: {@code vf} controlled. */
    private static final String CONTROLS = "CTRL=0 0 0 1 0,";
    /** Observe and scope requests EMTPWorks' form writes, every one off but for a regulator's. */
    private static final int OBSERVABLES = 31;
    /** The positions of {@code vd} and {@code vq} among them; 2 asks for the signal to be observed. */
    private static final int OBSERVE_VD = 10;
    private static final int OBSERVE_VQ = 16;
    private static final String OBSERVED = "2";

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
            if (isEnabled(extension) && !(extension instanceof SimplifiedAvrModel)) {
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
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            // Pin k on the bus phase, the field-voltage control signal, the observed signals, a blank pin.
            lines.add("_SM;" + name + "abc".charAt(p) + (regulated ? ";7;5;" : ";5;3;") + phaseNodes[p] + "," + fieldVoltageSignal(name) + ","
                    + (regulated ? signal(name, "vd") + "," + signal(name, "vq") + "," : "") + ",");
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
        // A shaft of its own, one mass - the rotor's, no exciter's -: all the external torque, H, DSR; nothing observed.
        lines.add("MS=0 1 1 0,");
        lines.add("100 " + number(machine.getInertiaConstant()) + " " + number(speedDeviationDamping) + " 0 0 0");
        lines.add("0 0 0 0 0");
        // H in place of the moment of inertia; SI units for the scopes and the observe signals.
        lines.add("MSu=1 0 0,");
        lines.add(PRECISION);
        // Its controls per unit; the field voltage controlled, held by its constant or driven by its regulator.
        lines.add("CTRLu=1,");
        lines.add(CONTROLS);
        // The scopes in SI, the observed signals per unit.
        lines.add("OBSu=0 1,");
        List<String> observe = new ArrayList<>(Collections.nCopies(OBSERVABLES, "0"));
        if (regulated) {
            observe.set(OBSERVE_VD, OBSERVED);
            observe.set(OBSERVE_VQ, OBSERVED);
        }
        lines.add("OBS=" + String.join(" ", observe) + ",");
        return lines;
    }

    /**
     * The control devices that drive the machine's field voltage: the constant that holds it at the load flow's, per unit, from the
     * start (step mode); or its regulator's voltage error and lag.
     */
    static List<Device> fieldVoltage(Generator generator, String name) {
        MachineInitialState initial = MachineInitialState.subtransient(generator, subtransient(generator), 0.0);
        double efd = initial.efd();
        SimplifiedAvrModel avr = regulator(generator);
        if (avr == null) {
            return List.of(new Device("_c_cst", List.of("_c_cst;" + name + "_VF;1;1;" + fieldVoltageSignal(name) + ",", number(efd) + ",S1,")));
        }
        double ka = avr.getGain();
        String error = signal(name, "VERR");
        return List.of(
                // Its output, its input, no reset; starting at Efd0, within the model's limits; Ka times 1 over 1 + Ta s, the
                // coefficients in increasing powers of s.
                new Device("_c_fslim", List.of("_c_fslim;" + name + "_AVR;4;4;" + fieldVoltageSignal(name) + "," + error + ",0,0,",
                        number(efd) + "," + number(avr.getMaxOutput()) + "," + number(avr.getMinOutput()) + ",1,1,2," + number(ka) + ",S1,",
                        "1", ";", "1 " + number(avr.getTimeConstant()))),
                new Device("_c_fu", List.of("_c_fu;" + name + "_VERR;3;3;" + error + "," + signal(name, "vd") + "," + signal(name, "vq") + ",",
                        number(efd / ka) + ",S1,",
                        number(initial.terminalVoltage() + efd / ka) + "-SQRT(u[1]*u[1]+u[2]*u[2])")));
    }

    /** The machine's enabled simplified voltage regulator, or {@code null}. */
    private static SimplifiedAvrModel regulator(Generator generator) {
        return generator.getExtension(ExcitationSystemModel.class) instanceof SimplifiedAvrModel avr && avr.isEnabled() ? avr : null;
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
