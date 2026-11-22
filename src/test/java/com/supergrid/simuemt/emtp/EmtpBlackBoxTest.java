/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxModels;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's reference black boxes ({@code bf288b4a}) in EMTP, each run from its win32 DLL - the very binary ATP loads, the one Kestrel
 * and PSCAD load built for win32 - through EMTPWorks' {@code CIGRE_IEEE_DLL} device ({@link EmtpBlackBoxes}), against the white model
 * it was built from, written by this backend: what this checks is the black box's wiring - its pins, its parameters in the DLL's order,
 * its initial values from the load flow, its DLL copied beside the netlist.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpBlackBoxTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(TIME_STEP, 0.8, FREQUENCY);
    /** Upstream's solver tests' fault: phase A of B2, cleared at its current's zero. */
    private static final BusFault FAULT = new BusFault("FLTA", "B2", 'A', 0.3, 0.4, FaultClearing.AT_CURRENT_ZERO);

    /**
     * {@code simple_avr_win32.dll}'s device: the field voltage out, the terminal voltage's magnitude and the (disabled) PSS's output - a
     * constant 0 - in; the wrapper, the project folder and the DLL copied beside the netlist; the study's parameters then Vt0 and Efd0,
     * from the load flow.
     */
    @Test
    void writesTheExcitationsDllDevice(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxAvr());
        Generator generator = network.getGenerator("GEN");
        MachineInitialState initial = MachineInitialState.subtransient(generator, (SubtransientMachineModel) generator.getExtension(
                com.powsybl.simuemt.extensions.GeneratorModel.class), 0.0);
        Path netlist = EmtpNetlistWriter.write(network, tempDir, SETTINGS);
        List<String> lines = Files.readAllLines(netlist, StandardCharsets.ISO_8859_1);

        assertThat(tempDir.resolve("simple_avr_win32.dll")).as("the DLL copied beside the netlist").exists();
        int device = lines.indexOf("_cm_dll;GEN_BBX;3;3;GEN_Vf,GEN_VT,GEN_BBX_Vs,");
        assertThat(device).as("the DLL's device").isNotNegative();
        assertThat(lines).contains("_c_cst;GEN_BBX_Vs;1;1;GEN_BBX_Vs,", "_c_fu;GEN_VT;3;3;GEN_VT,GEN_vd,GEN_vq,");
        assertThat(lines.subList(device + 1, device + 4)).containsExactly("1,2,3,S0,", "wrapper_1_1_0_0_x86.dll,", "0");
        List<String> data = lines.subList(device + 4, lines.size());
        String project = tempDir.toAbsolutePath().resolve("two_bus_pj") + "\\";
        String dll = tempDir.toAbsolutePath().resolve("simple_avr_win32.dll").toString();
        assertThat(String.join("", data.subList(1, 1 + Integer.parseInt(data.get(0))))).isEqualTo(project);
        List<String> rest = data.subList(1 + Integer.parseInt(data.get(0)), data.size());
        assertThat(String.join("", rest.subList(1, 1 + Integer.parseInt(rest.get(0))))).isEqualTo(dll);
        List<String> options = rest.subList(1 + Integer.parseInt(rest.get(0)), rest.size());
        // Ten options, nine of snapshot and autoinitialisation, two inputs not accumulated, no initial-output pins, Efd0, then the seven
        // parameters and their seven "variable" flags.
        assertThat(options.subList(0, 19)).containsExactly("1", "0", "1", "1", "1", "1", "1", "0", "0", "1", "0", "1.0", "1", ".", "0", "0", "1", "1", ".");
        assertThat(options.subList(19, 22)).containsExactly("0", "0", "0");
        double efd0 = Double.parseDouble(options.get(22));
        assertThat(efd0).isCloseTo(initial.efd(), within(1e-12));
        assertThat(options.subList(23, 28).stream().map(Double::parseDouble).toList()).containsExactly(200.0, 0.02, -5.0, 5.0, 0.02);
        assertThat(Double.parseDouble(options.get(28))).isCloseTo(initial.terminalVoltage(), within(1e-12));
        assertThat(Double.parseDouble(options.get(29))).isEqualTo(efd0);
        assertThat(options.subList(30, 37)).containsOnly("0");
        assertThat(options).as("the device's data, to its last line").hasSize(37);
    }

    @Test
    void cutsAPathInLinesOfAHundredCharacters() {
        String path = "C:\\" + "a".repeat(150) + "\\simple_avr_win32.dll";
        List<String> lines = EmtpBlackBoxes.path(path);

        assertThat(lines.get(0)).isEqualTo("2");
        assertThat(lines.get(1)).hasSize(100);
        assertThat(lines.get(1) + lines.get(2)).isEqualTo(path);
        assertThatThrownBy(() -> EmtpBlackBoxes.path("C:\\" + "a".repeat(97) + "_x.dll")).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("starting with '_'");
    }

    /** A black-box converter control and a black-box machine, refused by name; a DLL sampled off the run's step, refused as on every
     * backend. */
    @Test
    void refusesWhatItDoesNotWriteYet(@TempDir Path tempDir) {
        assertThat(EmtpBlackBoxes.unsupportedReasons(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachine())))
                .contains("generator 'GEN': a black-box machine is not written yet");
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxAvr());
        assertThatThrownBy(() -> EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(10.0e-6, 0.1, FREQUENCY)))
                .isInstanceOf(PowsyblException.class).hasMessageContaining("is sampled every 2.0E-5 s, the run's time step is 1.0E-5 s");
        assertThat(BlackBoxModels.artifactFor(BlackBoxModels.of(network.getGenerator("GEN")), EmtpBlackBoxes.BACKEND,
                EmtpBlackBoxes.DLL_PLATFORM)).hasValueSatisfying(dll -> assertThat(dll.platform()).isEqualTo("win32"));
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxAvrRunsAsItsWhiteModel} in EMTP: the two-bus bench's simplified AVR
     * as this backend writes it ({@code _c_fslim}, continuous) and as {@code simple_avr_win32.dll} - the same regulator by the
     * trapezoidal rule inside the DLL, released at 20 ms -, phase A of B2 faulted from 0.3 to 0.4 s; the bus voltages held to each other
     * as Kestrel holds them, 2e-4, the black box's field voltage at its ceiling in the fault.
     *
     * <p>Run 2026-11-22: 5e-8 before the fault, 4.7e-6 in it, 1.3e-6 after - closer than Kestrel's 6e-7, 6e-6 and 7.5e-5, the DLL fed the
     * step's own inputs where Kestrel feeds it a step late.
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxAvrRunsAsItsWhiteModel() {
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxAvr()),
                Path.of("target", "emtp", "black-box-avr"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithEmtExtensions()),
                Path.of("target", "emtp", "black-box-avr-white"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.20, 0.22}, {0.35, 0.37}, {0.60, 0.62}, {0.76, 0.78}}) {
            for (String[] phase : new String[][] {{"B1", "A"}, {"B1", "B"}, {"B2", "A"}}) {
                String scope = EmtpNetlistWriter.voltageScope(phase[0], phase[1].charAt(0));
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                System.out.printf(Locale.ROOT, "%s%s %.2f-%.2f s: black box %+.2e off white%n", phase[0], phase[1], window[0], window[1],
                        actual / expected - 1.0);
                softly.assertThat(actual).as("%s%s, %s-%s s", phase[0], phase[1], window[0], window[1]).isCloseTo(expected, within(2e-4 * expected));
            }
            System.out.printf(Locale.ROOT, "Efd %.2f-%.2f s: %.6f%n", window[0], window[1], blackBox.mean("control:GEN_EFD", window[0], window[1]));
        }
        softly.assertThat(blackBox.mean("control:GEN_EFD", 0.35, 0.37)).as("the field voltage at its ceiling in the fault").isCloseTo(5.0, within(1e-9));
        softly.assertAll();
    }
}
