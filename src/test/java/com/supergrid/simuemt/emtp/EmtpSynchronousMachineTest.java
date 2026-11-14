/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
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
import com.powsybl.simuemt.extensions.excitation.St4BInitialState;
import com.powsybl.simuemt.extensions.excitation.St4BModel;
import com.powsybl.simuemt.extensions.excitation.St5BInitialState;
import com.powsybl.simuemt.extensions.excitation.St5BModel;
import com.powsybl.simuemt.extensions.excitation.St6BInitialState;
import com.powsybl.simuemt.extensions.excitation.St6BModel;
import com.powsybl.simuemt.extensions.governor.GoverProportionalModel;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.extensions.output.AbstractTimeStepTable;
import com.powsybl.simuemt.extensions.output.Crest;
import com.powsybl.simuemt.extensions.pss.Pss1AModel;
import com.powsybl.simuemt.extensions.pss.PssModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's two-bus bench ({@code two-bus.xiidm}, {@code two-bus-extensions.xml}): its machine GEN - 100 MVA on 400 kV, Xd 2.0, Xq
 * 1.9, Xd' 0.28, T'd0 6.5 s, Xq' 0.35, T'q0 1.0 s, Xd'' = Xq'' 0.20, T''d0 0.035 s, T''q0 0.05 s, Xl 0.15, Ra 0.0025, H 5.5 s, D 1.8,
 * 1000 A on the air-gap line, 2 poles - through the 4 + j40 ohm line to B2's load, its voltage regulator and stabilizer disabled,
 * which holds its field. Written as EMTP's machine of the circuit its data describe under the exact definitions, and - tagged {@code
 * emtp} - run live: on the load flow, then shorted at the line's far end, unloaded against the short-circuit current those data give,
 * loaded against Park's equations integrated here ({@link ParkShortCircuitReference}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpSynchronousMachineTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(TIME_STEP, 0.2, FREQUENCY);

    /**
     * Each axis's rotor as the exact definitions give it - the d axis's field leakage 0.1507 pu and resistance 3.222e-1 pu/s, its
     * damper's 0.0780 pu and 5.933 pu/s; the q axis's 0.3891 pu and 2.992 pu/s, 0.0593 pu and 5.399 pu/s -, every resistance over
     * 100 pi rad/s, every mutual reactance the axis's magnetizing one. EMTP's machine report of this machine reads back each winding
     * of both axes, in ohms on the stator side, within 1e-6 (run 2026-11-13).
     */
    @Test
    void writesTheExactCircuitOfTheMachinesData(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(loadedMachineWithFieldHeld(), tempDir, SETTINGS), StandardCharsets.ISO_8859_1);
        int start = lines.indexOf("_SM;GENa;5;3;B1a,GEN_Vf,,");

        assertThat(start).as("the machine's phase a").isNotNegative();
        assertThat(lines.subList(start + 1, start + 3)).containsExactly("_SM;GENb;5;3;B1b,GEN_Vf,,", "_SM;GENc;5;3;B1c,GEN_Vf,,");
        assertThat(lines.get(start + 3)).startsWith("V=400.0000000000000 ").endsWith(",1kVRMSLL,");
        assertThat(lines.get(start + 4)).isEqualTo("P=0.000000000000000 -120.0000000000000 120.0000000000000,1,");
        assertThat(lines.get(start + 5)).isEqualTo("f=50.00000000000000 2 0 0 50.00000000000000,");
        assertThat(lines.get(start + 6)).isEqualTo("R=400.0000000000000 100.0000000000000 1000.000000000000 0 0 0,");
        assertValues(lines.get(start + 8), "Ra=", 0.0025, 0.15, 0.20);
        assertValues(lines.get(start + 9), "ds=", 3, 2, 2.0);
        assertValues(lines.get(start + 10), "Rf=", 0.001025515304441671, 2.000714254224947, 1.85);
        assertValues(lines.get(start + 11), "RD1=", 0.01888409366771252, 1.927976436715163, 1.85, 1.85);
        assertValues(lines.get(start + 12), "qs=", 3, 2, 1.9);
        assertValues(lines.get(start + 13), "RQ1=", 0.009524291693139402, 2.139081399466541, 1.75);
        assertValues(lines.get(start + 14), "RQ2=", 0.01718657476390697, 1.809317556890195, 1.75, 1.75);
        // H 5.5 s; DSR = D S / wm^2, 1.8 * 100 MVA / (100 pi rad/s)^2.
        assertThat(lines.get(start + 15)).isEqualTo("MS=0 1 1 0,");
        assertValues(lines.get(start + 16), "", 100, 5.5, 1.8e8 / Math.pow(100.0 * Math.PI, 2), 0, 0, 0);
        assertThat(lines.get(start + 19)).isEqualTo("PR=100 1e-06 300 0 1e-06,");
        // Its field voltage held: controlled, per unit, by a constant at the load flow's, Ef = 2.4769 pu - Eq' + (Xd - Xd') Id.
        assertThat(lines.subList(start + 20, start + 22)).containsExactly("CTRLu=1,", "CTRL=0 0 0 1 0,");
        int constant = lines.indexOf("_c_cst;GEN_VF;1;1;GEN_Vf,");
        assertThat(constant).as("the field voltage's constant").isNotNegative();
        assertThat(lines.get(constant + 1)).endsWith(",S1,");
        assertThat(Double.parseDouble(lines.get(constant + 1).split(",")[0])).isCloseTo(2.4769149, within(1.0e-6));
    }

    @Test
    void refusesTheMachineWhileAGovernorItDoesNotWriteIsEnabled(@TempDir Path tempDir) {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        network.getGenerator("GEN").addExtension(TurbineGovernorModel.class, new GoverProportionalModel(true, 20.0, 120.0, 0.0, 100.0));

        assertThatThrownBy(() -> EmtpNetlistWriter.write(network, tempDir, SETTINGS))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("generator 'GEN'")
                .hasMessageContaining("GoverProportionalModel is not written yet")
                .hasMessageNotContaining("SimplifiedAvrModel")
                .hasMessageNotContaining("Pss1AModel");
    }

    /**
     * A TGOV1 governor - droop 0.05, T1 0.2 s, valve within 0 and 1.2, T2/T3 0.3/1.0 s, Dt 0.5 - on the loaded machine, its field held:
     * its mass's power control signal before the field voltage's, the speed observed for its deviation; its demand {@code Pm0 -
     * dw / R}, its valve, its turbine, {@code Pm = turbine - Dt dw}, each at the load flow's mechanical power.
     */
    @Test
    void writesTheGovernorAsAtpsTacsHasIt(@TempDir Path tempDir) throws IOException {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(TurbineGovernorModel.class, new Tgov1Model(true, 0.05, 0.2, 0.0, 1.2, 0.3, 1.0, 0.5));
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_SM;GENa;7;5;B1a,GEN_Pm_1,GEN_Vf,GEN_Omega_1,,", "CTRL=0 0 0 1 0,", "0 1 0 0 4", "_c_fu;GEN_DW;2;2;GEN_DW,GEN_Omega_1,");
        int demand = lines.indexOf("_c_fu;GEN_GOV;2;2;GEN_GOV,GEN_DW,");
        double pm0 = Double.parseDouble(lines.get(demand + 1).split(",")[0]);
        // The air-gap power: the load flow's 100.25 MW (B1 to the line's far end) and the stator's losses, on 100 MVA.
        assertThat(pm0).isBetween(1.0, 1.01);
        assertThat(lines.get(demand + 2)).endsWith("-u[1]/0.05000000000000000");
        int valve = lines.indexOf("_c_fslim;GEN_VALVE;4;4;GEN_VALVE,GEN_GOV,0,0,");
        assertThat(lines.get(valve + 1)).endsWith(",1.200000000000000,0.000000000000000,1,1,2,1,S1,");
        assertThat(lines.subList(valve + 2, valve + 5)).containsExactly("1", ";", "1 0.2000000000000000");
        int turbine = lines.indexOf("_c_fs;GEN_TURB;2;2;GEN_TURB,GEN_VALVE,");
        assertThat(lines.subList(turbine + 2, turbine + 5)).containsExactly("1.000000000000000 0.3000000000000000", ";", "1 1.000000000000000");
        int power = lines.indexOf("_c_fu;GEN_PM;3;3;GEN_Pm_1,GEN_TURB,GEN_DW,");
        assertThat(lines.get(power + 2)).isEqualTo("u[1]-0.5000000000000000*u[2]");
    }

    /**
     * The loaded machine shorted solidly at the line's far end, rotor free, field held, its TGOV1 governor in service: the machine
     * sheds its load and speeds up, the governor closing its valve - against the reference with the same governor ({@link
     * ParkShortCircuitReference.Governor}). Kept in {@code target/emtp/machine-governed-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 1.0e-5 off, every instant within 0.6 us of the first's; the governor's share, the 45th crest
     * 254 us later than with the mechanical power held.
     */
    @Test
    @Tag("emtp")
    void theGovernedMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(TurbineGovernorModel.class, new Tgov1Model(true, 0.05, 0.2, 0.0, 1.2, 0.3, 1.0, 0.5));
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-governed-short");
    }

    /**
     * The bench's own stabilizer with its regulator - PSS1A: Ks 12, Tw 9 s, T1/T2 0.12/0.06 s, T3/T4 0.25/0.03 s, within 0.15 pu - as
     * ATP's TACS has it: its rotor's speed observed per unit, its one mass's, before vd and vq; its deviation, washout, lead-lags and
     * limit; its output a fourth input to the regulator's error.
     */
    @Test
    void writesTheStabilizerAsAtpsTacsHasIt(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.withLoadFlow(EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"),
                ExampleNetworks.resource("/two-bus-extensions.xml")));
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_SM;GENa;8;6;B1a,GEN_Vf,GEN_Omega_1,GEN_vd,GEN_vq,,", "0 1 0 0 0", "MSu=1 0 1,",
                "_c_fu;GEN_DW;2;2;GEN_DW,GEN_Omega_1,", "u[1]-1");
        int washout = lines.indexOf("_c_fs;GEN_XW;2;2;GEN_XW,GEN_DW,");
        assertThat(lines.subList(washout + 1, washout + 5)).containsExactly("0.000000000000000,1,2,2,12.00000000000000,S1,", "0.000000000000000 9.000000000000000",
                ";", "1 9.000000000000000");
        int first = lines.indexOf("_c_fs;GEN_Y1;2;2;GEN_Y1,GEN_XW,");
        assertThat(lines.subList(first + 2, first + 5)).containsExactly("1.000000000000000 0.1200000000000000", ";", "1 0.06000000000000000");
        int second = lines.indexOf("_c_fs;GEN_VSP;2;2;GEN_VSP,GEN_Y1,");
        assertThat(lines.subList(second + 2, second + 5)).containsExactly("1.000000000000000 0.2500000000000000", ";", "1 0.03000000000000000");
        int limit = lines.indexOf("_c_lim;GEN_VSL;2;2;GEN_VS,GEN_VSP,");
        assertThat(lines.get(limit + 1)).isEqualTo("0.1500000000000000,-0.1500000000000000,1,S1,");
        int error = lines.indexOf("_c_fu;GEN_VERR;4;4;GEN_VERR,GEN_vd,GEN_vq,GEN_VS,");
        assertThat(lines.get(error + 2)).endsWith("+u[3]-SQRT(u[1]*u[1]+u[2]*u[2])");
    }

    /**
     * The bench's own simplified voltage regulator - Ka 250, Ta 0.015 s, its output within -6 and 6 pu - written as ATP's TACS has it:
     * the machine's vd and vq observed per unit; their voltage error from {@code Vref = Vt0 + Efd0 / Ka}, 1 + 2.4769 / 250; the lag
     * from it to the field voltage, starting at Efd0.
     */
    @Test
    void writesTheRegulatorAsAtpsTacsHasIt(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(loadedMachineWithRegulator(), tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_SM;GENa;7;5;B1a,GEN_Vf,GEN_vd,GEN_vq,,", "CTRL=0 0 0 1 0,", "OBSu=0 1,",
                "OBS=0 0 0 0 0 0 0 0 0 0 2 0 0 0 0 0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0,");
        int lag = lines.indexOf("_c_fslim;GEN_AVR;4;4;GEN_Vf,GEN_VERR,0,0,");
        assertThat(lag).as("the regulator's lag").isNotNegative();
        String[] fields = lines.get(lag + 1).split(",");
        assertThat(Double.parseDouble(fields[0])).isCloseTo(2.4769149, within(1.0e-6));
        assertThat(Arrays.copyOfRange(fields, 1, 8)).containsExactly("6.000000000000000", "-6.000000000000000", "1", "1", "2",
                "250.0000000000000", "S1");
        assertThat(lines.subList(lag + 2, lag + 5)).containsExactly("1", ";", "1 0.01500000000000000");
        int error = lines.indexOf("_c_fu;GEN_VERR;3;3;GEN_VERR,GEN_vd,GEN_vq,");
        assertThat(error).as("the voltage error").isNotNegative();
        assertThat(lines.get(error + 2)).endsWith("-SQRT(u[1]*u[1]+u[2]*u[2])");
        assertThat(Double.parseDouble(lines.get(error + 2).split("-")[0])).isCloseTo(1.0 + 2.4769149 / 250.0, within(1.0e-8));
    }

    /**
     * The loaded machine run live: both buses within 1e-4 pu and 0.01 degree of the load flow, in EMTP's steady state and its last
     * period. Kept in {@code target/emtp/machine-loaded}.
     *
     * <p>Run 2026-11-13: B1, the machine's terminal, within 1.3e-6 pu and 8.8e-4 degree; B2 within 8.3e-6 pu and 5.5e-4 degree.
     */
    @Test
    @Tag("emtp")
    void theLoadedMachineHoldsTheLoadFlow() {
        Network network = loadedMachineWithFieldHeld();
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "machine-loaded"), SETTINGS), EmtpSettings.defaults());

        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double steadyAngle = Math.IEEEremainder(phasor.angleDegrees() - bus.getAngle(), 360.0);
            double lastAngle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - bus.getAngle(), 360.0);
            System.out.printf(Locale.ROOT, "%s: steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", bus.getId(),
                    phasor.magnitude() / crest - 1.0, steadyAngle, fundamental.magnitude() / crest - 1.0, lastAngle);
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("%s's steady state, pu", bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(steadyAngle).as("%s's steady state, degrees", bus.getId()).isCloseTo(0.0, within(0.01));
            softly.assertThat(fundamental.magnitude() / crest - 1.0).as("%s's last period, pu", bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(lastAngle).as("%s's last period, degrees", bus.getId()).isCloseTo(0.0, within(0.01));
        }
        softly.assertAll();
    }

    /**
     * IEEE Std 115's sudden three-phase short, as upstream's backends are held to it: the unloaded machine, its field held, shorted
     * solidly at the far end of its line at 0.5 s ({@code faults-b2-a-b-c-0.5-2.5}). The AC current's envelope is the step response of
     * {@code 1/(X(s) + Xe)}, {@code X(s)} the d-axis operational reactance under the exact definitions and {@code Xe} the line's: read
     * off the machine's bus voltage, which the shorted line carries whole, crest by crest, each two consecutive crests averaged so that
     * the voltage the decaying DC current drops across the line's resistance cancels out - within 1% over the first 0.9 s, as upstream
     * holds ATP (0.41%) and Xyce (0.28%). Kept in {@code target/emtp/machine-short}.
     *
     * <p>Run 2026-11-13: the EMF 1.00000 pu, 88 pairs of crests, the worst 0.45% off - Park's equations integrated for the same short
     * ({@link #theParkReferenceShortsTheUnloadedMachineAsTheClosedFormDoes}) 0.46%.
     */
    @Test
    @Tag("emtp")
    void theSuddenShortFollowsTheMachinesData() {
        List<EmtEvent> shortCircuit = EmtEventsXml.read(ExampleNetworks.resource("/events/faults-b2-a-b-c-0.5-2.5.xml"));
        double faultStart = ((BusFault) shortCircuit.get(0)).closeTimeSeconds();
        Network network = unloadedMachineWithFieldHeld();
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "machine-short"),
                new EmtpSimulationSettings(TIME_STEP, 1.5, FREQUENCY), shortCircuit), EmtpSettings.defaults()).output();

        Generator generator = network.getGenerator("GEN");
        SubtransientMachineModel machine = (SubtransientMachineModel) generator.getExtension(GeneratorModel.class);
        double nominalV = generator.getTerminal().getVoltageLevel().getNominalV();
        double baseOhms = nominalV * nominalV / generator.getRatedS();
        double basePeakAmps = generator.getRatedS() * 1.0e6 / (Math.sqrt(3.0) * nominalV * 1.0e3) * Math.sqrt(2.0);
        double basePeakVolts = nominalV * 1.0e3 * Math.sqrt(2.0 / 3.0);
        Line line = network.getLine("L1_2");
        String b1a = EmtpNetlistWriter.voltageScope("B1", 'A');
        double[] before = SwitchingWindows.leadingUpTo(faultStart, 0.02, TIME_STEP);
        double emf = output.fundamentalPhasor(b1a, FREQUENCY, before[0], before[1]).magnitude() / basePeakVolts;
        List<Crest> crests = output.crests(b1a, faultStart + 0.002, faultStart + 0.9);
        double worst = 0.0;
        SoftAssertions softly = new SoftAssertions();
        for (int i = 0; i + 1 < crests.size(); i++) {
            double t = 0.5 * (crests.get(i).timeSeconds() + crests.get(i + 1).timeSeconds()) - faultStart;
            double measured = 0.5 * (crests.get(i).magnitude() + crests.get(i + 1).magnitude()) / Math.hypot(line.getR(), line.getX()) / basePeakAmps;
            double expected = emf * shortCircuitEnvelope(machine, line.getX() / baseOhms, t);
            worst = Math.max(worst, Math.abs(measured / expected - 1.0));
            softly.assertThat(measured / expected - 1.0).as("AC short-circuit current %.3f s after the short, %.4f pu against the data's %.4f pu",
                    t, measured, expected).isCloseTo(0.0, within(0.01));
        }
        System.out.printf(Locale.ROOT, "EMF %.5f pu; %d crest pairs, worst %.3e%n", emf, crests.size() - 1, worst);
        softly.assertThat(crests.size()).as("crests through the short").isGreaterThan(80);
        softly.assertAll();
    }

    /**
     * The reference the loaded short is held to ({@link ParkShortCircuitReference}), checked first on the unloaded one: its machine's
     * terminal voltage, crest by crest - each two consecutive crests averaged - over the line's impedance, within 0.5% of the closed form
     * {@link #shortCircuitEnvelope} over 0.9 s, which leaves out the stator's transients and resistance.
     *
     * <p>2026-11-13: within 0.46% over 89 pairs of crests - as upstream's ATP is (0.41%), and EMTP (0.45%,
     * {@link #theSuddenShortFollowsTheMachinesData}).
     */
    @Test
    void theParkReferenceShortsTheUnloadedMachineAsTheClosedFormDoes() {
        Network network = unloadedMachineWithFieldHeld();
        Generator generator = network.getGenerator("GEN");
        SubtransientMachineModel machine = (SubtransientMachineModel) generator.getExtension(GeneratorModel.class);
        double baseOhms = Math.pow(generator.getTerminal().getVoltageLevel().getNominalV(), 2) / generator.getRatedS();
        Line line = network.getLine("L1_2");
        double emf = generator.getTerminal().getBusBreakerView().getBus().getV() / generator.getTerminal().getVoltageLevel().getNominalV();
        ParkShortCircuitReference reference = new ParkShortCircuitReference(machine, line.getR() / baseOhms, line.getX() / baseOhms, FREQUENCY,
                new double[] {emf, 0.0}, new double[] {0.0, 0.0}, true);
        List<Crest> crests = phaseA(reference, 0.0, 0.0, 0.92).crests("V", 0.002, 0.9);

        double worst = 0.0;
        for (int i = 0; i + 1 < crests.size(); i++) {
            double t = 0.5 * (crests.get(i).timeSeconds() + crests.get(i + 1).timeSeconds());
            double current = 0.5 * (crests.get(i).magnitude() + crests.get(i + 1).magnitude()) / (Math.hypot(line.getR(), line.getX()) / baseOhms);
            worst = Math.max(worst, Math.abs(current / (emf * shortCircuitEnvelope(machine, line.getX() / baseOhms, t)) - 1.0));
        }
        System.out.printf(Locale.ROOT, "Park reference against the closed form: %d crest pairs, worst %.3e%n", crests.size() - 1, worst);
        assertThat(crests).hasSizeGreaterThan(80);
        assertThat(worst).isLessThan(0.005);
    }

    /**
     * The same sudden short from the loaded machine, where its flux has a q-axis share before the short and the current then decays
     * through both axes' rotor circuits - what tells two machines' q axes apart, where the unloaded short, all d axis, cannot. Its
     * inertia made 1e4 s to hold its speed, its field held, the short solid at the line's far end at 0.5 s, cleared after the run:
     * phase a of the machine's bus, crest by crest over 0.55 s, against {@link ParkShortCircuitReference} started from the load flow's
     * terminal voltage and current, the rotor's position from the voltage EMTP held at the bus before the short - each crest within
     * 0.5% and, but for the first one's delay, within 25 us of the reference's, as upstream holds ATP (0.03%) and Xyce (0.05%).
     * Kept in {@code target/emtp/machine-loaded-short}.
     *
     * <p>Run 2026-11-13: 55 crests, the worst 1.9e-5 off, the first 1.8 us late and every other within 6.3 us of it.
     */
    @Test
    @Tag("emtp")
    void theLoadedSuddenShortFollowsParksEquations() {
        assertShortFollowsParksEquations(loadedMachineWithFieldHeld(1.0e4), 0.0, true, 1.1, 0.55, "machine-loaded-short");
    }

    /**
     * The same loaded short with the machine's own inertia (5.5 s) and damping (1.8), its rotor free: through the short at the end of
     * its line the machine sheds most of its load and speeds up, some 1% per 100 ms, its rotor's angle drawing ahead of the network's -
     * the swing equation's share in the crests, their instants most of all, which the held speed above leaves out. Over 0.45 s, held to
     * the reference's free rotor ({@code 2H dw/dt = Pm/w - Te - D (w - 1)}) as tightly, as upstream holds ATP (0.04%) and Xyce (0.02%).
     * Kept in {@code target/emtp/machine-free-rotor-short}.
     *
     * <p>This is what showed the field voltage EMTP holds by itself, {@code Ef = w Mdf vf / rf}, not {@code vf}: its crests fell behind
     * the reference's in proportion to the angle the rotor gained, 1.4e-3 per radian, 0.33% by 0.42 s, the instants still within 2 us -
     * as Park's equations put them, within 4e-5, with {@code vf/w} for the field voltage. Since held by a constant on its {@code vf}
     * control ({@link EmtpSynchronousMachines}).
     *
     * <p>Run 2026-11-13: 45 crests, the worst 1.8e-5 off, the first 1.9 us late and every other within 1.9 us of it.
     */
    @Test
    @Tag("emtp")
    void theLoadedSuddenShortWithItsRotorFreeFollowsParksEquations() {
        assertShortFollowsParksEquations(loadedMachineWithFieldHeld(), 0.0, false, 1.0, 0.45, "machine-free-rotor-short");
    }

    /**
     * The same free-rotor short, the bench's regulator in service (Ka 250, Ta 0.015 s, -6 to 6 pu): the voltage falls to a tenth, the
     * regulator drives the field voltage to its ceiling within a millisecond and holds it there - against the reference driven by the
     * same regulator ({@link ParkShortCircuitReference.Regulator}), its lag's state held at the limit as EMTP's dynamic limits hold it.
     * Kept in {@code target/emtp/machine-regulated-short}.
     *
     * <p>Run 2026-11-13: 45 crests, the worst 2.1e-4 off, every instant within 1.9 us of the first's delay; the regulator's share, 0.0743
     * pu at the last crest where the field held leaves 0.0584.
     */
    @Test
    @Tag("emtp")
    void theRegulatedMachineDrivesItsFieldToTheCeilingThroughTheShort() {
        assertShortFollowsParksEquations(loadedMachineWithRegulator(), 0.0, false, 1.0, 0.45, "machine-regulated-short");
    }

    /**
     * A regulator that regulates rather than hits its ceiling: the unloaded machine faulted through 560 ohm a phase (0.35 pu) at the
     * line's far end, which leaves it a sixth of its voltage after the subtransient and transient decays without a regulator; with one of
     * Ka 20 and Ta 0.05 s, the field voltage rising from 1 pu, short of its ceiling, as the voltage falls - the fault's resistance in
     * series with the line's, to the reference. Kept in {@code target/emtp/machine-regulated-fault}.
     *
     * <p>Run 2026-11-13: 43 crests, the worst 2.1e-5 off, every instant within 0.4 us of the first's; the regulator's share, 0.851 pu at
     * the last crest where the field held leaves 0.705.
     */
    /**
     * The same resistive fault, through 1600 ohm a phase (1 pu), the bench's stabilizer with the regulator: the fault loads the unloaded
     * machine, which slows down, the stabilizer adding its speed's deviation, washed out and led, to the regulator's error - against
     * the reference with the same stabilizer ({@link ParkShortCircuitReference.Stabilizer}). Kept in {@code
     * target/emtp/machine-stabilized-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 1.2e-5 off, every instant within 0.1 us of the first's; the stabilizer's share, 0.903 pu
     * at the 41st crest where the regulator alone gives 1.002.
     */
    @Test
    @Tag("emtp")
    void theStabilizedMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        Generator generator = network.getGenerator("GEN");
        generator.addExtension(ExcitationSystemModel.class, new SimplifiedAvrModel(true, 20.0, 0.05, 400.0, -6.0, 6.0));
        generator.addExtension(PssModel.class, new Pss1AModel(true, 12.0, 9.0, 0.12, 0.06, 0.25, 0.03, 0.15));
        assertShortFollowsParksEquations(network, 1600.0, false, 1.0, 0.45, "machine-stabilized-fault");
    }

    /** EMTP cuts a netlist line short past some 100 characters: an expression is written on lines of 80 at most, each ending on an
     * operator, never inside a number's exponent. */
    @Test
    void wrapsALongExpressionOnItsOperators() {
        String expression = "u[1]-u[2]*((1.000000000000000)+(0.003674546172098538)*EXP((0.7903685529986396)*u[2]))-(1.000000000000000E-05)*u[3]";
        List<String> lines = EmtpSynchronousMachines.wrapped(expression);

        assertThat(String.join("", lines)).isEqualTo(expression);
        assertThat(lines).hasSizeGreaterThan(1).allMatch(line -> line.length() <= 80);
        assertThat(lines.subList(0, lines.size() - 1)).allMatch(line -> "+-*/".indexOf(line.charAt(line.length() - 1)) >= 0 && !line.endsWith("E-"));
        assertThat(EmtpSynchronousMachines.wrapped("u[1]-1")).containsExactly("u[1]-1");
    }

    /**
     * Upstream's ST1A written as ATP's TACS has it: the field current observed between vd and vq, its transducer, its clamped error,
     * its one lead-lag - the second, of Tb = 0, left out -, its regulator, its field-current limiter and its ceiling 7 Vt - 0.05 Ifd,
     * the field voltage between it and -6 Vt.
     */
    @Test
    void writesTheSt1AAsAtpsTacsHasIt(@TempDir Path tempDir) throws IOException {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st1AModel());
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_SM;GENa;8;6;B1a,GEN_Vf,GEN_vd,GEN_if,GEN_vq,,",
                "OBS=0 0 0 0 0 0 0 0 0 0 2 0 0 2 0 0 2 0 0 0 0 0 0 0 0 0 0 0 0 0 0,",
                "_c_fs;GEN_VC;2;2;GEN_VC,GEN_VT,", "_c_lim;GEN_VI;2;2;GEN_VI,GEN_VERR,", "10.00000000000000,-10.00000000000000,1,S1,",
                "_c_fs;GEN_LL1;2;2;GEN_LL1,GEN_VI,", "_c_fslim;GEN_VA;4;4;GEN_VA,GEN_LL1,0,0,", "_c_fu;GEN_FC;3;3;GEN_FC,GEN_VA,GEN_if,",
                "_c_fu;GEN_CEIL;3;3;GEN_CEIL,GEN_VT,GEN_if,", "(7.000000000000000)*u[1]-(0.05000000000000000)*u[2]",
                "_c_fu;GEN_EF;3;3;GEN_Vf,GEN_EFL,GEN_CEIL,");
        assertThat(lines).noneMatch(line -> line.startsWith("_c_fs;GEN_LL2;") || line.startsWith("_c_fs;GEN_VRF;"));
    }

    /**
     * Upstream's ST1A on the loaded bench ({@code ExampleNetworks#st1AModel}: Tr 0.02 s, one lead-lag 1/10 s, Ka 200, Ta 0.02 s, its
     * ceiling 7 Vt - 0.05 Ifd, a field-current limiter of 4.54 past 4.4 pu), its stabilizer off, rotor free, shorted solidly at the
     * line's far end: the terminal voltage falls to a tenth and takes the ceiling down with it, the field voltage held there - against
     * the reference driven by the same ST1A ({@link ParkShortCircuitReference.St1A}). Kept in {@code target/emtp/machine-st1a-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 1.1e-5 off, every instant within 1.9 us of the first's delay; the ceiling's share, the
     * field voltage pulled below the machine's own: 0.0487 pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theSt1AMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st1AModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-st1a-short");
    }

    /**
     * The same ST1A through the resistive fault, the unloaded machine faulted through 560 ohm a phase: the voltage falling to 0.61 pu
     * and brought back to 0.85, the regulator within its ceiling, its lead-lag and its regulator at work. Kept in {@code
     * target/emtp/machine-st1a-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 2.3e-5 off, every instant within 0.4 us of the first's; the field held would leave 0.708
     * pu at the 43rd crest, 0.846 with the ST1A.
     */
    @Test
    @Tag("emtp")
    void theSt1AMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st1AModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-st1a-fault");
    }

    /**
     * Upstream's DC1A written as ATP's TACS has it: its error with its rate feedback's output, its regulator at rest on Vr0, its
     * exciter an integrator on {@code Vr - (Ke + Aex exp(Bex Efd)) Efd} driving the field voltage it reads.
     */
    @Test
    void writesTheDc1AAsAtpsTacsHasIt(@TempDir Path tempDir) throws IOException {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, dc1a());
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_SM;GENa;7;5;B1a,GEN_Vf,GEN_vd,GEN_vq,,", "_c_fs;GEN_VRF;2;2;GEN_VRF,GEN_Vf,",
                "_c_fu;GEN_VERR;4;4;GEN_VERR,GEN_vd,GEN_vq,GEN_VRF,", "_c_fslim;GEN_VR;4;4;GEN_VR,GEN_VERR,0,0,", "_c_fu;GEN_VEX;3;3;GEN_VEX,GEN_VR,GEN_Vf,",
                "_c_fs;GEN_EFD;2;2;GEN_Vf,GEN_VEX,", "0 0.8000000000000000");
        int regulator = lines.indexOf("_c_fslim;GEN_VR;4;4;GEN_VR,GEN_VERR,0,0,");
        assertThat(lines.get(regulator + 1)).endsWith(",6.500000000000000,-4.000000000000000,1,1,2,200.0000000000000,S1,");
    }

    /** Upstream's two-bus DC1A ({@code ExampleNetworks#createTwoBusNetworkWithDc1A}): Ka 200, Ta 0.02 s, Vr within -4 and 6.5, Ke 1,
     * Te 0.8 s, Kf 0.03, Tf 1 s, Se 0.15 at 3 pu and 0.5 at 4 pu. */
    private static Dc1AModel dc1a() {
        return new Dc1AModel(true, 400.0, 200.0, 0.02, -4.0, 6.5, 1.0, 0.8, 0.03, 1.0, 3.0, 0.15, 4.0, 0.5);
    }

    /**
     * Upstream's DC1A ({@link #dc1a}) on the loaded bench, its stabilizer off, rotor free, shorted solidly at the line's far end: the
     * regulator at its ceiling at once, the exciter building the field voltage up behind its 0.8 s, saturating past 3 pu - against the
     * reference driven by the same DC1A ({@link ParkShortCircuitReference.Dc1A}). Kept in {@code target/emtp/machine-dc1a-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 3.9e-5 off, every instant within 1.9 us of the first's delay; the exciter's share, 0.0623
     * pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theDc1AMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, dc1a());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-dc1a-short");
    }

    /**
     * The same DC1A through the resistive fault, the unloaded machine faulted through 560 ohm a phase: its regulator and its rate
     * feedback within their limits. Kept in {@code target/emtp/machine-dc1a-fault}.
     *
     * <p>Run 2026-11-14: 44 crests, the worst 2.5e-5 off, every instant within 0.4 us of the first's; 0.758 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theDc1AMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, dc1a());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-dc1a-fault");
    }

    /**
     * Upstream's AC1A ({@code ExampleNetworks#ac1AModel}: Tr 0.02 s, no lead-lag, Ka 400, Ta 0.02 s, VA within 14.5, Te 0.8 s, VR within
     * -5.43 and 6.03, Kf 0.03, Tf 1 s, Kc 0.2, Kd 0.38, Ke 1, Se 0.1 at 4.18 pu and 0.3 at 5.57 pu) on the loaded bench, rotor free,
     * shorted solidly at the line's far end: the exciter driven at its ceiling, its armature reaction and its rectifier's commutation
     * drop loaded by the field current the short drives - against the reference driven by the same AC1A ({@link
     * ParkShortCircuitReference.Ac1A}). Kept in {@code target/emtp/machine-ac1a-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 3.5e-5 off, every instant within 1.9 us of the first's delay; the exciter's armature
     * reaction and commutation drop all but cancelling its push, the field held 0.5% higher at the 15th crest and level by the 43rd.
     */
    @Test
    @Tag("emtp")
    void theAc1AMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac1AModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-ac1a-short");
    }

    /**
     * The same AC1A through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-ac1a-fault}.
     *
     * <p>Run 2026-11-14: 44 crests, the worst 2.7e-5 off, every instant within 0.5 us of the first's; 0.727 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theAc1AMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac1AModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-ac1a-fault");
    }

    /**
     * Upstream's AC6A ({@code ExampleNetworks#ac6AModel}: KA 536, TA 0.086 s, TK 0.18 s, TB 9 s, TC 3 s, VA within 75, VR within -36 Vt
     * and 44 Vt, TE 1 s, KE 1.6, KC 0.173, KD 1.91, KH 92, TJ 0.02 s, TH 0.08 s, VFELIM 19, VHMAX 75) on the loaded bench, rotor free,
     * shorted solidly at the line's far end: VR to its ceiling, which the terminal voltage takes down with it - against the reference
     * driven by the same AC6A ({@link ParkShortCircuitReference.Ac6A}). Kept in {@code target/emtp/machine-ac6a-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 2.3e-5 off, every instant within 1.9 us of the first's delay; the ceiling's share, 0.0503
     * pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theAc6AMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac6AModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-ac6a-short");
    }

    /**
     * The same AC6A through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-ac6a-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 2.4e-5 off, every instant within 0.3 us of the first's; 0.851 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theAc6AMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac6AModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-ac6a-fault");
    }

    /**
     * Upstream's AC8B ({@code ExampleNetworks#ac8BModel}: KPR 80, KIR 5, KDR 10, TDR 0.1 s, KA 1, no TA, VR within 0 and 35, TE 1.2 s,
     * KE 1, VFEMAX 10, VEMIN 0, KC 0.55, KD 1.1, SE 0.3 at 6.5 pu and 3 at 9 pu) on the loaded bench, rotor free, shorted solidly at the
     * line's far end: the PID at its ceiling, its integrator held there - against the reference driven by the same AC8B ({@link
     * ParkShortCircuitReference.Ac8B}). Kept in {@code target/emtp/machine-ac8b-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 1.1e-4 off - the derivative's KDR/TDR of 100 on the error's step amplifying the control's
     * step of delay -, every instant within 2.0 us of the first's delay; 0.0607 pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theAc8BMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac8BModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-ac8b-short");
    }

    /**
     * The same AC8B through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-ac8b-fault}.
     *
     * <p>Run 2026-11-14: 44 crests, the worst 2.5e-5 off, every instant within 0.3 us of the first's; 0.792 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theAc8BMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac8BModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-ac8b-fault");
    }

    /**
     * Upstream's ST4B ({@code ExampleNetworks#st4BModel}: KPR = KIR = 10.75, VR within -0.87 and 1, TA 0.02 s, KPM 1, VM within -0.87 and
     * 1, KP 9.3, XL 0.124, KC 0.113, VBMAX 11.63) on the loaded bench, rotor free, shorted solidly at the line's far end: its compound
     * source - the terminal voltage's, falling, and the stator current's, rising - through the rectifier - against the reference driven by
     * the same ST4B ({@link ParkShortCircuitReference.St4B}). Kept in {@code target/emtp/machine-st4b-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 8.7e-5 off, every instant within 1.9 us of the first's delay; its source's current share
     * keeping a ceiling the voltage's alone would lose, 0.0637 pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theSt4BMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st4BModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-st4b-short");
    }

    /**
     * The same ST4B through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-st4b-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 1.9e-5 off, every instant within 0.3 us of the first's; 0.974 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theSt4BMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st4BModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-st4b-fault");
    }

    /**
     * Upstream's ST5B ({@code ExampleNetworks#st5BModel}: KR 200, T1 0.004 s, KC 0.004, VR within -4 and 5, TC1/TB1 0.8/6 s, TC2/TB2
     * 0.08/0.01 s) on the loaded bench, rotor free, shorted solidly at the line's far end: VR to its limit inside its lead-lag, the field
     * voltage held at the ceiling the terminal voltage takes down - against the reference driven by the same ST5B ({@link
     * ParkShortCircuitReference.St5B}). Kept in {@code target/emtp/machine-st5b-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 1.5e-5 off, every instant within 1.8 us of the first's delay; the ceiling's share, 0.0494
     * pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theSt5BMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st5BModel());
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-st5b-short");
    }

    /**
     * The same ST5B through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-st5b-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 2.3e-5 off, every instant within 0.2 us of the first's; 0.833 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theSt5BMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st5BModel());
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-st5b-fault");
    }

    /**
     * Upstream's ST6B ({@code ExampleNetworks#st6BModel(0)}: IEEE's sample data, KPA 18.038, KIA 45.094, VA and VR within -3.85 and 4.81,
     * KFF = KM = KG = 1, TG 0.02 s, KP 1, ILR 4.164, KCL 1.0577, KLR 17.33, no OEL) on the loaded bench, rotor free, shorted solidly at the
     * line's far end: its potential source the terminal voltage, falling to a tenth - against the reference driven by the same ST6B ({@link
     * ParkShortCircuitReference.St6B}). Kept in {@code target/emtp/machine-st6b-short}.
     *
     * <p>Run 2026-11-14: 45 crests, the worst 1.3e-5 off, every instant within 1.9 us of the first's delay; its source's collapse, 0.0483
     * pu at the 43rd crest where the field held leaves 0.0588.
     */
    @Test
    @Tag("emtp")
    void theSt6BMachineFollowsParksEquationsThroughTheShort() {
        Network network = loadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st6BModel(0));
        assertShortFollowsParksEquations(network, 0.0, false, 1.0, 0.45, "machine-st6b-short");
    }

    /**
     * The same ST6B through the resistive fault, the unloaded machine faulted through 560 ohm a phase. Kept in {@code
     * target/emtp/machine-st6b-fault}.
     *
     * <p>Run 2026-11-14: 43 crests, the worst 3.3e-5 off, every instant within 0.4 us of the first's; 0.762 pu at the 43rd crest where
     * the field held leaves 0.708.
     */
    @Test
    @Tag("emtp")
    void theSt6BMachineFollowsParksEquationsThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.st6BModel(0));
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-st6b-fault");
    }

    @Test
    @Tag("emtp")
    void theRegulatedMachineHoldsItsVoltageThroughAResistiveFault() {
        Network network = unloadedMachineWithFieldHeld();
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, new SimplifiedAvrModel(true, 20.0, 0.05, 400.0, -6.0, 6.0));
        assertShortFollowsParksEquations(network, 560.0, false, 1.0, 0.45, "machine-regulated-fault");
    }

    /** {@code network}'s machine faulted at 0.5 s at the line's far end, each phase through {@code faultOhms} to earth, against Park's
     * equations, crest by crest on its bus's phase a through {@code readTo}. */
    private static void assertShortFollowsParksEquations(Network network, double faultOhms, boolean speedHeld, double runEnd, double readTo,
                                                         String directory) {
        double faultStart = 0.5;
        List<BusFault> shortCircuit = List.of(new BusFault("FLTA", "B2", 'A', faultStart, runEnd + 1).withResistance(faultOhms),
                new BusFault("FLTB", "B2", 'B', faultStart, runEnd + 1).withResistance(faultOhms),
                new BusFault("FLTC", "B2", 'C', faultStart, runEnd + 1).withResistance(faultOhms));
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", directory),
                new EmtpSimulationSettings(TIME_STEP, runEnd, FREQUENCY), shortCircuit), EmtpSettings.defaults()).output();

        Generator generator = network.getGenerator("GEN");
        SubtransientMachineModel machine = (SubtransientMachineModel) generator.getExtension(GeneratorModel.class);
        double nominalV = generator.getTerminal().getVoltageLevel().getNominalV();
        double baseOhms = nominalV * nominalV / generator.getRatedS();
        double basePeakVolts = nominalV * 1.0e3 * Math.sqrt(2.0 / 3.0);
        Line line = network.getLine("L1_2");
        Bus bus = generator.getTerminal().getBusBreakerView().getBus();
        // The load flow's terminal voltage and the current the machine gives, per unit.
        double[] v = {bus.getV() / nominalV * Math.cos(Math.toRadians(bus.getAngle())), bus.getV() / nominalV * Math.sin(Math.toRadians(bus.getAngle()))};
        double p = -generator.getTerminal().getP() / generator.getRatedS();
        double q = -generator.getTerminal().getQ() / generator.getRatedS();
        double v2 = v[0] * v[0] + v[1] * v[1];
        double[] i = {(p * v[0] + q * v[1]) / v2, (p * v[1] - q * v[0]) / v2};
        ParkShortCircuitReference.Regulator regulator = generator.getExtension(ExcitationSystemModel.class) instanceof SimplifiedAvrModel avr && avr.isEnabled()
                ? new ParkShortCircuitReference.Regulator(avr.getGain(), avr.getTimeConstant(), avr.getMinOutput(), avr.getMaxOutput()) : null;
        ParkShortCircuitReference.St1A st1a = generator.getExtension(ExcitationSystemModel.class) instanceof St1AModel m && m.isEnabled()
                ? new ParkShortCircuitReference.St1A(m.getTransducerTimeConstant(), m.getInputMax(), m.getInputMin(), m.getLeadTimeConstant(),
                        m.getLagTimeConstant(), m.getLeadTimeConstant2(), m.getLagTimeConstant2(), m.getRegulatorGain(), m.getRegulatorTimeConstant(),
                        m.getRegulatorOutputMax(), m.getRegulatorOutputMin(), m.getFieldVoltageMax(), m.getFieldVoltageMin(),
                        m.getRectifierLoadingFactor(), m.getRateFeedbackGain(), m.getRateFeedbackTimeConstant(), m.getFieldCurrentLimiterGain(),
                        m.getFieldCurrentLimit()) : null;
        ParkShortCircuitReference.Dc1A dc1a = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof Dc1AModel m && m.isEnabled()) {
            ExponentialSaturation saturation = ExponentialSaturation.fit(m.getSaturationVoltage1(), m.getSaturationFactor1(), m.getSaturationVoltage2(),
                    m.getSaturationFactor2());
            dc1a = new ParkShortCircuitReference.Dc1A(m.getRegulatorGain(), m.getRegulatorTimeConstant(), m.getMinOutput(), m.getMaxOutput(),
                    m.getExciterConstant(), m.getExciterTimeConstant(), m.getRateFeedbackGain(), m.getRateFeedbackTimeConstant(), saturation.aex(),
                    saturation.bex());
        }
        ParkShortCircuitReference.Ac1A ac1a = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof Ac1AModel m && m.isEnabled()) {
            ExponentialSaturation saturation = ExponentialSaturation.fit(m.getSaturationVoltage1(), m.getSaturationFactor1(), m.getSaturationVoltage2(),
                    m.getSaturationFactor2());
            double efd0 = MachineInitialState.subtransient(generator, machine, 0.0).efd();
            ac1a = new ParkShortCircuitReference.Ac1A(m.getTransducerTimeConstant(), m.getLeadTimeConstant(), m.getLagTimeConstant(), m.getRegulatorGain(),
                    m.getRegulatorTimeConstant(), m.getRegulatorOutputMax(), m.getRegulatorOutputMin(), m.getExciterTimeConstant(), m.getFieldVoltageMax(),
                    m.getFieldVoltageMin(), m.getRateFeedbackGain(), m.getRateFeedbackTimeConstant(), m.getRectifierLoadingFactor(), m.getDemagnetizingFactor(),
                    m.getExciterConstant(), saturation.aex(), saturation.bex(), RectifierRegulation.exciterVoltageFor(efd0, m.getRectifierLoadingFactor(), efd0));
        }
        ParkShortCircuitReference.Ac6A ac6a = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof Ac6AModel m && m.isEnabled()) {
            Ac6AInitialState state = Ac6AInitialState.of(generator, m, MachineInitialState.subtransient(generator, machine, 0.0).efd());
            ac6a = new ParkShortCircuitReference.Ac6A(m.getTransducerTimeConstant(), m.getRegulatorGain(), m.getRegulatorTimeConstant(),
                    m.getRegulatorLeadTimeConstant(), m.getLagTimeConstant(), m.getLeadTimeConstant(), m.getRegulatorOutputMax(), m.getRegulatorOutputMin(),
                    m.getFieldVoltageMax(), m.getFieldVoltageMin(), m.getExciterTimeConstant(), m.getExciterConstant(), m.getRectifierLoadingFactor(),
                    m.getDemagnetizingFactor(), m.getFieldCurrentFeedbackGain(), m.getFieldCurrentFeedbackLeadTimeConstant(),
                    m.getFieldCurrentFeedbackLagTimeConstant(), m.getFieldCurrentFeedbackThreshold(), m.getFieldCurrentFeedbackMax(), m.saturation().aex(),
                    m.saturation().bex(), state.reference(), state.voltage(), state.regulator(), state.feedback(), state.exciterVoltage());
        }
        ParkShortCircuitReference.Ac8B ac8b = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof Ac8BModel m && m.isEnabled()) {
            Ac8BInitialState state = Ac8BInitialState.of(generator, m, MachineInitialState.subtransient(generator, machine, 0.0).efd());
            ac8b = new ParkShortCircuitReference.Ac8B(m.getTransducerTimeConstant(), m.getProportionalGain(), m.getIntegralGain(), m.getDerivativeGain(),
                    m.getDerivativeTimeConstant(), m.getRegulatorGain(), m.getRegulatorTimeConstant(), m.getRegulatorOutputMax(), m.getRegulatorOutputMin(),
                    m.getExciterTimeConstant(), m.getExciterConstant(), m.getExciterFieldMax(), m.getExciterVoltageMin(), m.getRectifierLoadingFactor(),
                    m.getDemagnetizingFactor(), m.saturation().aex(), m.saturation().bex(), state.reference(), state.voltage(), state.regulatorIntegral(),
                    state.exciterField(), state.exciterVoltage());
        }
        ParkShortCircuitReference.St4B st4b = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof St4BModel m && m.isEnabled()) {
            St4BInitialState state = St4BInitialState.of(generator, m, MachineInitialState.subtransient(generator, machine, 0.0).efd());
            st4b = new ParkShortCircuitReference.St4B(m.getTransducerTimeConstant(), m.getRegulatorProportionalGain(), m.getRegulatorIntegralGain(),
                    m.getRegulatorOutputMax(), m.getRegulatorOutputMin(), m.getRegulatorTimeConstant(), m.getInnerProportionalGain(), m.getInnerIntegralGain(),
                    m.getInnerOutputMax(), m.getInnerOutputMin(), m.getFeedbackGain(), m.voltageFactor(), m.currentFactor(), m.reactivePowerFactor(),
                    m.activePowerFactor(), m.getRectifierLoadingFactor(), m.getExciterVoltageMax(), state.reference(), state.regulatorIntegral(),
                    state.regulatorOutput(), state.innerIntegral());
        }
        ParkShortCircuitReference.St5B st5b = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof St5BModel m && m.isEnabled()) {
            St5BInitialState state = St5BInitialState.of(generator, m, MachineInitialState.subtransient(generator, machine, 0.0).efd());
            St5BModel.TimeConstants own = m.getTimeConstants();
            st5b = new ParkShortCircuitReference.St5B(m.getTransducerTimeConstant(), m.getRegulatorGain(), m.getExciterTimeConstant(),
                    m.getRectifierLoadingFactor(), m.getMaxOutput(), m.getMinOutput(), own.lead1(), own.lag1(), own.lead2(), own.lag2(), state.reference(),
                    state.regulatorOutput());
        }
        ParkShortCircuitReference.St6B st6b = null;
        if (generator.getExtension(ExcitationSystemModel.class) instanceof St6BModel m && m.isEnabled()) {
            St6BInitialState state = St6BInitialState.of(generator, m, MachineInitialState.subtransient(generator, machine, 0.0).efd());
            st6b = new ParkShortCircuitReference.St6B(m.getTransducerTimeConstant(), m.getRegulatorProportionalGain(), m.getRegulatorIntegralGain(),
                    m.getRegulatorOutputMax(), m.getRegulatorOutputMin(), m.getFeedforwardGain(), m.getInnerGain(), m.getFeedbackGain(),
                    m.getFeedbackTimeConstant(), m.getMaxOutput(), m.getMinOutput(), m.getPotentialCircuitGain(), m.getRectifierLoadingFactor(),
                    m.getExciterVoltageMax(), m.getFieldCurrentLimit(), m.getFieldCurrentConversion(), m.getFieldCurrentLimiterGain(), state.reference(),
                    state.integral());
        }
        ParkShortCircuitReference.Stabilizer stabilizer = generator.getExtension(PssModel.class) instanceof Pss1AModel pss && pss.isEnabled()
                ? new ParkShortCircuitReference.Stabilizer(pss.getGain(), pss.getWashoutTimeConstant(), pss.getLeadTimeConstant(), pss.getLagTimeConstant(),
                        pss.getLeadTimeConstant2(), pss.getLagTimeConstant2(), pss.getOutputLimit()) : null;
        ParkShortCircuitReference.Governor governor = generator.getExtension(TurbineGovernorModel.class) instanceof Tgov1Model tgov1 && tgov1.isEnabled()
                ? new ParkShortCircuitReference.Governor(tgov1.getDroop(), tgov1.getGovernorTimeConstant(), tgov1.getValveMin(), tgov1.getValveMax(),
                        tgov1.getTurbineLeadTimeConstant(), tgov1.getTurbineLagTimeConstant(), tgov1.getTurbineDamping()) : null;
        ParkShortCircuitReference reference = new ParkShortCircuitReference(machine, (line.getR() + faultOhms) / baseOhms, line.getX() / baseOhms, FREQUENCY,
                v, i, speedHeld, regulator, st1a, dc1a, ac1a, ac6a, ac8b, st4b, st5b, st6b, stabilizer, governor);

        String b1a = EmtpNetlistWriter.voltageScope("B1", 'A');
        double[] before = SwitchingWindows.leadingUpTo(faultStart, 0.02, TIME_STEP);
        double terminalAngle = -output.fundamentalPhasor(b1a, FREQUENCY, before[0], before[1]).angleRadians();
        double[] vdq = reference.preFaultTerminalVoltage();
        List<Crest> expected = phaseA(reference, faultStart, terminalAngle - Math.atan2(vdq[1], vdq[0]), readTo + 0.01)
                .crests("V", faultStart + 0.002, faultStart + readTo);
        List<Crest> measured = output.crests(b1a, faultStart + 0.002, faultStart + readTo);

        assertThat(measured).as("crests of phase a after the short").hasSameSizeAs(expected);
        double firstDelay = measured.get(0).timeSeconds() - expected.get(0).timeSeconds();
        double worst = 0.0;
        double worstDelay = 0.0;
        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < expected.size(); k++) {
            double crest = measured.get(k).magnitude() / basePeakVolts;
            double delay = measured.get(k).timeSeconds() - expected.get(k).timeSeconds() - firstDelay;
            worst = Math.max(worst, Math.abs(crest / expected.get(k).magnitude() - 1.0));
            worstDelay = Math.max(worstDelay, Math.abs(delay));
            softly.assertThat(crest / expected.get(k).magnitude() - 1.0).as("crest %d, %.5f pu at %.4f s against the reference's %.5f pu", k + 1,
                    crest, measured.get(k).timeSeconds() - faultStart, expected.get(k).magnitude()).isCloseTo(0.0, within(0.005));
            softly.assertThat(delay).as("crest %d's instant against the reference's, s", k + 1).isCloseTo(0.0, within(25.0e-6));
        }
        System.out.printf(Locale.ROOT, "%d crests: worst %.3e, first delay %.1f us, worst further delay %.1f us%n", expected.size(), worst,
                firstDelay * 1.0e6, worstDelay * 1.0e6);
        softly.assertAll();
    }

    /** {@code reference}'s phase a per unit through {@code duration} after the short at {@code faultStart}, the d axis {@code
     * rotorAngle} ahead of phase a at time 0 in the synchronous frame - and the reference's own swing ahead of that -, sampled every 5
     * us. */
    private static AbstractTimeStepTable phaseA(ParkShortCircuitReference reference, double faultStart, double rotorAngle, double duration) {
        double sample = 5.0e-6;
        double[][] dq = reference.terminalVoltages(duration, sample, 1.0e-6);
        double[][] rows = new double[dq.length][];
        for (int k = 0; k < dq.length; k++) {
            double t = faultStart + (k + 1) * sample;
            double angle = rotorAngle + 2.0 * Math.PI * FREQUENCY * t + dq[k][2];
            rows[k] = new double[] {t, dq[k][0] * Math.cos(angle) - dq[k][1] * Math.sin(angle)};
        }
        return new AbstractTimeStepTable(new String[] {"TIME", "V"}, rows) { };
    }

    /**
     * The AC envelope of a sudden three-phase short behind the external reactance {@code xe}, per unit of the EMF, {@code t} seconds
     * after it: the step response of {@code G(s) = D(s) / P(s)}, {@code D(s) = (1 + sT'd0)(1 + sT''d0)} and {@code P(s) = Xd (1 + sT'd)
     * (1 + sT''d) + xe D(s)}, the short-circuit time constants the exact definitions' {@code T'd = T'd0 Xd'/Xd}, {@code T''d = T''d0
     * Xd''/Xd'}: {@code G(0)} plus, at each root {@code s} of {@code P}, the residue {@code D(s) / (s P'(s)) e^(st)}.
     */
    static double shortCircuitEnvelope(SubtransientMachineModel machine, double xe, double t) {
        double xd = machine.getDirectSynchronousReactance();
        double t0 = machine.getOpenCircuitTransientTimeConstant();
        double t00 = machine.getDirectOpenCircuitSubtransientTimeConstant();
        double tShort = t0 * machine.getDirectTransientReactance() / xd;
        double ttShort = t00 * machine.getDirectSubtransientReactance() / machine.getDirectTransientReactance();
        double p2 = xd * tShort * ttShort + xe * t0 * t00;
        double p1 = xd * (tShort + ttShort) + xe * (t0 + t00);
        double p0 = xd + xe;
        double discriminant = Math.sqrt(p1 * p1 - 4.0 * p2 * p0);
        double response = 1.0 / p0;
        for (double s : new double[] {(-p1 + discriminant) / (2.0 * p2), (-p1 - discriminant) / (2.0 * p2)}) {
            response += (1.0 + s * t0) * (1.0 + s * t00) / (s * (2.0 * p2 * s + p1)) * Math.exp(s * t);
        }
        return response;
    }

    /** The bench, its load flow solved, its regulator and stabilizer disabled. */
    private static Network loadedMachineWithFieldHeld() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        holdField(network.getGenerator("GEN"));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The bench, its load flow solved, its stabilizer disabled, its regulator in service. */
    private static Network loadedMachineWithRegulator() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        Generator generator = network.getGenerator("GEN");
        ExcitationSystemModel regulator = generator.getExtension(ExcitationSystemModel.class);
        holdField(generator);
        generator.addExtension(ExcitationSystemModel.class, regulator);
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The same, its machine's inertia constant {@code inertiaSeconds}. */
    private static Network loadedMachineWithFieldHeld(double inertiaSeconds) {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        Generator generator = network.getGenerator("GEN");
        holdField(generator);
        SubtransientMachineModel m = (SubtransientMachineModel) generator.getExtension(GeneratorModel.class);
        generator.addExtension(GeneratorModel.class, new SubtransientMachineModel(inertiaSeconds, m.getDamping(), m.getStatorResistance(),
                m.getDirectSynchronousReactance(), m.getQuadratureSynchronousReactance(), m.getDirectTransientReactance(),
                m.getOpenCircuitTransientTimeConstant(), m.getStatorLeakageReactance(), m.getQuadratureTransientReactance(),
                m.getDirectSubtransientReactance(), m.getQuadratureSubtransientReactance(), m.getQuadratureOpenCircuitTransientTimeConstant(),
                m.getDirectOpenCircuitSubtransientTimeConstant(), m.getQuadratureOpenCircuitSubtransientTimeConstant(),
                m.getFieldCurrentAirGapLine(), m.getNumberOfPoles(), m.getSaturationFactor10(), m.getSaturationFactor12()));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The bench's machine with nothing to carry: no load, no active or reactive target, its field held. */
    private static Network unloadedMachineWithFieldHeld() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        network.getLoad("LOAD").remove();
        holdField(network.getGenerator("GEN").setTargetP(0.0).setLocalTargetQ(0.0));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The generator's voltage regulator and stabilizer, the same disabled. */
    private static void holdField(Generator generator) {
        SimplifiedAvrModel avr = (SimplifiedAvrModel) generator.getExtension(ExcitationSystemModel.class);
        generator.addExtension(ExcitationSystemModel.class, new SimplifiedAvrModel(false, avr.getGain(), avr.getTimeConstant(),
                avr.getVoltageSetpoint(), avr.getMinOutput(), avr.getMaxOutput()));
        Pss1AModel pss = (Pss1AModel) generator.getExtension(PssModel.class);
        generator.addExtension(PssModel.class, new Pss1AModel(false, pss.getGain(), pss.getWashoutTimeConstant(), pss.getLeadTimeConstant(),
                pss.getLagTimeConstant(), pss.getLeadTimeConstant2(), pss.getLagTimeConstant2(), pss.getOutputLimit()));
    }

    /** A data line's values after its key, each to 1e-12 relative. */
    private static void assertValues(String line, String key, double... expected) {
        assertThat(line).startsWith(key);
        double[] values = Arrays.stream(line.substring(key.length()).replace(",", "").trim().split("\\s+")).mapToDouble(Double::parseDouble).toArray();
        assertThat(values).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(values[i]).as("%s value %d", key, i).isCloseTo(expected[i], within(Math.abs(expected[i]) * 1.0e-12 + 1.0e-15));
        }
    }
}
