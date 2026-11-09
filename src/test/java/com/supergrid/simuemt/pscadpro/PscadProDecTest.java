/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
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

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's DEC2A and DEC3A in PSCAD Professional, which upstream leaves to be run: its two-bus benches ({@code
 * ExampleNetworks#createTwoBusNetworkWithDec2A} and {@code #createTwoBusNetworkWithDec3A}) through the scenarios ATP, Xyce and Kestrel
 * run ({@code XyceSolverIntegrationTest#dec2ABoostsTheExcitationOnItsTriggerAndDec3ACutsTheStabilizerOffThroughAFault}), from the
 * steady state at 20 us, held to the same assertions - all but the DEC3A's output, which PSCAD does not record: the stabilizer's
 * output, on its limit, and S1, open, say it.
 *
 * <p>Run 2026-11-09: the DEC2A's pulse 0.0897 at 1.10 s, the terminal voltage it raises 1.077 near 1.4 s, 0.0053 left at the end;
 * the DEC3A open from the fault, the stabilizer on 0.1, the voltage back above VTMIN for good at 1.1646 s and S1 closed at 1.6648 s,
 * 0.5002 s on.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProDecTest {

    private static final double FREQUENCY = 50.0;
    /** How long the benches run, s, as upstream's: the pulse and the cut over by then. */
    private static final double RUN_END = 4.0;
    private static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(20.0e-6, RUN_END, FREQUENCY, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);

    /** The DEC2A at rest until its trigger at 1 s, then its pulse - near 0.09 - raising the terminal voltage some 7%, washed out by the end. */
    @Test
    void theDec2ABoostsTheExcitationOnItsTrigger() throws IOException {
        PscadOutput run = run(ExampleNetworks.createTwoBusNetworkWithDec2A(), "dec2a", "excitation-boost-gen-1.0");
        print(run, "GEN_VT", "GEN_DECVD");
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(run.mean("GEN_VT", 0.5, 0.95)).as("the terminal voltage before the trigger").isCloseTo(1.0, within(1.0e-4));
        softly.assertThat(run.peakAbsolute("GEN_DECVD", 0.0, 0.99)).as("the pulse before the trigger").isLessThan(1.0e-9);
        softly.assertThat(run.peakAbsolute("GEN_DECVD", 1.0, 1.5)).as("the pulse's crest").isBetween(0.085, 0.1);
        softly.assertThat(run.peakAbsolute("GEN_VT", 1.0, 2.0)).as("the terminal voltage it raises").isBetween(1.06, 1.09);
        softly.assertThat(run.mean("GEN_DECVD", RUN_END - 0.2, RUN_END)).as("the pulse washed out").isLessThan(0.01);
        softly.assertAll();
    }

    /** The DEC3A closed at rest, opened by the fault at B2 while the stabilizer sits on its limit, closed again TDR after the terminal voltage is back above VTMIN for good. */
    @Test
    void theDec3ACutsTheStabilizerOffThroughAFault() throws IOException {
        PscadOutput run = run(ExampleNetworks.createTwoBusNetworkWithDec3A(), "dec3a", "fault-b2-abc-1.0-1.1");
        print(run, "GEN_VT", "GEN_DECS1", "GEN_VS");
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(run.mean("GEN_DECS1", 0.5, 0.95)).as("S1 closed at rest").isGreaterThan(0.999);
        softly.assertThat(run.mean("GEN_DECS1", 1.002, 1.6)).as("S1 opened by the fault").isLessThan(1.0e-3);
        softly.assertThat(run.mean("GEN_VS", 1.05, 1.5)).as("the stabilizer on its limit meanwhile").isCloseTo(0.1, within(1.0e-3));
        double closed = run.firstTimeAbove("GEN_DECS1", 0.5, 1.1);
        // The voltage back above VTMIN for good, the dips that follow the clearing restarting the delay each.
        double recovered = run.firstTimeAbove("GEN_VT", 0.8, 1.1);
        for (double dip = run.firstTimeBelow("GEN_VT", 0.8, recovered); dip < closed; dip = run.firstTimeBelow("GEN_VT", 0.8, recovered)) {
            recovered = run.firstTimeAbove("GEN_VT", 0.8, dip);
        }
        System.out.printf(Locale.ROOT, "dec3a: back above VTMIN %.5f s, S1 closed %.5f s%n", recovered, closed);
        softly.assertThat(closed - recovered).as("S1 closed again TDR after the voltage is back above VTMIN, s").isCloseTo(0.5, within(0.005));
        softly.assertThat(run.mean("GEN_DECS1", 1.9, RUN_END)).as("S1 closed at the end").isGreaterThan(0.999);
        softly.assertAll();
    }

    private static PscadOutput run(Network network, String bench, String events) throws IOException {
        Path workDir = Files.createDirectories(Path.of("target", "dec", bench));
        return PscadProSimulation.runFromSteadyState(ExampleNetworks.withLoadFlow(network), workDir, SETTINGS, events(events), PscadProSettings.defaults()).output();
    }

    private static List<EmtEvent> events(String name) {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/" + name + ".xml"));
    }

    /** The signals' 20 ms means at the instants upstream compares the backends at. */
    private static void print(PscadOutput run, String... signals) {
        for (double t : new double[] {0.9, 1.01, 1.03, 1.05, 1.1, 1.2, 1.4, 1.5, 1.6, 1.8, 2.0, 2.5, 3.0, RUN_END - 0.04}) {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.2f s:", t));
            for (String signal : signals) {
                line.append(String.format(Locale.ROOT, " %s %.6f", signal, run.mean(signal, t, t + 0.02)));
            }
            System.out.println(line);
        }
    }
}
