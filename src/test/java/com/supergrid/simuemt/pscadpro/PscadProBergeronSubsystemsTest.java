/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A Bergeron line from its steady state in PSCAD Professional: upstream's Bergeron restart bench ({@code
 * PscadFileWriterTest#writeRestartStartsATransmissionLineSteadyFromItsTemplateRun}: an ideal infinite bus on B1, the line - 320 ohm,
 * 0.33 ms - to B2 and its load), 20 us, 0.4 s. PSCAD Professional splits the circuit at the line, which decouples its ends: its
 * template's snapshot has two subsystems, B2 and the load's in 1, B1's in 2, the line's data after subsystem 2 with its ends in each -
 * which upstream's patch refused ("a line's state is patched in a snapshot of one subsystem only") and reads since its
 * {@code pscad-bergeron-across-subsystems}. B1, the infinite bus's, on the load flow within 1e-6 pu; B2 not on the load flow's - a pi
 * section's, 6.3e-3 pu and 0.2 degree off - but on Kestrel's run of the same distributed line, |B2A| within 1e-8 in the first period and
 * in the last, as upstream's PSCAD Free restart holds it; and its angle from B1 the same in both within 1e-4 degree. Kept in
 * {@code target/bergeron-subsystems}: its template's snapshot and Main.dta, and the patched snapshot it restarted from, are what
 * {@code bergeron-subsystems-professional/} holds ({@link PscadProBergeronSubsystemsSnapshotTest}).
 *
 * <p>Run 2026-11-17, 45 s: B1 7.9e-9 pu off; |B2A| 326.080251 kV, Kestrel's 326.080250, and B2 -1.210957 degrees from B1, the same to
 * every digit printed in the first period as in the last.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProBergeronSubsystemsTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double SQRT_TWO_THIRDS = Math.sqrt(2.0 / 3.0);
    /** |B2A|, crest, Kestrel's on the same bench: upstream's PSCAD Free restart's, to 1e-8. */
    private static final double KESTREL_B2_KV = 326.080250;
    static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(TIME_STEP, 0.4, FREQUENCY, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);

    @Test
    void aBergeronLineStartsSteadyAcrossTwoSubsystems() throws IOException {
        Path workDir = Path.of("target", "bergeron-subsystems");
        Network network = bench();
        double runEnd = SETTINGS.stopTimeSeconds();

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, workDir, SETTINGS, List.of(), PscadProSettings.defaults());

        try (var snapshots = Files.walk(workDir.resolve("template"))) {
            Path template = snapshots.filter(p -> p.getFileName().toString().equals("two_bus.snp")).findFirst().orElseThrow();
            assertThat(Files.readAllLines(template).stream().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).as("the template's subsystems").hasSize(2);
        }
        Bus b1 = network.getBusBreakerView().getBus("B1");
        double crest = b1.getVoltageLevel().getNominalV() * 1.0e3 * SQRT_TWO_THIRDS;
        double[] first = {TIME_STEP, TIME_STEP + 1.0 / FREQUENCY};
        double[] last = {runEnd - 1.0 / FREQUENCY, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {first, last}) {
            double b1Error = PscadProBeyondFreeTest.magnitude(run, "B1", 'A', window) / crest - b1.getV() / b1.getVoltageLevel().getNominalV();
            double b2Kv = PscadProBeyondFreeTest.magnitude(run, "B2", 'A', window) / 1.0e3;
            System.out.printf(Locale.ROOT, "from %.5f s: B1 %.3e pu off the load flow; |B2A| %.6f kV, Kestrel's %.6f; B2 %.6f degrees from B1%n",
                    window[0], b1Error, b2Kv, KESTREL_B2_KV, PscadProBeyondFreeTest.angleFrom(run, "B1", "B2", window));
            softly.assertThat(b1Error).as("|V(B1)| against the load flow's, pu, from %.5f s", window[0]).isCloseTo(0.0, within(1.0e-6));
            softly.assertThat(b2Kv / KESTREL_B2_KV - 1.0).as("|B2A| against Kestrel's, from %.5f s", window[0]).isCloseTo(0.0, within(1.0e-8));
        }
        softly.assertThat(Math.IEEEremainder(PscadProBeyondFreeTest.angleFrom(run, "B1", "B2", last) - PscadProBeyondFreeTest.angleFrom(run, "B1", "B2",
                first), 360.0)).as("B2's angle from B1, its last period's against its first's, degrees").isCloseTo(0.0, within(1.0e-4));
        softly.assertAll();
    }

    /** Upstream's Bergeron restart bench: its two-bus network behind an ideal infinite bus, L1_2 a Bergeron line, the load flow run. */
    static Network bench() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
        return ExampleNetworks.withLoadFlow(network);
    }
}
