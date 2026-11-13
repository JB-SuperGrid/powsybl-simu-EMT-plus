/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * Example 1's reduced bench with VSC2 on a metallic return ({@code vsc-monopole-infinite-buses-metallic-return}: station 2's ground
 * taken away, its return conductor DCLR, 5 ohm and 0.3 H, from DC1G to DC2G), which PSCAD writes since upstream's {@code 738359dd}:
 * {@link PscadProCases}'s {@code template-vsc-metallic-return}, run live in PSCAD Professional from its steady state at 20 us, switching
 * on the time step, every sample kept, through the phase-a fault on B2 from 0.3 to 0.4 s, to 0.8 s. Upstream holds a hand-made PSCAD
 * Free run of it, every fifth sample kept ({@code PscadFileWriterTest#writeRestartRunsAVscMonopoleOnAMetallicReturnThroughAFaultAsAtpDoes}),
 * to ATP's 20 ms means; this holds PSCAD Professional's to the same means and tolerances - the numbers copied, the code this project's
 * own -: DC2G, VSC2's return, within 25 V at rest and 250 V through the fault, its L di/dt through DCLR swinging it from -175 V to
 * 4667 V; and VSC2's DC voltage across its terminals, DC2P less DC2G, the load flow's.
 *
 * <p>PSCAD Professional splits the DC side off into a subsystem of its own, which upstream's restart reads since its {@code 199f37e0}
 * ({@link PscadProSnapshotSubsystemsTest}, on this run's template snapshot); the run is kept in {@code target/metallic-return}, its
 * template's {@code .snp} and {@code Main.dta} what {@code vsc-metallic-return-professional/} holds.
 *
 * <p>Run 2026-11-13, 1.5 minutes: DC2G 2019.58 V at rest against ATP's 2018.24, -176.4, 1176.4 and 4669.6 V through the fault, 2.7 V off
 * at most, 2235.4 and 2021.23 V after; DC2P within 24 V; VSC2's powers within 3.0e-4 pu, VSC1's 5.1e-4; VSC2's DC voltage 0.991922 pu
 * against the load flow's 0.991935, VSC1's 1.000010 at the end. The same to every digit printed with the split unticked - one subsystem,
 * as PSCAD Free's -, and as upstream's PSCAD Free run: 2019.58 V at rest, 4669.6 V at the clearing.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProMetallicReturnTest {

    private static final String[] COLUMNS = {"VSC2_P", "VSC2_Q", "VSC1_VDC", "VSC1_P", "V_DC2P", "V_DC2G"};
    /** ATP's 20 ms means upstream: window start, then {@link #COLUMNS}. */
    private static final double[][] ATP = {
        {0.10, -0.800007, -0.044653, 1.000043, 0.809710, 498004.7, 2018.244},
        {0.30, -0.597816, -0.212894, 1.005070, 0.690847, 503284.8, -175.448},
        {0.36, -0.671041, -0.335254, 0.995259, 0.645696, 496428.5, 1173.732},
        {0.40, -0.808213, -0.300817, 0.995826, 0.742273, 493178.8, 4667.139},
        {0.50, -0.799251, -0.039768, 1.009957, 0.781530, 502649.0, 2234.748},
        {0.78, -0.800033, -0.042439, 0.999879, 0.809614, 497920.5, 2021.226}};
    private static final double[] STEADY = {0.008, 0.004, 0.004, 0.016, 2000.0, 25.0};
    private static final double[] THROUGH_THE_FAULT = {0.025, 0.025, 0.005, 0.03, 2500.0, 250.0};

    @Test
    void aVscStationOnAMetallicReturnRidesThroughAFaultAsAtpDoes() throws IOException {
        Path workDir = Files.createDirectories(Path.of("target", "metallic-return"));
        PscadProCases.Case scenario = PscadProCases.all().filter(c -> c.name().equals("template-vsc-metallic-return")).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(scenario.network().get());

        PscadOutput run = PscadProSimulation.runFromSteadyState(network, workDir, scenario.settings(), scenario.events(),
                PscadProSettings.defaults()).output();

        SoftAssertions softly = new SoftAssertions();
        for (double[] window : ATP) {
            boolean faulted = window[0] >= 0.3 - 1.0e-9 && window[0] < 0.5 - 1.0e-9;
            for (int k = 0; k < COLUMNS.length; k++) {
                double mean = run.mean(COLUMNS[k], window[0], window[0] + 0.02);
                System.out.printf(Locale.ROOT, "%s from %.2f s: %.6f (ATP %.6f, %.3e off)%n", COLUMNS[k], window[0], mean, window[k + 1],
                        mean - window[k + 1]);
                softly.assertThat(mean).as("%s from %.2f s", COLUMNS[k], window[0])
                        .isCloseTo(window[k + 1], within(faulted ? THROUGH_THE_FAULT[k] : STEADY[k]));
            }
        }
        // VSC2's DC voltage across its terminals, DC2P less DC2G, the load flow's at rest; its controls read it so.
        double acrossKv = network.getDcNode("DC2P").getV() - network.getDcNode("DC2G").getV();
        System.out.printf(Locale.ROOT, "VSC2_VDC 0.1-0.3 s %.6f (load flow %.6f); VSC1_VDC 0.7-0.8 s %.6f%n", run.mean("VSC2_VDC", 0.1, 0.3),
                acrossKv / 500.0, run.mean("VSC1_VDC", 0.7, 0.8));
        softly.assertThat(run.mean("VSC2_VDC", 0.1, 0.3)).as("VSC2's DC voltage across its terminals at rest, pu")
                .isCloseTo(acrossKv / 500.0, within(1.0e-4));
        softly.assertThat(run.mean("VSC1_VDC", 0.7, 0.8)).as("VSC1's DC voltage at the end, pu").isCloseTo(1.0, within(0.001));
        softly.assertAll();
    }
}
