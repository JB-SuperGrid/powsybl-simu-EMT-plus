/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxArtifact;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxInitialValue;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxModel;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxModelStore;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxModels;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxQuantity;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxSignal;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.excitation.SimplifiedAvrModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.pss.Pss1AModel;
import com.powsybl.simuemt.extensions.pss.PssModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;

/**
 * The black boxes EMTP runs: an IEEE/CIGRE B4.82 DLL - the very binary Kestrel and PSCAD load, built for win32 - through EMTPWorks'
 * own device for them, the {@code CIGRE_IEEE_DLL} toolbox's {@code _cm_dll}. EMTP's solver, {@code emtpopt.exe}, is 32-bit: it loads
 * the toolbox's x86 wrapper ({@code wrapper_1_1_0_0_x86.dll}, IEEE/CIGRE API 1.1), which it finds by itself in the toolbox folders
 * {@code Toolboxes\Toolbox.ini} lists, and the wrapper loads the model's DLL, copied beside the netlist. A win64 DLL cannot be loaded
 * there (the x64 wrapper is "not found" by the 32-bit solver).
 *
 * <p>The device, as EMTPWorks writes it (its scripts read 2026-11-22): its pins the DLL's outputs, then its inputs, one per scalar
 * port; {@code ParamsA} the numbers of outputs and inputs, then {@code 3,S0,}; then its {@code ModelData}: the wrapper, terminated by a
 * comma - EMTP reads that field on until one -, then one line per option, the project folder and the DLL's full path each as a count
 * of lines then the path cut in lines of 100 characters (EMTP reads the first 100 of a line), the inputs' accumulation flags, the
 * outputs' initial values, the parameters in the DLL's order - the study's, then the initial values the host computes, as the black
 * box lists them -, and each parameter's "variable" flag. The DLL's path must be absolute: a relative one is not loaded ("Cannot load
 * DLL at simple_avr.dll").
 *
 * <p>The DLL is called at its own sample time - the run's time step, which {@link BlackBoxModels#timeStepReasons} holds it to, as on
 * every backend - within the step's control solution, on the step's own inputs: the device "participates in control system solutions
 * without any time-step delay" (the toolbox's help). Its model initialisation is called, its outputs held on their initial values until
 * its first call.
 *
 * <p>What EMTP writes: a machine's excitation alone ({@link #excitation}); a converter's inner and outer controls together
 * ({@link #converterControl}); a machine alone, a voltage behind its interface impedance ({@link #machine}), beside a typed simplified
 * AVR and IEEE PSS1A on its field voltage and speed, if any ({@link #typedRegulators}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpBlackBoxes {

    /** The name upstream's support check phrases EMTP's reasons with. */
    static final String BACKEND = "EMTP";
    /** The platform of the DLLs EMTP's 32-bit solver loads. */
    static final String DLL_PLATFORM = "win32";
    /** The toolbox's wrapper for the IEEE/CIGRE API 1.1, x86. */
    static final String WRAPPER = "wrapper_1_1_0_0_x86.dll";

    /** The netlist being written, its DLLs copied beside it. */
    private static final ThreadLocal<Path> NETLIST = new ThreadLocal<>();

    private EmtpBlackBoxes() {
    }

    /** What EMTP cannot write of {@code network}'s black boxes: upstream's rules for a backend loading win32 DLLs, then what this backend
     * does not write yet. */
    static List<String> unsupportedReasons(Network network) {
        return new ArrayList<>(BlackBoxModels.unsupportedReasons(network, BACKEND, DLL_PLATFORM).stream().map(String::strip).toList());
    }

    /** Runs {@code writing} with the netlist {@code netlist} as the one its black boxes' DLLs are copied beside. */
    static <T> T writing(Path netlist, java.util.function.Supplier<T> writing) {
        NETLIST.set(netlist.toAbsolutePath());
        try {
            return writing.get();
        } finally {
            NETLIST.remove();
        }
    }

    /**
     * The black-box excitation system of {@code generator}, machine {@code name}: its DLL's instance, reading the terminal voltage {@code
     * vt} and the stabilizer's output {@code vs} ({@code null} without one: 0) on the ports bound to them, a constant on a port held at
     * one, initialised on the load flow; its field voltage on {@code efd}, also scoped ({@code control:<name>_EFD}).
     */
    static List<EmtpSynchronousMachines.Device> excitation(Generator generator, String name, MachineInitialState initial, String vt, String vs,
                                                           String efd) {
        BlackBoxModel<Generator> model = BlackBoxModels.of(generator);
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        for (BlackBoxSignal input : model.getInputs()) {
            String constant = name + "_BBX_" + input.port();
            if (input.isConstant() || input.quantity() == BlackBoxQuantity.PSS_OUTPUT_PU && vs == null) {
                devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + constant + ";1;1;" + constant + ",",
                        number(input.isConstant() ? input.constant() : 0.0) + ",S1,")));
                inputs.add(constant);
                continue;
            }
            inputs.add(switch (input.quantity()) {
                case TERMINAL_VOLTAGE_MAGNITUDE_PU -> vt;
                case PSS_OUTPUT_PU -> vs;
                default -> throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': input '" + input.port() + "' reads "
                        + input.quantity() + ", which EMTP does not feed a black-box excitation");
            });
        }
        List<Double> initialValues = new ArrayList<>();
        for (BlackBoxInitialValue initialValue : model.getInitialValues()) {
            initialValues.add(switch (initialValue.quantity()) {
                case TERMINAL_VOLTAGE_MAGNITUDE_PU -> initial.terminalVoltage();
                case FIELD_VOLTAGE_PU -> initial.efd();
                default -> throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': no initial value of "
                        + initialValue.quantity() + " for a black-box excitation");
            });
        }
        devices.add(instance(name + "_BBX", model, List.of(efd), List.of(initial.efd()), inputs, initialValues));
        devices.add(EmtpVoltageSourceConverters.scoped(EmtpSynchronousMachines.function(name + "_EFD", name + "_EFD", List.of(efd), initial.efd(), "u[1]")));
        return devices;
    }

    /**
     * Black box {@code model}'s {@code _cm_dll} device {@code device}: its outputs on {@code outputs}, starting at {@code initialOutputs},
     * its inputs read from {@code inputs}, its parameters the study's then {@code initialValues}; its DLL copied beside the netlist.
     */
    static EmtpSynchronousMachines.Device instance(String device, BlackBoxModel<?> model, List<String> outputs, List<Double> initialOutputs,
                                                   List<String> inputs, List<Double> initialValues) {
        Path netlist = NETLIST.get();
        if (netlist == null) {
            throw new IllegalStateException("SimuEmt Plus: a black box written outside a netlist");
        }
        BlackBoxArtifact artifact = BlackBoxModels.artifactFor(model, BACKEND, DLL_PLATFORM).orElseThrow();
        Path dll;
        try {
            Files.createDirectories(netlist.getParent());
            dll = BlackBoxModelStore.copyInto(artifact, netlist.getParent());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("SimuEmt Plus: could not create " + netlist.getParent(), e);
        }
        String file = netlist.getFileName().toString();
        Path project = netlist.resolveSibling(file.substring(0, file.length() - ".net".length()) + "_pj");

        List<String> pins = new ArrayList<>(outputs);
        pins.addAll(inputs);
        List<String> lines = new ArrayList<>(header("_cm_dll;" + device + ";" + pins.size() + ";" + pins.size() + ";", pins));
        lines.add(outputs.size() + "," + inputs.size() + ",3,S0,");
        lines.add(WRAPPER + ",");
        // The DLL not copied for this instance; the project folder, then the DLL, each a count of lines and the lines.
        lines.add("0");
        lines.addAll(path(project + "\\"));
        lines.addAll(path(dll.toString()));
        // The model's initialisation called; no interpolation; warnings allowed; the parameters' limits and fixed values enforced;
        // its initialisation not called from a snapshot; several calls a step allowed; released at once; not computed at t = 0; EMTP's
        // step taken if the DLL has none.
        lines.addAll(List.of("1", "0", "1", "1", "1", "1", "1", "0", "0", "1"));
        // No snapshot, at 1 s, no file; no autoinitialisation, no phase adjustment, at 1 s, no file.
        lines.addAll(List.of("0", "1.0", "1", ".", "0", "0", "1", "1", "."));
        // No input accumulated; the initial outputs given here, not through pins.
        lines.addAll(Collections.nCopies(inputs.size(), "0"));
        lines.add("0");
        initialOutputs.forEach(value -> lines.add(number(value)));
        List<Double> parameters = new ArrayList<>(model.getParameters().values());
        parameters.addAll(initialValues);
        parameters.forEach(value -> lines.add(number(value)));
        // No parameter varied through a pin.
        lines.addAll(Collections.nCopies(parameters.size(), "0"));
        return new EmtpSynchronousMachines.Device("_cm_dll", lines);
    }

    /** A device's first line, {@code start} then its pins, each ended by its comma - on as many lines as {@link EmtpNetlistWriter#MAX_LINE}
     * takes, broken after a pin's comma, as EMTPWorks continues one. */
    static List<String> header(String start, List<String> pins) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder(start);
        for (String pin : pins) {
            if (line.length() + pin.length() + 1 > EmtpNetlistWriter.MAX_LINE) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            line.append(pin).append(',');
        }
        lines.add(line.toString());
        return lines;
    }

    /**
     * The black-box controls of {@code converter}, {@code id} in the netlist: its DLL's instance fed the bus's phase voltages {@code v} and
     * the converter's phase currents - its EMFs' {@code i}, counted out of it, negated - per unit of its peak phase bases, and its DC
     * voltage per unit of its DC terminal's nominal one; initialised on the load flow, phase a's voltage angle at t = 0 {@code angle0} as
     * this backend's waveforms have it. As Kestrel builds it: its EMFs {@code m Vdc / 2}, per unit, parked in a frame turning at the
     * nominal frequency from that angle - {@code <id>_TH}, {@code <id>_ED}, {@code <id>_EQ} -, which the typed control's power stage then
     * turns back into the EMFs, a step ahead, and the DC current ({@code EmtpVoltageSourceConverters#emfsAndDcCurrent}); the currents
     * parked in that frame for it ({@code <id>_ID}, {@code <id>_IQ}). Recorded as the other backends record a black-box control: P, Q,
     * |V| and Vdc, unfiltered ({@code control:<id>_P}, {@code _Q}, {@code _VAC}, {@code _VDC}).
     *
     * <p>Its modulation indices are those of the step before: parked a step back ({@code <id>_THB}), they are turned a step on in
     * alpha-beta - as upstream's ATP, PSCAD and Xyce turn a DLL's three-phase outputs -, on top of the step every EMF of this backend is put
     * ahead. Measured 2026-11-22 on {@code vsc-monopole-infinite-buses-2-blackbox}: parked in the frame itself, VSC1's EMF turned back
     * 0.0087 rad at the first call and its Q started 0.014 pu under the load flow's, 4.7e-3 off the white control at 0.2 s, still
     * settling - as upstream measured ATP's before it turned them; a step back, 1e-6. Starting its PLL a step on ({@code Theta0 +
     * w Dt}) changed nothing: the indices, not the DLL's frame, are late.
     */
    static void converterControl(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, String id, AveragedVscInitialState initial,
                                 String[] v, String[] i, double angle0, double omega, double timeStepSeconds) {
        BlackBoxModel<VoltageSourceConverter> model = BlackBoxModels.of(converter);
        double vbpk = initial.peakPhaseVoltageBase();
        double ibpk = initial.peakPhaseCurrentBase();
        double dcBaseVolts = initial.dcNominalVoltage() * 1.0e3;
        double vdc0 = initial.dcVoltage() / initial.dcNominalVoltage();
        String dc = id + "Vdc";
        devices.add(EmtpSynchronousMachines.function(dc, dc, List.of(id + "_VDP", id + "_VDR"), vdc0, "(u[1]-u[2])/" + number(dcBaseVolts)));
        // The load flow's phase EMFs, the bus's phasor and the reactor's drop: what the control starts on.
        double emfMagnitude = Math.hypot(initial.directEmf(), initial.quadratureEmf());
        double emfAngle = angle0 + Math.atan2(initial.quadratureEmf(), initial.directEmf());
        List<String> inputs = new ArrayList<>();
        for (BlackBoxSignal input : model.getInputs()) {
            String pin = id + input.port();
            if (input.isConstant()) {
                devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + pin + ";1;1;" + pin + ",", number(input.constant()) + ",S1,")));
                inputs.add(pin);
                continue;
            }
            int phase = switch (input.quantity()) {
                case TERMINAL_VOLTAGE_A_PU, CONVERTER_CURRENT_A_PU -> 0;
                case TERMINAL_VOLTAGE_B_PU, CONVERTER_CURRENT_B_PU -> 1;
                case TERMINAL_VOLTAGE_C_PU, CONVERTER_CURRENT_C_PU -> 2;
                case DC_VOLTAGE_PU -> -1;
                default -> throw new PowsyblException("SimuEmt Plus: converter '" + converter.getId() + "': input '" + input.port() + "' reads "
                        + input.quantity() + ", which EMTP does not feed a black-box control");
            };
            double offset = -2.0 * Math.PI / 3.0 * (phase == 2 ? -1 : phase);
            switch (input.quantity()) {
                case TERMINAL_VOLTAGE_A_PU, TERMINAL_VOLTAGE_B_PU, TERMINAL_VOLTAGE_C_PU -> devices.add(EmtpSynchronousMachines.function(pin, pin,
                        List.of(v[phase]), initial.terminalVoltage() * Math.cos(angle0 + offset), "u[1]/" + number(vbpk)));
                case CONVERTER_CURRENT_A_PU, CONVERTER_CURRENT_B_PU, CONVERTER_CURRENT_C_PU -> devices.add(EmtpSynchronousMachines.function(pin, pin,
                        List.of(i[phase]), Math.hypot(initial.directCurrent(), initial.quadratureCurrent())
                                * Math.cos(angle0 + Math.atan2(initial.quadratureCurrent(), initial.directCurrent()) + offset), "-u[1]/" + number(ibpk)));
                default -> pin = dc;
            }
            inputs.add(pin);
        }
        List<Double> initialValues = new ArrayList<>();
        for (BlackBoxInitialValue initialValue : model.getInitialValues()) {
            initialValues.add(switch (initialValue.quantity()) {
                case ACTIVE_POWER_PU -> initial.activePower();
                case REACTIVE_POWER_PU -> initial.reactivePower();
                case TERMINAL_VOLTAGE_MAGNITUDE_PU -> initial.terminalVoltage();
                case TERMINAL_VOLTAGE_ANGLE_RAD -> angle0;
                case DC_VOLTAGE_PU -> vdc0;
                case AC_VOLTAGE_BASE_PEAK_KV -> vbpk / 1.0e3;
                case DC_VOLTAGE_BASE_KV -> initial.dcNominalVoltage();
                case ACTIVE_POWER_CONTROL -> initial.activePowerControl() ? 1.0 : 0.0;
                case AC_VOLTAGE_CONTROL -> initial.acVoltageControl() ? 1.0 : 0.0;
                default -> throw new PowsyblException("SimuEmt Plus: converter '" + converter.getId() + "': no initial value of "
                        + initialValue.quantity() + " for a black-box control");
            });
        }
        String[] modulation = new String[3];
        List<String> outputs = new ArrayList<>();
        List<Double> initialOutputs = new ArrayList<>();
        for (BlackBoxSignal output : model.getOutputs()) {
            int phase = switch (output.quantity()) {
                case MODULATION_INDEX_A -> 0;
                case MODULATION_INDEX_B -> 1;
                case MODULATION_INDEX_C -> 2;
                default -> throw new PowsyblException("SimuEmt Plus: converter '" + converter.getId() + "': output '" + output.port() + "' gives "
                        + output.quantity() + ", which EMTP does not take from a black-box control");
            };
            modulation[phase] = id + "M" + "abc".charAt(phase);
            outputs.add(modulation[phase]);
            // m = e / (Vdc / 2), at the load flow.
            initialOutputs.add(2.0 * emfMagnitude * Math.cos(emfAngle - 2.0 * Math.PI / 3.0 * (phase == 2 ? -1 : phase)) / vdc0 * vbpk / dcBaseVolts);
        }
        devices.add(instance(id + "_BBX", model, outputs, initialOutputs, inputs, initialValues));

        // The frame, at the nominal frequency from the bus's angle at t = 0; the EMFs m Vdc / 2 per unit, and every quantity, parked in it.
        String theta = id + "_TH";
        devices.add(EmtpSynchronousMachines.function(theta, theta, List.of(id + "_ZERO"), angle0, number(angle0) + "+" + number(omega) + "*t+0*u[1]"));
        String before = id + "_THB";
        devices.add(EmtpSynchronousMachines.function(before, before, List.of(id + "_ZERO"), angle0, number(angle0) + "+" + number(omega) + "*(t-"
                + number(timeStepSeconds) + ")+0*u[1]"));
        String[] emf = new String[3];
        for (int k = 0; k < 3; k++) {
            emf[k] = id + "E" + "abc".charAt(k);
            devices.add(EmtpSynchronousMachines.function(emf[k], emf[k], List.of(modulation[k], dc), emfMagnitude * Math.cos(emfAngle - 2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k)),
                    "u[1]*u[2]*" + number(0.5 * dcBaseVolts / vbpk)));
        }
        devices.add(EmtpVoltageSourceConverters.park(id + "_ED", id + "_ED", emf, before, "COS", 1.0, initial.directEmf(), false));
        devices.add(EmtpVoltageSourceConverters.park(id + "_EQ", id + "_EQ", emf, before, "SIN", 1.0, initial.quadratureEmf(), true));
        devices.add(EmtpVoltageSourceConverters.park(id + "_ID", id + "_ID", i, theta, "COS", -ibpk, initial.directCurrent(), false));
        devices.add(EmtpVoltageSourceConverters.park(id + "_IQ", id + "_IQ", i, theta, "SIN", -ibpk, initial.quadratureCurrent(), true));
        devices.add(EmtpVoltageSourceConverters.park(id + "_VD", id + "_VD", v, theta, "COS", vbpk, initial.terminalVoltage(), false));
        devices.add(EmtpVoltageSourceConverters.park(id + "_VQ", id + "_VQ", v, theta, "SIN", vbpk, 0.0, true));
        List<String> dq = List.of(id + "_VD", id + "_VQ", id + "_ID", id + "_IQ");
        EmtpVoltageSourceConverters.measurement(devices, id + "_P", dq, "u[1]*u[3]+u[2]*u[4]", 0.0, initial.activePower());
        EmtpVoltageSourceConverters.measurement(devices, id + "_Q", dq, "u[2]*u[3]-u[1]*u[4]", 0.0, initial.reactivePower());
        EmtpVoltageSourceConverters.measurement(devices, id + "_VAC", dq.subList(0, 2), "SQRT(u[1]*u[1]+u[2]*u[2])", 0.0, initial.terminalVoltage());
        EmtpVoltageSourceConverters.measurement(devices, id + "_VDC", List.of(dc), "u[1]", 0.0, vdc0);
    }

    /**
     * The black-box machine of {@code generator}, on bus phase nodes {@code phaseNodes}, its terminal displaced by {@code offsetDegrees}, as
     * upstream's ATP builds it: per phase, its EMF - a controlled voltage source ({@code _cV}) to earth, as ATP's type-60 sources and
     * Kestrel's are - a converter's star point is isolated, a machine's stator earthed: from an isolated star, phase A of B2 faulted to earth,
     * B1's voltage was 43% off the white machine's -, its load-flow phasor {@code V + (R + jX) I} standing in for it in the steady state ({@code _Vsine}) - behind the model's
     * interface impedance, {@code R + jX} on the machine's rating, {@code 16L/Dt} across its inductance; the bus's voltage read by a
     * probe. Its DLL reads the bus's phase voltages and the current out of the machine - the source's, counted from its star point - per
     * unit of its peak phase bases, starts on the load flow ({@code P0}, {@code Q0}, {@code V0}, {@code Theta0} - phase a's voltage angle at
     * t = 0 as this backend's waveforms have it), and returns its EMF per unit.
     *
     * <p>The EMF is turned two steps on in alpha-beta: a step for the DLL's outputs, those of the step before ({@link #converterControl}),
     * and a step for the controlled source, which takes its control a step after it is computed - every EMF of this backend put a step
     * ahead. And its initial angle {@code Theta0} is given a step back, {@code angle - w Dt}: with no infinite bus on the bench, the
     * machine's own angle sets the network's. Measured 2026-11-22 against EMTP's own machine: turned one step, the bus 1.6e-3 off before
     * the fault, drifting to 2.8e-3; two, 4.6e-5 - and every bus 0.37 degree ahead of the load flow from the first period, a step's
     * angle, with {@code Theta0} the bus's angle, 0.73 with it a step on, within 0.006 with it a step back.
     */
    static List<EmtpSynchronousMachines.Device> machine(Generator generator, String[] phaseNodes, double offsetDegrees, EmtpSimulationSettings settings) {
        BlackBoxModel<Generator> model = BlackBoxModels.of(generator);
        com.powsybl.iidm.network.Bus bus = generator.getTerminal().getBusBreakerView().getBus();
        double nominalKv = bus.getVoltageLevel().getNominalV();
        double ratedMva = generator.getRatedS();
        double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
        double dt = settings.timeStepSeconds();
        double vbpk = nominalKv * 1.0e3 * Math.sqrt(2.0 / 3.0);
        double ibpk = ratedMva * 1.0e6 * Math.sqrt(2.0) / (Math.sqrt(3.0) * nominalKv * 1.0e3);
        double baseOhm = nominalKv * nominalKv / ratedMva;
        double r = model.getInterfaceResistance();
        double x = model.getInterfaceReactance();
        // The load flow's operating point, per unit of the machine's rating: V at its angle, I out of it, E = V + (R + jX) I.
        double angle = Math.toRadians(MachineInitialState.terminalAngleDegrees(generator, offsetDegrees));
        double v0 = bus.getV() / nominalKv;
        double[] i0 = MachineInitialState.terminalCurrent(generator, offsetDegrees);
        double vr = v0 * Math.cos(angle);
        double vi = v0 * Math.sin(angle);
        double er = vr + r * i0[0] - x * i0[1];
        double ei = vi + r * i0[1] + x * i0[0];
        double emf = Math.hypot(er, ei);
        double emfAngle = Math.atan2(ei, er);
        double current = Math.hypot(i0[0], i0[1]);
        double currentAngle = Math.atan2(i0[1], i0[0]);

        String id = EmtpVoltageSourceConverters.sanitize(generator.getId());
        String zero = id + "_ZERO";
        String star = "";
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + zero + ";1;1;" + zero + ",", "0,S1,")));
        double inductanceH = x * baseOhm / omega;
        String[] v = new String[3];
        String[] i = new String[3];
        String[] e = new String[3];
        for (int k = 0; k < 3; k++) {
            char phase = "abc".charAt(k);
            double offset = -2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k);
            String inner = id + "_K" + phase;
            String junction = id + "_J" + phase;
            String standIn = id + "_M" + phase;
            v[k] = id + "_V" + phase;
            i[k] = id + "_I" + phase;
            e[k] = id + "_EMF" + phase;
            devices.add(element("_RLC", id + "_R" + phase, List.of(inner, junction), number(r * baseOhm) + ",0,0,0,0,"));
            devices.add(element("_RLC", id + "_L" + phase, List.of(junction, phaseNodes[k]), "0," + number(inductanceH) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LD" + phase, List.of(junction, phaseNodes[k]),
                    number(NumericalAids.seriesInductanceDampingOhm(inductanceH, dt)) + ",0,0,0,0,"));
            // The EMF's stand-in, in the steady state only.
            devices.add(element("_Vsine", id + "_STD" + phase, List.of(inner, standIn), number(vbpk * emf) + "," + number(settings.nominalFrequencyHz()) + ","
                    + number(Math.toDegrees(emfAngle + offset)) + ",-1,1E-15,"));
            devices.add(element("_cV", id + "_CV" + phase, List.of(standIn, star, e[k], id + "_CVV" + phase, i[k], id + "_CVP" + phase),
                    "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            devices.add(element("_cI", id + "_PR" + phase, List.of(phaseNodes[k], "", zero, v[k], id + "_PRI" + phase, id + "_PRP" + phase),
                    "-1,1E15,0,0,0,0,>v,0,0,"));
        }
        // The DLL, on the bus's voltages and the machine's currents per unit.
        // Its signals out, but its EMFs - its speed, its field current, its field voltage at the load flow -, by quantity.
        java.util.Map<BlackBoxQuantity, String> signals = new java.util.EnumMap<>(BlackBoxQuantity.class);
        model.getOutputs().forEach(output -> signals.put(output.quantity(), id + output.port()));
        List<String> inputs = new ArrayList<>();
        for (BlackBoxSignal input : model.getInputs()) {
            String pin = id + input.port();
            if (input.isConstant()) {
                devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + pin + ";1;1;" + pin + ",", number(input.constant()) + ",S1,")));
                inputs.add(pin);
                continue;
            }
            if (input.quantity() == BlackBoxQuantity.FIELD_VOLTAGE_PU) {
                inputs.add(typedRegulators(devices, generator, id, v, v0, vbpk, signals, pin));
                continue;
            }
            int phase = switch (input.quantity()) {
                case TERMINAL_VOLTAGE_A_PU, MACHINE_CURRENT_A_PU -> 0;
                case TERMINAL_VOLTAGE_B_PU, MACHINE_CURRENT_B_PU -> 1;
                case TERMINAL_VOLTAGE_C_PU, MACHINE_CURRENT_C_PU -> 2;
                default -> throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': input '" + input.port() + "' reads "
                        + input.quantity() + ", which EMTP does not feed a black-box machine");
            };
            double offset = -2.0 * Math.PI / 3.0 * (phase == 2 ? -1 : phase);
            boolean voltage = input.quantity().name().startsWith("TERMINAL_VOLTAGE");
            devices.add(EmtpSynchronousMachines.function(pin, pin, List.of(voltage ? v[phase] : i[phase]),
                    voltage ? v0 * Math.cos(angle + offset) : current * Math.cos(currentAngle + offset), "u[1]/" + number(voltage ? vbpk : ibpk)));
            inputs.add(pin);
        }
        List<Double> initialValues = new ArrayList<>();
        for (BlackBoxInitialValue initialValue : model.getInitialValues()) {
            initialValues.add(switch (initialValue.quantity()) {
                case ACTIVE_POWER_PU -> vr * i0[0] + vi * i0[1];
                case REACTIVE_POWER_PU -> vi * i0[0] - vr * i0[1];
                case TERMINAL_VOLTAGE_MAGNITUDE_PU -> v0;
                // A step back: see above.
                case TERMINAL_VOLTAGE_ANGLE_RAD -> angle - omega * dt;
                default -> throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': no initial value of "
                        + initialValue.quantity() + " for a black-box machine");
            });
        }
        String[] outputs = new String[3];
        List<String> outputPins = new ArrayList<>();
        List<Double> initialOutputValues = new ArrayList<>();
        for (BlackBoxSignal output : model.getOutputs()) {
            outputPins.add(id + output.port());
            switch (output.quantity()) {
                case MACHINE_EMF_A_PU, MACHINE_EMF_B_PU, MACHINE_EMF_C_PU -> {
                    int phase = output.quantity().ordinal() - BlackBoxQuantity.MACHINE_EMF_A_PU.ordinal();
                    outputs[phase] = id + output.port();
                    initialOutputValues.add(emf * Math.cos(emfAngle - 2.0 * Math.PI / 3.0 * (phase == 2 ? -1 : phase)));
                }
                // At rest until its first call, its outputs those of the step before: its speed 1; its field current and its field voltage
                // at the load flow, which only the binary knows, 0 - the typed regulator on its field voltage at the load flow then gives
                // the DLL's first call 0 plus its own state, 0, a step's field voltage of Td0' (6.5 s): nothing measurable.
                case ROTOR_SPEED_PU -> initialOutputValues.add(1.0);
                case FIELD_CURRENT_PU, INITIAL_FIELD_VOLTAGE_PU -> initialOutputValues.add(0.0);
                default -> throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': output '" + output.port() + "' gives "
                        + output.quantity() + ", which EMTP does not take from a black-box machine");
            }
        }
        devices.add(instance(id + "_BBX", model, outputPins, initialOutputValues, inputs, initialValues));

        // The EMFs in volts, turned two steps on: e'_k = (2/3) sum_j e_j cos(phi - (k - j) 2 pi / 3).
        double turn = 2.0 * omega * dt;
        for (int k = 0; k < 3; k++) {
            StringBuilder expression = new StringBuilder("(" + number(2.0 / 3.0 * vbpk) + ")*(");
            for (int j = 0; j < 3; j++) {
                expression.append(j == 0 ? "" : "+").append("(").append(number(Math.cos(turn - (k - j) * 2.0 * Math.PI / 3.0))).append(")*u[").append(j + 1).append("]");
            }
            expression.append(")");
            double offset = -2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k);
            devices.add(EmtpSynchronousMachines.function(e[k], e[k], List.of(outputs), vbpk * emf * Math.cos(emfAngle + offset), expression.toString()));
        }
        return devices;
    }

    /**
     * The typed regulators beside black-box machine {@code id}, as upstream's Kestrel writes them, its field voltage on {@code efd}: the
     * terminal voltage {@code Vt}, its bus's phase voltages {@code v}'s amplitude in alpha-beta, per unit of {@code vbpk}; an IEEE PSS1A in
     * service on the speed the DLL gives; the simplified AVR {@code Ka/(1+sTa)} on {@code Vt0 - Vt + Vs} - a white machine's regulator
     * starts on {@code Vref = Vt0 + Efd0/Ka}, but only the DLL knows {@code Efd0}, and gives it at every step: its state {@code Efd0 + x},
     * {@code x} from 0, {@code dx/dt = (Ka (Vt0 - Vt + Vs) - x) / Ta}, stopped at the limits of {@code Efd0 + x}, the field voltage clamped
     * to them. Disabled, the field voltage is {@code Efd0}. Returns {@code efd}.
     */
    private static String typedRegulators(List<EmtpSynchronousMachines.Device> devices, Generator generator, String id, String[] v, double vt0,
                                          double vbpk, java.util.Map<BlackBoxQuantity, String> signals, String efd) {
        String efd0 = signals.get(BlackBoxQuantity.INITIAL_FIELD_VOLTAGE_PU);
        if (efd0 == null) {
            throw new PowsyblException("SimuEmt Plus: generator '" + generator.getId() + "': a black-box machine fed by a typed excitation must give the"
                    + " field voltage it needs at the load flow, INITIAL_FIELD_VOLTAGE_PU, for that excitation to start on");
        }
        String vt = id + "_VT";
        String alpha = "(2*u[1]-u[2]-u[3])/3";
        String beta = "(u[2]-u[3])/" + number(Math.sqrt(3.0));
        devices.add(EmtpSynchronousMachines.function(vt, vt, List.of(v), vt0, "SQRT((" + alpha + ")*(" + alpha + ")+(" + beta + ")*(" + beta + "))/"
                + number(vbpk)));
        String vs = null;
        String speed = signals.get(BlackBoxQuantity.ROTOR_SPEED_PU);
        if (speed != null && generator.getExtension(PssModel.class) instanceof Pss1AModel pss && pss.isEnabled()) {
            String deviation = id + "_DW";
            devices.add(EmtpVoltageSourceConverters.scoped(EmtpSynchronousMachines.function(deviation, deviation, List.of(speed), 0.0, "u[1]-1")));
            vs = id + "_VS";
            devices.addAll(EmtpSynchronousMachines.pss1A(pss, id, deviation, vs));
        }
        if (!(generator.getExtension(ExcitationSystemModel.class) instanceof SimplifiedAvrModel avr) || !avr.isEnabled() || avr.getGain() == 0.0) {
            devices.add(EmtpSynchronousMachines.function(efd, efd, List.of(efd0), 0.0, "u[1]"));
            return efd;
        }
        String x = id + "_AVX";
        String rate = id + "_AVD";
        String max = number(avr.getMaxOutput());
        String min = number(avr.getMinOutput());
        List<String> rateInputs = new ArrayList<>(List.of(vt, x, efd0));
        String error = number(vt0) + "-u[1]";
        if (vs != null) {
            rateInputs.add(vs);
            error += "+u[4]";
        }
        String derivative = "((" + number(avr.getGain()) + ")*(" + error + ")-u[2])/" + number(avr.getTimeConstant());
        // Stopped at a limit while driven past it.
        String atMax = EmtpVoltageSourceConverters.step("u[3]+u[2]-" + max) + "*" + EmtpVoltageSourceConverters.step(derivative);
        String atMin = EmtpVoltageSourceConverters.step(min + "-u[3]-u[2]") + "*" + EmtpVoltageSourceConverters.step("-(" + derivative + ")");
        devices.add(EmtpSynchronousMachines.function(rate, rate, rateInputs, 0.0, "(1-" + atMax + "-" + atMin + ")*" + derivative));
        devices.add(EmtpVoltageSourceConverters.integrator(x, x, rate, 1.0, 0.0));
        devices.add(EmtpVoltageSourceConverters.scoped(EmtpSynchronousMachines.function(efd, efd, List.of(efd0, x), 0.0,
                EmtpVoltageSourceConverters.clampBetween("u[1]+u[2]", min, max))));
        return efd;
    }

    /** An electrical device: its header - part, name, its pins twice, its nodes - and its data line. */
    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",",
                data));
    }

    /** {@code path} as the device reads one: its count of lines, then its lines of {@link EmtpNetlistWriter#MAX_LINE} characters. */
    static List<String> path(String path) {
        List<String> lines = new ArrayList<>();
        for (int start = 0; start < path.length(); start += EmtpNetlistWriter.MAX_LINE) {
            String line = path.substring(start, Math.min(path.length(), start + EmtpNetlistWriter.MAX_LINE));
            if (line.startsWith("_")) {
                // EMTP would read it as a device's first line.
                throw new PowsyblException("SimuEmt Plus: a path EMTP cannot read, a line of it starting with '_': " + path);
            }
            lines.add(line);
        }
        lines.add(0, Integer.toString(lines.size()));
        return lines;
    }
}
