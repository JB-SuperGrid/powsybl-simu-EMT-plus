/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.blackbox.BlackBoxModels;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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

    /** A black-box machine beside typed regulators, taken as upstream's Kestrel takes them, from its field-port DLL's win32 build; a DLL
     * sampled off the run's step, refused as on every backend. */
    @Test
    void refusesWhatItDoesNotWriteYet(@TempDir Path tempDir) {
        assertThat(EmtpBlackBoxes.unsupportedReasons(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachine()))).isEmpty();
        assertThat(EmtpBlackBoxes.unsupportedReasons(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachineAndTypedRegulators())))
                .isEmpty();
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

    /** Example 2's VSC1 under the core of its grid-following control, white ({@code -gfl}) or as {@code gfl_vsc_win32.dll} ({@code
     * -blackbox}). */
    private static Network vscBench(String variant) {
        Network network = ExampleNetworks.readVscBench("vsc-monopole-infinite-buses-2-" + variant, EmtExtensionsXmlReader::readNetwork);
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** The converter's DLL device: its three modulation indices out, the bus's phase voltages, its phase currents and its DC voltage in. */
    @Test
    void writesTheConverterControlsDllDevice(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(vscBench("blackbox"), tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(tempDir.resolve("gfl_vsc_win32.dll")).as("the DLL copied beside the netlist").exists();
        int device = lines.indexOf("_cm_dll;VSC1_BBX;10;10;VSC1Ma,VSC1Mb,VSC1Mc,VSC1Va,VSC1Vb,VSC1Vc,VSC1Ia,VSC1Ib,VSC1Ic,VSC1Vdc,");
        assertThat(device).as("the DLL's device").isNotNegative();
        assertThat(lines.subList(device + 1, device + 3)).containsExactly("3,7,3,S0,", "wrapper_1_1_0_0_x86.dll,");
        // A device's pins past EMTP's 100 characters go on after a pin's comma.
        assertThat(EmtpBlackBoxes.header("_cm_dll;VSC12_BBX;10;10;", List.of("VSC12Ma", "VSC12Mb", "VSC12Mc", "VSC12Va", "VSC12Vb", "VSC12Vc",
                "VSC12Ia", "VSC12Ib", "VSC12Ic", "VSC12Vdc"))).containsExactly(
                "_cm_dll;VSC12_BBX;10;10;VSC12Ma,VSC12Mb,VSC12Mc,VSC12Va,VSC12Vb,VSC12Vc,VSC12Ia,VSC12Ib,VSC12Ic,", "VSC12Vdc,");
        assertThatThrownBy(() -> EmtpNetlistWriter.write(vscBench("blackbox"), tempDir, SETTINGS, List.of(new ConverterBlocking("VSC1", 0.3))))
                .isInstanceOf(PowsyblException.class).hasMessageContaining("its control is a black box");
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxConverterControlRunsAsItsWhiteModel} in EMTP: example 2's VSC1 under
     * the core of its grid-following control as this backend writes it and as {@code gfl_vsc_win32.dll}, phase A of B1 faulted from 0.3 to
     * 0.35 s; held to each other as Kestrel holds them - the bus voltages within 2e-4, the powers and DC voltages 3e-3, the black box's P
     * and Q unfiltered, the white control's through its 1 ms lag -, but the bus voltages in the fault within 3e-4: the DLL's indices,
     * turned a step on in alpha-beta ({@link EmtpBlackBoxes#converterControl}), turn the fault's negative sequence the wrong way, as
     * upstream's ATP, PSCAD and Xyce turn it (Xyce 2.5e-4).
     *
     * <p>Run 2026-11-22: the bus voltages 1.5e-8 before the fault, 2.6e-4 in it, 2.4e-6 just after, 3e-7 at 0.77 s; VSC1's P and Q 1e-6
     * before, 2.5e-3 in the fault, 1.3e-5 at 0.77 s.
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxConverterControlRunsAsItsWhiteModel() {
        BusFault fault = new BusFault("FLTA", "B1", 'A', 0.3, 0.35, FaultClearing.AT_CURRENT_ZERO);
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(vscBench("blackbox"), Path.of("target", "emtp", "black-box-gfl"), SETTINGS,
                List.of(fault)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(vscBench("gfl"), Path.of("target", "emtp", "black-box-gfl-white"), SETTINGS,
                List.of(fault)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.20, 0.22}, {0.32, 0.34}, {0.45, 0.47}, {0.76, 0.78}}) {
            for (String[] phase : new String[][] {{"B1", "A"}, {"B1", "B"}, {"B2", "A"}}) {
                String scope = EmtpNetlistWriter.voltageScope(phase[0], phase[1].charAt(0));
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                System.out.printf(Locale.ROOT, "%s%s %.2f-%.2f s: black box %+.2e off white%n", phase[0], phase[1], window[0], window[1],
                        actual / expected - 1.0);
                double tolerance = window[0] > 0.3 && window[1] < 0.35 ? 3e-4 : 2e-4;
                softly.assertThat(actual).as("%s%s, %s-%s s", phase[0], phase[1], window[0], window[1]).isCloseTo(expected, within(tolerance * expected));
            }
            for (String signal : List.of("VSC1_P", "VSC1_Q", "VSC1_VDC", "VSC2_P", "VSC2_VDC")) {
                double expected = white.mean("control:" + signal, window[0], window[1]);
                double actual = blackBox.mean("control:" + signal, window[0], window[1]);
                System.out.printf(Locale.ROOT, "%s %.2f-%.2f s: white %.6f, black box %+.2e%n", signal, window[0], window[1], expected, actual - expected);
                softly.assertThat(actual).as("%s, %s-%s s", signal, window[0], window[1]).isCloseTo(expected, within(3e-3));
            }
        }
        softly.assertAll();
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxMachineRunsAsItsWhiteModel} in EMTP: the two-bus bench's held
     * subtransient machine as this backend writes it - EMTP's own machine, in the exact definitions - and as {@code sub_machine_win32.dll},
     * the same machine in the classical ones behind the voltage this backend builds, phase A of B2 faulted from 0.3 to 0.4 s; the bus
     * voltages within 2.5e-4, as upstream's ATP holds its black box to its own machine (2.4e-4 in the fault) - not the same equations
     * written twice, as Kestrel's and Xyce's are.
     *
     * <p>Run 2026-11-22: 5.6e-5 in the first period, 4.2e-5 before the fault, 1.9e-4 in it, 1e-4 at worst after it; the angles within
     * 0.006 degree before the fault, 0.03 after it, held within 0.05.
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxMachineRunsAsItsWhiteModel() {
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachine()),
                Path.of("target", "emtp", "black-box-machine"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithHeldSubtransientMachine()),
                Path.of("target", "emtp", "black-box-machine-white"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.0, 0.02}, {0.20, 0.22}, {0.32, 0.34}, {0.45, 0.47}, {0.60, 0.62}, {0.76, 0.78}}) {
            for (String[] phase : new String[][] {{"B1", "A"}, {"B1", "B"}, {"B2", "A"}}) {
                String scope = EmtpNetlistWriter.voltageScope(phase[0], phase[1].charAt(0));
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                System.out.printf(Locale.ROOT, "%s%s %.2f-%.2f s: black box %+.2e off white%n", phase[0], phase[1], window[0], window[1],
                        actual / expected - 1.0);
                softly.assertThat(actual).as("%s%s, %s-%s s", phase[0], phase[1], window[0], window[1]).isCloseTo(expected, within(MACHINE_TOLERANCE * expected));
                double angleOff = Math.toDegrees(blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).angleRadians()
                        - white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).angleRadians());
                System.out.printf(Locale.ROOT, "%s%s %.2f-%.2f s: black box %+.4f degree off white%n", phase[0], phase[1], window[0], window[1], angleOff);
                softly.assertThat(angleOff).as("%s%s, %s-%s s, degrees", phase[0], phase[1], window[0], window[1]).isCloseTo(0.0, within(0.05));
            }
        }
        softly.assertAll();
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxMachineRunsUnderTypedRegulatorsAsItsWhiteModel} in EMTP: the two-bus
     * bench's subtransient machine under its simplified AVR and PSS1A as this backend writes them, and as {@code sub_machine_field_win32.dll}
     * under the same regulators written around it ({@link EmtpBlackBoxes}), phase A of B2 faulted from 0.3 to 0.4 s: the bus voltages
     * within 3e-4, the angles 0.05 degree - EMTP's machine in the exact definitions, the DLL's in the classical ones.
     *
     * <p>Run 2026-11-22: 6e-5 in the first period, 9e-7 before the fault, 2.2e-4 at worst in it, 7.4e-5 after; the angles within 0.013
     * degree; the black box's field voltage at its ceiling, 5, from the fault to 0.62 s.
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxMachineRunsUnderTypedRegulatorsAsItsWhiteModel() {
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(
                ExampleNetworks.createTwoBusNetworkWithBlackBoxMachineAndTypedRegulators()), Path.of("target", "emtp", "black-box-machine-regulated"), SETTINGS,
                List.of(FAULT)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithRegulatedSubtransientMachine()),
                Path.of("target", "emtp", "black-box-machine-regulated-white"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.0, 0.02}, {0.20, 0.22}, {0.32, 0.34}, {0.45, 0.47}, {0.60, 0.62}, {0.76, 0.78}}) {
            for (String[] phase : new String[][] {{"B1", "A"}, {"B1", "B"}, {"B2", "A"}}) {
                String scope = EmtpNetlistWriter.voltageScope(phase[0], phase[1].charAt(0));
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double angleOff = Math.toDegrees(blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).angleRadians()
                        - white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).angleRadians());
                System.out.printf(Locale.ROOT, "regulated %s%s %.2f-%.2f s: black box %+.2e, %+.4f degree off white%n", phase[0], phase[1], window[0], window[1],
                        actual / expected - 1.0, angleOff);
                softly.assertThat(actual).as("%s%s, %s-%s s", phase[0], phase[1], window[0], window[1]).isCloseTo(expected, within(REGULATED_TOLERANCE * expected));
                softly.assertThat(angleOff).as("%s%s, %s-%s s, degrees", phase[0], phase[1], window[0], window[1]).isCloseTo(0.0, within(0.05));
            }
            System.out.printf(Locale.ROOT, "regulated Efd %.2f-%.2f s: black box %.5f%n", window[0], window[1], blackBox.mean("control:GENEfd", window[0], window[1]));
        }
        softly.assertAll();
    }

    private static final double MACHINE_TOLERANCE = 2.5e-4;
    private static final double REGULATED_TOLERANCE = 3e-4;

    /**
     * The black boxes on the rotor's speed ({@code 4295f31d}): {@code tgov1_win32.dll} on the machine's observed speed, its power driving
     * the mass's ({@code Pm_1}), Pm0 its one initial value; {@code pss1a_win32.dll} on the speed, its output the typed AVR's stabilizing
     * input; {@code avr_pss1a_win32.dll} on the terminal voltage and the speed, the field voltage and the stabilizer's output out.
     */
    @Test
    void writesTheDllDevicesReadingTheSpeed(@TempDir Path tempDir) throws IOException {
        Network governed = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxGovernor());
        List<String> governor = Files.readAllLines(EmtpNetlistWriter.write(governed, tempDir.resolve("governor"), SETTINGS), StandardCharsets.ISO_8859_1);
        assertThat(tempDir.resolve("governor").resolve("tgov1_win32.dll")).exists();
        int device = governor.indexOf("_cm_dll;GEN_BBX;2;2;GEN_BBPM,GEN_Omega_1,");
        assertThat(device).as("the governor's device").isNotNegative();
        assertThat(governor.get(device + 1)).isEqualTo("1,1,3,S0,");
        assertThat(governor).contains("_c_fu;GEN_PM;2;2;GEN_Pm_1,GEN_BBPM,").anyMatch(line -> line.startsWith("_SM;GENa;") && line.contains("GEN_Pm_1,GEN_Vf,"));
        double pm0 = MachineInitialState.subtransient(governed.getGenerator("GEN"), (SubtransientMachineModel) governed.getGenerator("GEN")
                .getExtension(com.powsybl.simuemt.extensions.GeneratorModel.class), 0.0).mechanicalPower();
        // Past the two paths, the 19 options, the input's accumulation flag and the initial-output pins' flag: its initial output, its eight
        // parameters, then Pm0, then nine "variable" flags.
        List<String> data = governor.subList(device + 4, governor.size());
        List<String> rest = data.subList(1 + Integer.parseInt(data.get(0)), data.size());
        List<String> options = rest.subList(1 + Integer.parseInt(rest.get(0)), rest.size());
        assertThat(options.subList(19, 21)).containsExactly("0", "0");
        assertThat(Double.parseDouble(options.get(21))).isCloseTo(pm0, within(1e-12));
        assertThat(options.subList(22, 30).stream().map(Double::parseDouble).toList()).containsExactly(0.05, 0.5, 0.0, 1.1, 2.0, 10.0, 0.0, 0.02);
        assertThat(Double.parseDouble(options.get(30))).isCloseTo(pm0, within(1e-12));
        assertThat(options.subList(31, 40)).containsOnly("0");

        List<String> pss = Files.readAllLines(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxPss()),
                tempDir.resolve("pss"), SETTINGS), StandardCharsets.ISO_8859_1);
        assertThat(tempDir.resolve("pss").resolve("pss1a_win32.dll")).exists();
        assertThat(pss).contains("_cm_dll;GEN_BBX;2;2;GEN_VS,GEN_Omega_1,", "_c_fu;GEN_VERR;4;4;GEN_VERR,GEN_vd,GEN_vq,GEN_VS,")
                .noneMatch(line -> line.startsWith("_c_fs;GEN_XW;"));

        List<String> avrPss = Files.readAllLines(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxAvrAndPss()),
                tempDir.resolve("avr-pss"), SETTINGS), StandardCharsets.ISO_8859_1);
        assertThat(tempDir.resolve("avr-pss").resolve("avr_pss1a_win32.dll")).exists();
        int avr = avrPss.indexOf("_cm_dll;GEN_BBX;4;4;GEN_Vf,GEN_VS,GEN_VT,GEN_Omega_1,");
        assertThat(avr).as("the excitation's device").isNotNegative();
        assertThat(avrPss.get(avr + 1)).isEqualTo("2,2,3,S0,");
        assertThat(avrPss).contains("_c_fu;GEN_P1VS;2;2;GEN_P1VS,GEN_VS,").anyMatch(line -> line.startsWith("_SM;GENa;") && line.contains("GEN_Omega_1,"));
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxPssRunsAsItsWhiteModel} in EMTP: the regulated machine's PSS1A as this
     * backend writes it and as {@code pss1a_win32.dll} on the same simplified AVR, phase A of B2 faulted from 0.3 to 0.4 s; Vs and the bus
     * voltages held as Kestrel holds them, 1e-4 and 5e-6.
     *
     * <p>Run 2026-11-25, Vs up to 0.088: Vs within 7.2e-7, the bus voltages 6.5e-7 - the DLL fed the step's own speed, where Kestrel feeds
     * it a step late (4.8e-5).
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxPssRunsAsItsWhiteModel() {
        compareRegulated("black-box-pss", ExampleNetworks.createTwoBusNetworkWithBlackBoxPss(), 1e-4, 5e-6);
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxAvrWithItsPssRunsAsItsWhiteModels} in EMTP: the regulated machine's
     * simplified AVR and PSS1A as this backend writes them and as {@code avr_pss1a_win32.dll}, phase A of B2 faulted from 0.3 to 0.4 s; Vs
     * and the bus voltages held as Kestrel holds them, 1.3e-4 and 6e-5.
     *
     * <p>Run 2026-11-25: Vs within 3.1e-6, the bus voltages 3.4e-6 (Kestrel 6.6e-5 and 2.9e-5).
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxAvrWithItsPssRunsAsItsWhiteModels() {
        compareRegulated("black-box-avr-pss", ExampleNetworks.createTwoBusNetworkWithBlackBoxAvrAndPss(), 1.3e-4, 6e-5);
    }

    private static void compareRegulated(String name, Network blackBoxNetwork, double vsTolerance, double voltageTolerance) {
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(blackBoxNetwork), Path.of("target", "emtp", name), SETTINGS,
                List.of(FAULT)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithRegulatedSubtransientMachine()),
                Path.of("target", "emtp", name + "-white"), SETTINGS, List.of(FAULT)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.20, 0.22}, {0.32, 0.34}, {0.45, 0.47}, {0.60, 0.62}, {0.76, 0.78}}) {
            double vs = white.mean("control:GEN_P1VS", window[0], window[1]);
            double actualVs = blackBox.mean("control:GEN_P1VS", window[0], window[1]);
            System.out.printf(Locale.ROOT, "%s Vs %.2f-%.2f s: white %+.6f, black box %+.2e%n", name, window[0], window[1], vs, actualVs - vs);
            softly.assertThat(actualVs).as("Vs, %s-%s s", window[0], window[1]).isCloseTo(vs, within(vsTolerance));
            for (String[] phase : new String[][] {{"B1", "A"}, {"B1", "B"}, {"B2", "A"}}) {
                String scope = EmtpNetlistWriter.voltageScope(phase[0], phase[1].charAt(0));
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                System.out.printf(Locale.ROOT, "%s %s%s %.2f-%.2f s: black box %+.2e off white%n", name, phase[0], phase[1], window[0], window[1],
                        actual / expected - 1.0);
                softly.assertThat(actual).as("%s%s, %s-%s s", phase[0], phase[1], window[0], window[1]).isCloseTo(expected, within(voltageTolerance * expected));
            }
        }
        softly.assertAll();
    }

    /**
     * Upstream's {@code KestrelBlackBoxSolverTest#theReferenceBlackBoxGovernorRunsAsItsWhiteModel} in EMTP, but through a fault - EMTP
     * steps no load -: the islanded machine's TGOV1 as this backend writes it and as {@code tgov1_win32.dll}, phase A of B2 faulted from
     * 0.3 to 0.4 s, run 3 s; the power, the speed and the bus voltages held as Kestrel holds them, 2e-6, 2.5e-7 and 6e-6.
     *
     * <p>Run 2026-11-25, the speed up to 6.2e-3 pu, the power from 1.006 to 0.984 pu: the power within 7.8e-9, the speed 3.8e-10, the bus
     * voltages 4.3e-10 - both on the same Pm0 and the same machine, the DLL fed the step's own speed.
     */
    @Test
    @Tag("emtp")
    void theReferenceBlackBoxGovernorRunsAsItsWhiteModel() {
        EmtpSimulationSettings settings = new EmtpSimulationSettings(TIME_STEP, 3.0, FREQUENCY);
        EmtpOutput blackBox = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxGovernor()),
                Path.of("target", "emtp", "black-box-governor"), settings, List.of(FAULT)), EmtpSettings.defaults()).output();
        EmtpOutput white = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithTgov1()),
                Path.of("target", "emtp", "black-box-governor-white"), settings, List.of(FAULT)), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{0.20, 0.22}, {0.32, 0.34}, {0.45, 0.47}, {1.00, 1.02}, {2.00, 2.02}, {2.96, 2.98}}) {
            double pm = white.mean("control:GEN_PM", window[0], window[1]);
            double dw = white.mean("control:GEN_DW", window[0], window[1]);
            double actualPm = blackBox.mean("control:GEN_PM", window[0], window[1]);
            double actualDw = blackBox.mean("control:GEN_DW", window[0], window[1]);
            System.out.printf(Locale.ROOT, "governor %.2f-%.2f s: white Pm %.6f Dw %+.3e, black box %+.2e %+.2e%n", window[0], window[1], pm, dw, actualPm - pm,
                    actualDw - dw);
            softly.assertThat(actualPm).as("Pm, %s-%s s", window[0], window[1]).isCloseTo(pm, within(2e-6));
            softly.assertThat(actualDw).as("Dw, %s-%s s", window[0], window[1]).isCloseTo(dw, within(2.5e-7));
            for (String bus : List.of("B1", "B2")) {
                String scope = EmtpNetlistWriter.voltageScope(bus, 'A');
                double expected = white.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                double actual = blackBox.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
                System.out.printf(Locale.ROOT, "governor %sA %.2f-%.2f s: black box %+.2e off white%n", bus, window[0], window[1], actual / expected - 1.0);
                softly.assertThat(actual).as("%sA, %s-%s s", bus, window[0], window[1]).isCloseTo(expected, within(6e-6 * expected));
            }
        }
        softly.assertAll();
    }
}
