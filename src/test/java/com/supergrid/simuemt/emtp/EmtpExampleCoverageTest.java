/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.testresources.ExampleCatalogue;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's catalogue of examples ({@code ExampleCatalogue}) against the EMTP writer: each example written, or refused with what it
 * holds that the writer does not write yet, said by name - nothing left out silently. The examples it writes are pinned, so that what
 * the writer gains or loses shows; and - tagged {@code emtp} - each is run live, every bus on the load flow.
 *
 * <p>2026-11-14, upstream's {@code b5cea5b0}: 26 of its 131 examples written. Its refusals, the most frequent first: power electronics
 * (VSCs, MMCs, their DC sides), load models (infinite buses, induction motors, constant power), shunts, wind turbines, open-ended lines
 * and transformers open at a winding, line models and zero-sequence data, arresters, excitation limiters, governors but TGOV1, PSS2A.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpExampleCoverageTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    /** The examples the writer writes whole, 2026-11-14. */
    private static final List<String> WRITTEN = List.of(
            "createThreeBusNetworkWithTransformerAndEmtExtensions",
            "createThreeBusNetworkWithTransformerMagnetizingBranchAndTap",
            "createTwoBusNetworkWithAc1A",
            "createTwoBusNetworkWithAc6A",
            "createTwoBusNetworkWithAc8B",
            "createTwoBusNetworkWithClassicalMachineModel",
            "createTwoBusNetworkWithDc1A",
            "createTwoBusNetworkWithEmtExtensions",
            "createTwoBusNetworkWithIdealInfiniteBusGenerator",
            "createTwoBusNetworkWithInfiniteBusGenerator",
            "createTwoBusNetworkWithParallelLines",
            "createTwoBusNetworkWithSt1A",
            "createTwoBusNetworkWithSt4B",
            "createTwoBusNetworkWithSt5B",
            "createTwoBusNetworkWithSt6B",
            "createTwoBusNetworkWithSt6BFieldCurrentLimit",
            "createTwoBusNetworkWithTgov1",
            "createFourBusNetworkWithThreeWindingsTransformerMagnetizingBranchAndTap",
            "createFourBusNetworkWithPhaseShiftingLegAndWindings",
            "createFourBusNetworkWithPurePhaseShiftingLeg",
            "createFourBusNetworkWithThreeWindingsTransformerAndEmtExtensions",
            "createThreeBusNetworkWithPhaseShiftingTransformer",
            "createThreeBusNetworkWithPurePhaseShiftingTransformer",
            "createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer",
            "createTwoBusNetworkWithLineDisconnectedAtBothEnds",
            "two-bus");

    /** Every example written, or refused naming what it holds that is not written yet; the written ones pinned. */
    @Test
    void writesEachExampleOrRefusesItByName(@TempDir Path tempDir) {
        Map<String, Integer> reasons = new TreeMap<>();
        SoftAssertions softly = new SoftAssertions();
        List<String> written = new java.util.ArrayList<>();
        for (ExampleCatalogue.Example example : ExampleCatalogue.examples()) {
            Network network = ready(example);
            List<String> refused = EmtpNetlistWriter.unsupported(network);
            Path directory = tempDir.resolve(example.name().replaceAll("[^A-Za-z0-9]+", "-"));
            if (refused.isEmpty()) {
                written.add(example.name());
                softly.assertThat(EmtpNetlistWriter.write(network, directory, SETTINGS)).as("%s written", example.name()).exists();
            } else {
                softly.assertThatThrownBy(() -> EmtpNetlistWriter.write(network, directory, SETTINGS)).as("%s refused", example.name())
                        .isInstanceOf(PowsyblException.class).hasMessageContaining(refused.get(0));
                refused.forEach(reason -> reasons.merge(reason.replaceAll("'[^']*'", "'*'"), 1, Integer::sum));
            }
        }
        reasons.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> System.out.printf(Locale.ROOT, "%3d x %s%n", e.getValue(), e.getKey()));
        System.out.printf(Locale.ROOT, "%d of %d examples written%n", written.size(), ExampleCatalogue.examples().size());
        softly.assertThat(written).as("the examples written").containsExactlyInAnyOrderElementsOf(WRITTEN);
        softly.assertAll();
        assertThatThrownBy(() -> EmtpNetlistWriter.write(ready(example("createTwoBusNetworkWithBergeronLine")), tempDir.resolve("bergeron"), SETTINGS))
                .hasMessageContaining("BergeronLineModel, which is not written yet");
    }

    static List<String> written() {
        return WRITTEN;
    }

    /** The one example held looser: its magnetizing admittance 1.6 times rated (G 3.3e-4 S, B -1e-3 S), which the writer hangs mid-T as
     * ATP's deck does and the load flow does not - 1.0e-2 pu and 0.087 degree off it (2026-11-14), where upstream holds ATP to running
     * cleanly alone; {@link EmtpTransformerTest}'s magnetized transformer, its current a real one's, within 1e-4. */
    private static final String MAGNETIZED_AND_TAPPED = "createThreeBusNetworkWithTransformerMagnetizingBranchAndTap";

    /**
     * Each example written, run live: every bus within 1e-4 pu and 0.01 degree of the load flow, displaced by its vector group, in EMTP's
     * steady state and its last period - but {@link #MAGNETIZED_AND_TAPPED}. Kept in {@code target/emtp/example-*}.
     *
     * <p>Run 2026-11-14, the 26 in some 25 s: every bus within 4.5e-5 pu and 2.3e-3 degree - TGOV1's machine's angle the farthest, its
     * governor's droop on the speed's settling -, but the magnetized and tapped transformer's, 1.0e-2 pu and 0.087 degree.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("written")
    @Tag("emtp")
    void anExampleWrittenRunsOnItsLoadFlow(String name) {
        Network network = ready(example(name));
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "example-" + name.replaceAll("[^A-Za-z0-9]+", "-")),
                SETTINGS), EmtpSettings.defaults());
        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        SoftAssertions softly = new SoftAssertions();
        double worstPu = 0.0;
        double worstDegrees = 0.0;
        double pu = MAGNETIZED_AND_TAPPED.equals(name) ? 1.5e-2 : 1.0e-4;
        double degrees = MAGNETIZED_AND_TAPPED.equals(name) ? 0.1 : 0.01;
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            if (Double.isNaN(bus.getV())) {
                continue;
            }
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            double angle = bus.getAngle() + offsets.degrees(bus);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double[] errors = {phasor.magnitude() / crest - 1.0, Math.IEEEremainder(phasor.angleDegrees() - angle, 360.0),
                fundamental.magnitude() / crest - 1.0, Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - angle, 360.0)};
            worstPu = Math.max(worstPu, Math.max(Math.abs(errors[0]), Math.abs(errors[2])));
            worstDegrees = Math.max(worstDegrees, Math.max(Math.abs(errors[1]), Math.abs(errors[3])));
            softly.assertThat(errors[0]).as("%s, %s's steady state, pu", name, bus.getId()).isCloseTo(0.0, within(pu));
            softly.assertThat(errors[1]).as("%s, %s's steady state, degrees", name, bus.getId()).isCloseTo(0.0, within(degrees));
            softly.assertThat(errors[2]).as("%s, %s's last period, pu", name, bus.getId()).isCloseTo(0.0, within(pu));
            softly.assertThat(errors[3]).as("%s, %s's last period, degrees", name, bus.getId()).isCloseTo(0.0, within(degrees));
        }
        System.out.printf(Locale.ROOT, "%s: buses within %.1e pu and %.1e degree%n", name, worstPu, worstDegrees);
        softly.assertAll();
    }

    private static ExampleCatalogue.Example example(String name) {
        return ExampleCatalogue.examples().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    /** The example on its load flow, as upstream's coverage tests ready it: a bench of files, or a network with a DC side, through the
     * pipeline's preprocessing. */
    private static Network ready(ExampleCatalogue.Example example) {
        Network network = example.create(EmtExtensionsXmlReader::readNetwork);
        if (example.fromFiles() || network.getDcNodeCount() > 0) {
            EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
            return network;
        }
        return ExampleNetworks.withLoadFlow(network);
    }
}
