/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Oel1BModel;
import com.powsybl.simuemt.extensions.excitation.limiter.OverexcitationLimiterModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * Upstream's IEEE ST6B in PSCAD Professional, run live and held to the Xyce tables upstream holds its stored PSCAD Free runs to
 * ({@code PscadFileWriterTest#writeRunsAMachineUnderItsSt6BAsXyceDoes}, upstream {@code 9ffcac8a}): the same benches and scenarios,
 * from rest at 20 us as upstream's, against a regression of PSCAD's or of the writer's. Through the fault B1 - the ST6B alone, and
 * with the comparison's OEL1B summed into the error (position 1) or taking over on {@code VR} (position 4) - and through a 15% step
 * with the field current limiter's reference brought down, the PI on {@code VAMAX} and the limiter holding the field current. The
 * numbers are upstream's, copied: B1 on Xyce's to 2e-3 in the fault, 5e-4 just after and 2e-4 from 0.3 s after the clearing, the
 * speed to 4e-5, the limited {@code VR} to 0.3%, the field current to 0.2%; each limited run's B1 under the unlimited one's after the
 * clearing.
 *
 * <p>Run 2026-11-10, as upstream's PSCAD Free runs: B1 1.16e-3 under Xyce's in the fault in all four, within 3.1e-4 just after and
 * 1.8e-4 from 0.3 s after the clearing; the speed within 3e-5; the limited {@code VR} within 0.3%, the OEL's runs' widest gap 0.27%
 * at 3.4 s; through the step the PI on 4.81 and the field current 0.13 to 0.14% under Xyce's, EMTDC's base for it, B1 within 9e-5.
 * About 2 minutes the five runs.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProSt6BTest {

    private static final double FREQUENCY = 50.0;
    private static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(20.0e-6, 4.0, FREQUENCY, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);
    private static PscadOutput unlimited;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"st6b", "st6b-limited", "st6b-oel1", "st6b-oel4"})
    void theSt6BRunsAsXyces(String bench) throws IOException {
        boolean limited = "st6b-limited".equals(bench);
        int position = bench.startsWith("st6b-oel") ? Integer.parseInt(bench.substring("st6b-oel".length())) : 0;
        Network network = bench(limited, position);
        PscadOutput run = run(network, bench, limited ? "voltage-step-gen-2.5-plus-0.15" : "fault-b2-a-2.5-2.6-at-order");

        SoftAssertions softly = new SoftAssertions();
        double loadFlowCrest = network.getBusBreakerView().getBus("B1").getV() * Math.sqrt(2.0 / 3.0);
        for (double start : new double[] {2.02, 2.4}) {
            softly.assertThat(b1(run, start) / loadFlowCrest).as("|B1A| at %s s against the load flow", start).isCloseTo(1.0, within(1.0e-5));
        }
        // Xyce's run upstream (2026-11-09): |B1A| (kV), the speed, the limited VR, the field current.
        double[][] xyce = switch (bench) {
            case "st6b-limited" -> new double[][] {{2.52, 327.9958, 0.999991, 4.81000, 2.72658}, {2.8, 345.0963, 0.998491, 3.59385, 2.85995},
                {3.0, 351.7056, 0.996165, 3.02057, 2.89303}, {3.4, 357.6223, 0.990378, 2.62440, 2.91589}, {3.8, 354.8942, 0.984595, 2.62081, 2.91610}};
            case "st6b-oel1" -> new double[][] {{2.52, 33.9561, 1.000675, Double.NaN, Double.NaN}, {2.65, 289.5192, 1.004905, Double.NaN, Double.NaN},
                {2.8, 310.9266, 1.006615, 4.20855, Double.NaN}, {3.0, 325.4751, 1.007203, 3.44953, Double.NaN},
                {3.4, 336.6046, 1.005345, 2.24509, Double.NaN}, {3.8, 332.4228, 1.003183, 2.05873, Double.NaN}};
            case "st6b-oel4" -> new double[][] {{2.52, 33.9582, 1.000675, Double.NaN, Double.NaN}, {2.65, 292.5219, 1.004845, Double.NaN, Double.NaN},
                {2.8, 313.9213, 1.006331, 4.00328, Double.NaN}, {3.0, 327.3809, 1.006688, 3.28161, Double.NaN},
                {3.4, 337.1733, 1.004667, 2.24395, Double.NaN}, {3.8, 332.6141, 1.002465, 2.04019, Double.NaN}};
            default -> new double[][] {{2.52, 33.9587, 1.000675, Double.NaN, Double.NaN}, {2.65, 293.3437, 1.004830, Double.NaN, Double.NaN},
                {2.8, 314.7155, 1.006255, 3.94488, Double.NaN}, {3.0, 327.8562, 1.006552, 3.23067, Double.NaN},
                {3.4, 337.0474, 1.004514, 2.22556, Double.NaN}, {3.8, 332.2555, 1.002380, 2.04517, Double.NaN}};
        };
        for (double[] window : xyce) {
            double t = window[0];
            double b1 = b1(run, t);
            double speed = run.mean("GEN_W", t, t + 0.02);
            double vr = run.mean("GEN_ST6VRL", t, t + 0.02);
            double ifd = run.mean("GEN_IF", t, t + 0.02);
            System.out.printf(Locale.ROOT, "%s %.2f s: B1 %.4f kV (Xyce %.4f, %+.2e), speed %.6f (%.6f), VR %.5f (%.5f), IF %.5f (%.5f)%n", bench, t, b1,
                    window[1], b1 / window[1] - 1.0, speed, window[2], vr, window[3], ifd, window[4]);
            double tolerance = limited ? 1.0e-4 : t < 2.6 ? 0.002 : t < 2.7 ? 5.0e-4 : 2.0e-4;
            softly.assertThat(b1 / window[1]).as("|B1A| at %s s against Xyce", t).isCloseTo(1.0, within(tolerance));
            softly.assertThat(speed).as("the speed at %s s", t).isCloseTo(window[2], within(4.0e-5));
            if (!Double.isNaN(window[3])) {
                softly.assertThat(vr).as("the limited VR at %s s", t).isCloseTo(window[3], withinPercentage(0.3));
            }
            if (!Double.isNaN(window[4])) {
                softly.assertThat(ifd).as("the field current at %s s", t).isCloseTo(window[4], withinPercentage(0.2));
            }
        }
        if (limited) {
            softly.assertThat(run.mean("GEN_ST6VA", 3.4, 3.42)).as("the PI on its ceiling").isCloseTo(4.81, within(1.0e-4));
        }
        if (position > 0) {
            // The limiter acting: B1 under the unlimited run's 0.3 s after the clearing, by more than the runs are apart.
            softly.assertThat(b1(run, 2.8) / b1(unlimited(), 2.8)).as("B1 at 2.8 s under the unlimited run's").isLessThan(0.999);
        }
        softly.assertAll();
    }

    /** Upstream's bench: the two-bus machine under the ST6B, its limiter's reference brought down or the comparison's OEL1B at its position. */
    private static Network bench(boolean limited, int position) {
        Network network = ExampleNetworks.withLoadFlow(limited ? ExampleNetworks.createTwoBusNetworkWithSt6BFieldCurrentLimit() : ExampleNetworks.createTwoBusNetworkWithSt6B());
        if (position > 0) {
            Generator generator = network.getGenerator("GEN");
            generator.addExtension(ExcitationSystemModel.class, ExampleNetworks.st6BModel(position));
            double ifd0 = MachineInitialState.subtransient(generator, (SubtransientMachineModel) generator.getExtension(GeneratorModel.class), 0.0).efd();
            generator.addExtension(OverexcitationLimiterModel.class, new Oel1BModel(true, position == 4, ifd0 + 0.1, 0.1, 0.05, 12.0, ifd0, -5.0));
        }
        return network;
    }

    /** The ST6B alone through the fault, run once for the limited benches to be compared with. */
    private static synchronized PscadOutput unlimited() throws IOException {
        if (unlimited == null) {
            unlimited = run(bench(false, 0), "st6b-unlimited", "fault-b2-a-2.5-2.6-at-order");
        }
        return unlimited;
    }

    /** From rest, as upstream's stored runs: the events after the rotor's release. */
    private static PscadOutput run(Network network, String bench, String events) throws IOException {
        Path workDir = Files.createDirectories(Path.of("target", "st6b", bench));
        return PscadProSimulation.run(network, workDir, SETTINGS, events(events), PscadProSettings.defaults()).output();
    }

    private static double b1(PscadOutput run, double t) {
        return run.fundamentalPhasor("V_B1_A", FREQUENCY, t, t + 0.02).magnitude() / 1.0e3;
    }

    private static List<EmtEvent> events(String name) {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/" + name + ".xml"));
    }
}
