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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * 0.5002 s on. From rest on upstream's stored runs' scenarios, 2026-11-10: the DEC2A's pulse on Xyce's to 1e-5, B1 within 2.3e-5, the
 * speed 2e-6; the DEC3A's S1 open and closed at Xyce's windows, the stabilizer within 3e-4, B1 1.5e-3 under in the fault, 7.7e-4
 * just after and within 2.3e-4 from 0.4 s after.
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

    /**
     * Upstream's stored-run scenarios ({@code PscadFileWriterTest#writeRunsADec2AAndADec3AAsXyceDoes}, upstream {@code 9ffcac8a}), from rest
     * at 20 us for 6 s, held to the same Xyce tables and tolerances, numbers copied: before the event on the load flow to 1e-5 - the
     * DEC3A's once its stabilizer has taken out the speed's step at the rotor's release; the DEC2A's pulse from its trigger at 3 s on
     * Xyce's to 1e-4, B1 to 5e-5, the speed to 1e-5; the DEC3A's S1 opening in the fault at 2.5 s and closing at Xyce's instants, the
     * stabilizer on Xyce's to 5e-4, B1 to 2e-3 in the fault, 1e-3 just after and 3e-4 from 0.4 s after the clearing.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dec2a", "dec3a"})
    void theDecRunsAsXyces(String bench) throws IOException {
        boolean boost = "dec2a".equals(bench);
        Network network = ExampleNetworks.withLoadFlow(boost ? ExampleNetworks.createTwoBusNetworkWithDec2A() : ExampleNetworks.createTwoBusNetworkWithDec3A());
        PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, 6.0, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP);
        PscadOutput run = PscadProSimulation.run(network, Files.createDirectories(Path.of("target", "dec", bench + "-from-rest")), settings,
                events(boost ? "excitation-boost-gen-3.0" : "fault-b2-abc-2.5-2.6"), PscadProSettings.defaults()).output();

        SoftAssertions softly = new SoftAssertions();
        double loadFlowCrest = network.getBusBreakerView().getBus("B1").getV() * Math.sqrt(2.0 / 3.0);
        for (double start : boost ? new double[] {2.02, 2.4} : new double[] {2.4}) {
            softly.assertThat(b1(run, start) / loadFlowCrest).as("|B1A| at %s s against the load flow", start).isCloseTo(1.0, within(1.0e-5));
        }
        // Xyce's run upstream (2026-11-09): |B1A| (kV), the speed, the DEC2A's VD - or the DEC3A's S1 and the stabilizer's output.
        double[][] xyce = boost
                ? new double[][] {{3.02, 327.6185, 0.999995, 0.06143}, {3.1, 333.0844, 0.999856, 0.08967}, {3.2, 341.2448, 0.999321, 0.08347},
                    {3.4, 351.0495, 0.997034, 0.06842}, {3.8, 341.9453, 0.993233, 0.04586}, {4.5, 333.1402, 0.990133, 0.02277},
                    {5.0, 332.1955, 0.989403, 0.01381}, {5.9, 329.0815, 0.989514, 0.00562}}
                : new double[][] {{2.55, 24.7595, 1.002866, 0.0, 0.1}, {2.7, 282.3077, 1.010051, 0.0, 0.1}, {3.02, 329.4966, 1.011609, 0.0, 0.08993},
                    {3.2, 330.9206, 1.011001, 1.0, 0.08828}, {3.4, 346.4864, 1.009151, 1.0, 0.04181}, {3.8, 336.8808, 1.005896, 1.0, 0.02799},
                    {4.5, 329.6705, 1.003158, 1.0, 0.01068}, {5.9, 326.9307, 1.001637, 1.0, 0.00077}};
        for (double[] window : xyce) {
            double t = window[0];
            double b1 = b1(run, t);
            double speed = run.mean("GEN_W", t, t + 0.02);
            String own = boost ? String.format(Locale.ROOT, "VD %.5f (%.5f)", run.mean("GEN_DECVD", t, t + 0.02), window[3])
                    : String.format(Locale.ROOT, "S1 %.4f (%.0f), VS %.5f (%.5f)", run.mean("GEN_DECS1", t, t + 0.02), window[3], run.mean("GEN_VS", t, t + 0.02), window[4]);
            System.out.printf(Locale.ROOT, "%s %.2f s: B1 %.4f kV (Xyce %.4f, %+.2e), speed %.6f (%.6f), %s%n", bench, t, b1, window[1], b1 / window[1] - 1.0,
                    speed, window[2], own);
            double tolerance = boost ? 5.0e-5 : t < 2.6 ? 0.002 : t < 2.9 ? 1.0e-3 : 3.0e-4;
            softly.assertThat(b1 / window[1]).as("|B1A| at %s s against Xyce", t).isCloseTo(1.0, within(tolerance));
            softly.assertThat(speed).as("the speed at %s s", t).isCloseTo(window[2], within(boost ? 1.0e-5 : 4.0e-5));
            if (boost) {
                softly.assertThat(run.mean("GEN_DECVD", t, t + 0.02)).as("the pulse at %s s", t).isCloseTo(window[3], within(1.0e-4));
            } else {
                softly.assertThat(run.mean("GEN_DECS1", t, t + 0.02)).as("S1 at %s s", t).isCloseTo(window[3], within(1.0e-3));
                softly.assertThat(run.mean("GEN_VS", t, t + 0.02)).as("the stabilizer at %s s", t).isCloseTo(window[4], within(5.0e-4));
            }
        }
        softly.assertAll();
    }

    private static double b1(PscadOutput run, double t) {
        return run.fundamentalPhasor("V_B1_A", FREQUENCY, t, t + 0.02).magnitude() / 1.0e3;
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
