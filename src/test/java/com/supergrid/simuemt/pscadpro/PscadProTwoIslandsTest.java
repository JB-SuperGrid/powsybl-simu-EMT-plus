/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link TwoIslandsNetwork}'s two passive islands from their steady state in PSCAD Professional ({@link PscadProCases}'s {@code
 * template-two-islands}, 20 us, 0.2 s): its template's snapshot split into two subsystems, one per island, patched by upstream's restart
 * with the phasor steady state subsystem by subsystem (its {@code 199f37e0}). In the first period and in the last 60 ms, every bus at the
 * load flow's voltage within 1e-4 pu and at its angle from its island's source bus within 0.01 degree - what {@link PscadProBeyondFreeTest}
 * holds a snapshot of one subsystem to. The run is kept in {@code target/two-islands}: its template's snapshot and Main.dta, and the
 * patched snapshot it restarted from, are what {@code two-islands-professional/} holds ({@link PscadProTwoIslandsSnapshotTest}).
 *
 * <p>Run 2026-11-13, 1 minute: every bus within 8.1e-6 pu and 3.3e-4 degree of the load flow (B2's), the same to every digit printed in
 * the first period as 0.2 s on. Its first run found upstream's patch blind to a transformer unit outside subsystem 1, headed {@code 2 -2}
 * - "holds 0 units, not 3" -, read since upstream's {@code 9a95cf57}.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProTwoIslandsTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double SQRT_TWO_THIRDS = Math.sqrt(2.0 / 3.0);
    /** Each bus's island's source bus, its angles' reference. */
    private static final Map<String, String> REFERENCE = Map.of("B1", "B1", "B2", "B1", "B3", "B3", "B4", "B3", "B5", "B3");

    @Test
    void twoIslandsStartOnTheirLoadFlowEachInASubsystemOfItsOwn() throws IOException {
        Path workDir = Path.of("target", "two-islands");
        PscadProCases.Case scenario = PscadProCases.all().filter(c -> c.name().equals("template-two-islands")).findFirst().orElseThrow();
        Network network = scenario.loaded();
        double runEnd = scenario.settings().stopTimeSeconds();

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, workDir, scenario.settings(), scenario.events(),
                PscadProSettings.defaults());

        try (var snapshots = Files.walk(workDir.resolve("template"))) {
            Path template = snapshots.filter(p -> p.getFileName().toString().equals("two_islands.snp")).findFirst().orElseThrow();
            assertThat(Files.readAllLines(template).stream().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).as("the template's subsystems").hasSize(2);
        }
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{TIME_STEP, TIME_STEP + 1.0 / FREQUENCY}, {runEnd - 0.06, runEnd}}) {
            for (Bus bus : network.getBusBreakerView().getBuses()) {
                Bus reference = network.getBusBreakerView().getBus(REFERENCE.get(bus.getId()));
                double nominal = bus.getVoltageLevel().getNominalV();
                double puError = PscadProBeyondFreeTest.magnitude(run, bus.getId(), 'A', window) / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
                double angleError = bus == reference ? 0.0 : Math.IEEEremainder(PscadProBeyondFreeTest.angleFrom(run, reference.getId(), bus.getId(), window)
                        - (bus.getAngle() - reference.getAngle()), 360.0);
                System.out.printf(Locale.ROOT, "%s from %.5f s: %.3e pu, %.3e degree%n", bus.getId(), window[0], puError, angleError);
                softly.assertThat(puError).as("|V(%s)| against the load flow's, pu, from %.5f s", bus.getId(), window[0]).isCloseTo(0.0, within(1.0e-4));
                softly.assertThat(angleError).as("V(%s)'s angle from V(%s) against the load flow's, degrees, from %.5f s", bus.getId(), reference.getId(),
                        window[0]).isCloseTo(0.0, within(0.01));
            }
        }
        softly.assertAll();
    }
}
