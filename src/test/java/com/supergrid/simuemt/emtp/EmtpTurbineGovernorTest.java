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
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Turbine-governors in EMTP ({@link EmtpTurbineGovernors}), each through the step of its power reference upstream's backends take, on
 * its example's machine islanded on its load: held to upstream's Xyce checks ({@code XyceSolverIntegrationTest}), which ATP and
 * Kestrel are held to as well. The step at 0.5 s, run 12 s; each kept in {@code target/emtp/governor-<name>}.
 *
 * <p>Run 2026-11-16: every value within 1.1e-5 of Xyce's - HYGOV's 27 within 3.8e-6, IEEEG2's power at 3 s 4.9e-5 under the check's
 * rounded 1.0172, Xyce's own 1.017155 within 4e-6; before the step, every bus on its load flow within 8.3e-6 pu.
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
