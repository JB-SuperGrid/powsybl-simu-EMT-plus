/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.PowerReferenceStep;
import com.powsybl.simuemt.extensions.governor.HygovInitialState;
import com.powsybl.simuemt.extensions.governor.HygovModel;
import com.powsybl.simuemt.extensions.governor.IeeeG1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Turbine-governors in EMTP ({@link EmtpTurbineGovernors}), each through the step of its power reference upstream's backends take, on
 * its example's machine islanded on its load: held to upstream's Xyce checks ({@code XyceSolverIntegrationTest}), which ATP and
 * Kestrel are held to as well. The step at 0.5 s, run 12 s; each kept in {@code target/emtp/governor-<name>}.
 *
 * <p>Run 2026-11-16: GoverNordic, GovSteamEU and GovCT2, with and without its engine's delay, within 2.1e-5 of Xyce's; IEEEG1,
 * GovSteam1, GovHydro4 and TGOV3 within 6.3e-5 (GovSteam1's valve as it crosses its gap); the first five within 1.1e-5 - HYGOV's 27
 * within 3.8e-6, IEEEG2's power at 3 s 4.9e-5 under the check's rounded 1.0172, Xyce's own 1.017155 within 4e-6; before the step,
 * every bus on its load flow within 8.3e-6 pu.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpTurbineGovernorTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double STEP = 0.5;
    private static final double RUN_END = 12.0;
    private static final String POWER = "control:GEN_PM";
    private static final String SPEED = "control:GEN_DW";

    /** The step: the reference stepped at its instant, on the time step, in the demand's expression. */
    @Test
    void writesAPowerReferenceStep(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithGoverDtrI8());
        String netlist = String.join("\n", Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 1.0, FREQUENCY),
                List.of(new PowerReferenceStep("GEN", STEP, 0.01))), StandardCharsets.ISO_8859_1));
        assertThat(netlist).contains("_c_fu;GEN_GDU;2;2;GEN_GDU,GEN_DW,", "+" + EmtpNetlistWriter.number(0.01) + "*0.5*(1+SIGN(t-(");
    }

    /**
     * {@code XyceSolverIntegrationTest#aGoverDtrI8AnswersAPowerStep}: the power through its one lag overshooting to 1.01129 at 1 s
     * within 3e-4; the speed 4.779e-4 pu at 3 s and 4.394e-4 settled, within 2e-5.
     */
    @Test
    @Tag("emtp")
    void aGoverDtrI8AnswersAPowerStep() {
        EmtpOutput output = run("goverdtri8", ExampleNetworks::createTwoBusNetworkWithGoverDtrI8, 0.01);
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        check(softly, output, POWER, 1.0, 1.01129, 3.0e-4);
        check(softly, output, SPEED, 3.0, 4.779e-4, 2.0e-5);
        check(softly, output, SPEED, 11.5, 4.394e-4, 2.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGoverProportionalHoldsItsLimitThroughAPowerStep}: the power held at its limit, 1.02, from the
     * step to 2 s; 1.01036 at 4 s within 3e-4; the speed settled at 2.3183e-3 pu within 3e-5.
     */
    @Test
    @Tag("emtp")
    void aGoverProportionalHoldsItsLimitThroughAPowerStep() {
        EmtpOutput output = run("goverproportional", ExampleNetworks::createTwoBusNetworkWithGoverProportional, 0.05);
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean(POWER, 0.52, 2.0)).as("the power held at its limit").isCloseTo(1.02, within(1.0e-6));
        check(softly, output, POWER, 4.0, 1.01036, 3.0e-4);
        check(softly, output, SPEED, 11.5, 2.3183e-3, 3.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#anIeeeG2AnswersAPowerStepTheWrongWayFirst}: the demand stepped at once; the power down by about
     * twice the step first, risen to 1.0172 at 3 s within 1e-3; the speed 2.09e-3 pu at its highest, within 2e-4, and between 8e-4 and
     * 1e-3 at the end.
     */
    @Test
    @Tag("emtp")
    void anIeeeG2AnswersAPowerStepTheWrongWayFirst() {
        EmtpOutput output = run("ieeeg2", ExampleNetworks::createTwoBusNetworkWithIeeeG2, 0.01);
        double pm0 = pm0(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithIeeeG2()));
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean("control:GEN_IGU", 0.52, 0.54)).as("the demand stepped at once").isCloseTo(pm0 + 0.01, within(2.0e-4));
        softly.assertThat(output.mean(POWER, 0.52, 0.54) - pm0).as("the power down by about twice the step first").isBetween(-0.020, -0.016);
        check(softly, output, POWER, 3.0, 1.0172, 1.0e-3);
        check(softly, output, SPEED, 6.0, 2.09e-3, 2.0e-4);
        softly.assertThat(output.mean(SPEED, 11.5, 12.0)).as("the speed at the end").isBetween(8.0e-4, 1.0e-3);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGastMeetsItsLoadLimitOnAPowerStep}: the valve, the power and the exhaust temperature at rest
     * before; the valve heading for the load limit, not the demand, 0.3 s after the step (Xyce 1.028473); the power's peak between
     * {@code Pm0 + 0.026} and {@code + 0.034}; the speed between 2.1e-3 and 2.4e-3 pu at the end, the temperature on the power.
     */
    @Test
    @Tag("emtp")
    void aGastMeetsItsLoadLimitOnAPowerStep() {
        EmtpOutput output = run("gast", ExampleNetworks::createTwoBusNetworkWithGast, 0.05);
        double pm0 = pm0(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithGast()));
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean("control:GEN_GAV", 0.3, 0.5)).as("the valve before the step").isCloseTo(pm0, within(1.0e-4));
        softly.assertThat(output.mean("control:GEN_GAT", 0.3, 0.5)).as("the exhaust temperature before the step").isCloseTo(pm0, within(1.0e-4));
        check(softly, output, "control:GEN_GAV", 0.8, 1.028473, 5.0e-4);
        softly.assertThat(output.mean(POWER, 1.15, 1.25)).as("the power at its peak").isBetween(pm0 + 0.026, pm0 + 0.034);
        softly.assertThat(output.mean(SPEED, 11.5, 12.0)).as("the speed at the end").isBetween(0.0021, 0.0024);
        softly.assertThat(output.mean("control:GEN_GAT", 11.5, 12.0)).as("the exhaust temperature at the end, on the power")
                .isCloseTo(output.mean(POWER, 11.5, 12.0), within(5.0e-4));
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aHygovAnswersAPowerStepTheWrongWayFirst}, and its Xyce table in {@code
     * PscadFileWriterTest#writeRunsAHygovAsXyceDoes}: the gate at rest before; the power, the gate and the speed, 20 ms means from
     * each instant after the step, within 2.5e-4, 1e-4 and 2e-5 of Xyce's.
     */
    @Test
    @Tag("emtp")
    void aHygovAnswersAPowerStepTheWrongWayFirst() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithHygov());
        Generator generator = network.getGenerator("GEN");
        HygovInitialState initial = HygovInitialState.of(generator, (HygovModel) generator.getExtension(TurbineGovernorModel.class), pm0(network));
        EmtpOutput output = run("hygov", ExampleNetworks::createTwoBusNetworkWithHygov, 0.01);
        double[] after = {0.1, 0.3, 0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 11.0};
        double[][] xyce = {{0.998827, 0.921906, 0.9999752}, {0.990279, 0.930330, 0.9997774}, {0.991413, 0.936645, 0.9995384},
            {1.007891, 0.946584, 0.9993204}, {1.036725, 0.953589, 1.0007987}, {1.045227, 0.947529, 1.0061085}, {1.032276, 0.938965, 1.0090996},
            {1.023903, 0.935589, 1.0096734}, {1.022062, 0.935667, 1.0092686}};
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean("control:GEN_HYG", 0.3, 0.5)).as("the gate before the step").isCloseTo(initial.gate(), within(1.0e-4));
        for (int k = 0; k < after.length; k++) {
            if (STEP + after[k] + 0.02 > RUN_END) {
                continue;
            }
            check(softly, output, POWER, STEP + after[k], xyce[k][0], 2.5e-4);
            check(softly, output, "control:GEN_HYG", STEP + after[k], xyce[k][1], 1.0e-4);
            check(softly, output, SPEED, STEP + after[k], xyce[k][2] - 1.0, 2.0e-5);
        }
        softly.assertAll();
    }

    private static EmtpOutput run(String name, Supplier<Network> bench, double delta) {
        Network network = ExampleNetworks.withLoadFlow(bench.get());
        return EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "governor-" + name), new EmtpSimulationSettings(TIME_STEP, RUN_END,
                FREQUENCY), List.of(new PowerReferenceStep("GEN", STEP, delta))), EmtpSettings.defaults()).output();
    }

    /**
     * {@code XyceSolverIntegrationTest#anIeeeG1AnswersAPowerStepThroughItsReheater}: the valve at rest before, nearly the whole step open
     * 0.3 s after it (Xyce 1.01521), back within 2e-3 at the end; the power then held back by the reheater, between {@code Pm0 + 5e-4}
     * and {@code + 3e-3} (Xyce 1.00731); the speed between 4e-4 and 5.5e-4 pu at the end (Xyce 4.6e-4).
     */
    @Test
    @Tag("emtp")
    void anIeeeG1AnswersAPowerStepThroughItsReheater() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithIeeeG1());
        double pm0 = pm0(network);
        double valve0 = ((IeeeG1Model) network.getGenerator("GEN").getExtension(TurbineGovernorModel.class)).initialReference(network.getGenerator("GEN"), pm0);
        EmtpOutput output = run("ieeeg1", ExampleNetworks::createTwoBusNetworkWithIeeeG1, 0.01);
        String valve = "control:GEN_IEV";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean(valve, 0.3, 0.5)).as("the valve before the step").isCloseTo(valve0, within(1.0e-4));
        check(softly, output, valve, 0.8, 1.01521, 1.0e-4);
        check(softly, output, POWER, 0.8, 1.00731, 1.0e-4);
        softly.assertThat(output.mean(POWER, 0.8, 0.82)).as("the power held back by the reheater").isBetween(pm0 + 0.0005, pm0 + 0.003);
        softly.assertThat(output.mean(SPEED, 11.5, 12.0)).as("the speed at the end").isBetween(0.0004, 0.00055);
        softly.assertThat(output.mean(valve, 11.5, 12.0)).as("the valve at the end").isCloseTo(valve0, within(0.002));
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGovSteam1TakesAPowerStepThroughItsDeadbandAndBacklash}: the backlash 0.002 behind the valve as it
     * opens; at 2 s the valve 1.023622 and the backlash 1.021622, moved by the step; at 2.5 s the valve 1.021423, the backlash held
     * while the valve crosses the gap; at 4 s 0.002 ahead of it as it closes; the speed 5.40e-4 pu at 11.5 s.
     */
    @Test
    @Tag("emtp")
    void aGovSteam1TakesAPowerStepThroughItsDeadbandAndBacklash() {
        EmtpOutput output = run("govsteam1", ExampleNetworks::createTwoBusNetworkWithGovSteam1, 0.01);
        String valve = "control:GEN_GSV";
        String backlash = "control:GEN_GSBS";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean(valve, 1.0, 1.02) - output.mean(backlash, 1.0, 1.02)).as("the backlash behind the valve").isCloseTo(0.002, within(2.0e-5));
        check(softly, output, valve, 2.0, 1.023622, 1.0e-4);
        check(softly, output, backlash, 2.0, 1.021622, 1.0e-4);
        check(softly, output, valve, 2.5, 1.021423, 1.0e-4);
        softly.assertThat(output.mean(backlash, 2.5, 2.52)).as("the backlash held").isCloseTo(output.mean(backlash, 2.0, 2.02), within(2.0e-5));
        softly.assertThat(output.mean(backlash, 4.0, 4.02) - output.mean(valve, 4.0, 4.02)).as("the backlash ahead").isCloseTo(0.002, within(2.0e-5));
        check(softly, output, SPEED, 11.5, 5.40e-4, 2.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGovHydro4AnswersAPowerStepTheWrongWayFirst}: at 1 s the power 0.997560 and the gate 0.648227, the
     * backlash 0.002 behind; at 4 s the power 1.045510, the gate 0.667375, the backlash 0.665443, still there at 5 s; at 8 s the
     * backlash 0.002 ahead of the gate; the speed 9.90e-3 pu at 11.5 s.
     */
    @Test
    @Tag("emtp")
    void aGovHydro4AnswersAPowerStepTheWrongWayFirst() {
        EmtpOutput output = run("govhydro4", ExampleNetworks::createTwoBusNetworkWithGovHydro4, 0.01);
        String gate = "control:GEN_GHV";
        String backlash = "control:GEN_GHBS";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        check(softly, output, POWER, 1.0, 0.997560, 2.0e-4);
        check(softly, output, gate, 1.0, 0.648227, 1.0e-4);
        softly.assertThat(output.mean(gate, 1.0, 1.02) - output.mean(backlash, 1.0, 1.02)).as("the backlash behind the gate").isCloseTo(0.002, within(2.0e-5));
        check(softly, output, POWER, 4.0, 1.045510, 1.0e-3);
        check(softly, output, gate, 4.0, 0.667375, 1.0e-4);
        check(softly, output, backlash, 4.0, 0.665443, 1.0e-4);
        softly.assertThat(output.mean(backlash, 5.0, 5.02)).as("the backlash held").isCloseTo(output.mean(backlash, 4.0, 4.02), within(2.0e-5));
        softly.assertThat(output.mean(backlash, 8.0, 8.02) - output.mean(gate, 8.0, 8.02)).as("the backlash ahead").isCloseTo(0.002, within(2.0e-5));
        check(softly, output, SPEED, 11.5, 9.90e-3, 2.0e-4);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aTGov3FastValvesThroughAFault}: a three-phase fault on B2 from 0.5 to 0.6 s, the TGOV3's fast
     * valving from 0.5 s ({@code fault-b2-abc-0.5-0.6-fast-valving-gen-0.5}): at 1 s the power 0.434488, the pressure 1.078509; at 3 s
     * the pressure 1.250504, the integral 1.977864 behind the valve at 1.2, the speed -4.757e-2 pu; at 6 s the integral 3.402563; at 10
     * s 1.431215, the speed 3.028e-2; at 11.5 s the valve 0.681215, the speed 3.624e-2 pu.
     */
    @Test
    @Tag("emtp")
    void aTGov3FastValvesThroughAFault() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithTGov3());
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "governor-tgov3"), new EmtpSimulationSettings(TIME_STEP,
                RUN_END, FREQUENCY), EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-abc-0.5-0.6-fast-valving-gen-0.5.xml"))),
                EmtpSettings.defaults()).output();
        String pressure = "control:GEN_TGPR";
        String integral = "control:GEN_TGI";
        String valve = "control:GEN_TGG";
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean(SPEED, 0.3, 0.5)).as("the speed before the fault").isCloseTo(0.0, within(1.0e-5));
        check(softly, output, POWER, 1.0, 0.434488, 2.0e-3);
        check(softly, output, pressure, 1.0, 1.078509, 2.0e-3);
        check(softly, output, pressure, 3.0, 1.250504, 2.0e-3);
        check(softly, output, integral, 3.0, 1.977864, 5.0e-3);
        softly.assertThat(output.mean(valve, 3.0, 3.02)).as("the valve at its limit").isCloseTo(1.2, within(1.0e-6));
        check(softly, output, SPEED, 3.0, -4.757e-2, 1.0e-3);
        check(softly, output, integral, 6.0, 3.402563, 1.0e-2);
        check(softly, output, integral, 10.0, 1.431215, 1.0e-2);
        check(softly, output, SPEED, 10.0, 3.028e-2, 1.0e-3);
        check(softly, output, valve, 11.5, 0.681215, 1.0e-2);
        check(softly, output, SPEED, 11.5, 3.624e-2, 1.0e-3);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGoverNordicAnswersAPowerStepTheWrongWayFirst}, run 20 s: the gate at rest before on the load flow's
     * 0.838 of the turbine; the power 1.00505 at 0.8 s, down first, and 1.00702 at 3 s; the gate 0.83918 at 2 s; the speed 4.0704e-4 pu
     * at 10 s and 4.151e-4 at 19.5 s.
     */
    @Test
    @Tag("emtp")
    void aGoverNordicAnswersAPowerStepTheWrongWayFirst() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithGoverNordic());
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "governor-governordic"), new EmtpSimulationSettings(TIME_STEP,
                20.0, FREQUENCY), List.of(new PowerReferenceStep("GEN", STEP, 0.01))), EmtpSettings.defaults()).output();
        String gate = "control:GEN_GNV";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean(gate, 0.3, 0.5)).as("the gate before the step").isCloseTo(pm0(network) / 1.2, within(2.0e-5));
        check(softly, output, POWER, 0.8, 1.00505, 1.5e-4);
        check(softly, output, POWER, 3.0, 1.00702, 3.0e-4);
        check(softly, output, gate, 2.0, 0.83918, 1.0e-4);
        check(softly, output, SPEED, 10.0, 4.0704e-4, 2.0e-5);
        check(softly, output, SPEED, 19.5, 4.151e-4, 2.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGovSteamEuAnswersAPowerStep}: the control valves at rest before on {@code Pm0 / 1.2}; 0.845873
     * at 1 s; the power 1.009831 at 3 s; the speed 4.97e-4 pu at 11.5 s.
     */
    @Test
    @Tag("emtp")
    void aGovSteamEuAnswersAPowerStep() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithGovSteamEu());
        EmtpOutput output = run("govsteameu", ExampleNetworks::createTwoBusNetworkWithGovSteamEu, 0.01);
        String valves = "control:GEN_EUCV";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        softly.assertThat(output.mean(valves, 0.3, 0.5)).as("the control valves before the step").isCloseTo(pm0(network) / 1.2, within(2.0e-4));
        check(softly, output, valves, 1.0, 0.845873, 5.0e-4);
        check(softly, output, POWER, 3.0, 1.009831, 5.0e-4);
        check(softly, output, SPEED, 11.5, 4.97e-4, 2.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGovCt2AnswersAPowerStepThroughItsSpeedGovernor}, a step of 0.001: the valve, the exhaust temperature
     * and the filtered power at rest before; 0.5 s after the step the valve 0.720044 and the power 1.011063; at 11.5 s the valve 0.718008,
     * the exhaust temperature 0.717172, the speed 1.026e-3 pu.
     */
    @Test
    @Tag("emtp")
    void aGovCt2AnswersAPowerStepThroughItsSpeedGovernor() {
        EmtpOutput output = run("govct2", ExampleNetworks::createTwoBusNetworkWithGovCt2, 0.001);
        String valve = "control:GEN_GCVO";
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        check(softly, output, valve, 1.0, 0.720044, 1.0e-4);
        check(softly, output, POWER, 1.0, 1.011063, 2.0e-4);
        check(softly, output, valve, 11.5, 0.718008, 1.0e-4);
        check(softly, output, "control:GEN_GCTEX", 11.5, 0.717172, 1.0e-4);
        check(softly, output, SPEED, 11.5, 1.026e-3, 2.0e-5);
        softly.assertAll();
    }

    /**
     * {@code XyceSolverIntegrationTest#aGovCt2EngineDelaysTheTurbinesAnswer}: the engine delaying the fuel by 0.25 s - the valve 0.71803
     * 0.1 s after the step, the power not moving until the delay has gone by, then 1.0073 at 0.9 s, 1.01256 at 1.5 s, 1.01276 at 2 s; the
     * speed 1.027e-3 pu at 11.5 s.
     */
    @Test
    @Tag("emtp")
    void aGovCt2EngineDelaysTheTurbinesAnswer() {
        EmtpOutput output = run("govct2-delayed", ExampleNetworks::createTwoBusNetworkWithDelayedGovCt2, 0.001);
        SoftAssertions softly = new SoftAssertions();
        before(softly, output);
        check(softly, output, "control:GEN_GCVO", 0.6, 0.71803, 1.0e-4);
        softly.assertThat(output.mean(POWER, 0.5, 0.745)).as("the power until the delay has gone by").isCloseTo(output.mean(POWER, 0.3, 0.5), within(2.0e-5));
        check(softly, output, POWER, 0.9, 1.0073, 2.0e-4);
        check(softly, output, POWER, 1.5, 1.01256, 2.0e-4);
        check(softly, output, POWER, 2.0, 1.01276, 2.0e-4);
        check(softly, output, SPEED, 11.5, 1.027e-3, 2.0e-5);
        softly.assertAll();
    }

    /** The power before the step, on the load flow's mechanical power within 1e-4, as upstream's checks have it. */
    private static void before(SoftAssertions softly, EmtpOutput output) {
        softly.assertThat(output.mean(SPEED, 0.3, 0.5)).as("the speed before the step").isCloseTo(0.0, within(1.0e-5));
    }

    /** {@code column}'s 20 ms mean from {@code t}, against {@code expected} within {@code tolerance}; printed. */
    private static void check(SoftAssertions softly, EmtpOutput output, String column, double t, double expected, double tolerance) {
        double actual = output.mean(column, t, t + 0.02);
        System.out.printf(Locale.ROOT, "%s at %.2f s: %.7f (Xyce %.7f, off %.1e)%n", column, t, actual, expected, actual - expected);
        softly.assertThat(actual).as("%s at %.2f s", column, t).isCloseTo(expected, within(tolerance));
    }

    private static double pm0(Network network) {
        Generator generator = network.getGenerator("GEN");
        return MachineInitialState.subtransient(generator, (SubtransientMachineModel) generator.getExtension(GeneratorModel.class), 0.0).mechanicalPower();
    }
}
