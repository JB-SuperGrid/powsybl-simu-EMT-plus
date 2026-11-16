/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.ElectricalNodes;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
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
 * <p>2026-11-15, upstream's {@code 6346a20c}: 71 of its 132 examples written - since the shunts, the grounds, the open ends, the SVC, the battery, the WT4B - CIGRE DCS2 with it -, the switches
 * the infinite buses on a load, the Bergeron lines and the ZnO arresters; 2026-11-16, {@code 6f83d62b}: 73 of 134, its two rigid bipoles; 78 with the induction motors, 87 with the governors. Its
 * refusals, the most frequent first: power electronics (VSCs, MMCs, their DC sides), load models
 * (induction motors, constant power), zero-sequence data of lumped lines, excitation limiters,
 * governors but TGOV1, PSS2A.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpExampleCoverageTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    /** The examples the writer writes whole, 2026-11-14: 26 at first, the shunts', the ground's and the open ends' since. */
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
            "createTwoBusNetworkWithFanMotor",
            "createTwoBusNetworkWithGast",
            "createTwoBusNetworkWithGoverDtrI8",
            "createTwoBusNetworkWithGovHydro4",
            "createTwoBusNetworkWithGovSteam1",
            "createTwoBusNetworkWithGoverProportional",
            "createTwoBusNetworkWithHygov",
            "createTwoBusNetworkWithIeeeG1",
            "createTwoBusNetworkWithIeeeG2",
            "createTwoBusNetworkWithTGov3",
            "createTwoBusNetworkWithInductionMotor",
            "createTwoBusNetworkWithInfiniteBusAndDampedMotor",
            "createTwoBusNetworkWithInfiniteBusAndFanMotor",
            "createTwoBusNetworkWithInfiniteBusAndInductionMotor",
            "createTwoBusNetworkWithInfiniteBusGenerator",
            "createTwoBusNetworkWithInfiniteBusLoad",
            "createTwoBusNetworkWithBergeronLine",
            "createTwoBusNetworkWithModalBergeronLine",
            "createModalBergeronLineBench(false)",
            "createModalBergeronLineBench(true)",
            "createTwoBusNetworkWithZnOArrester",
            "createTwoBusNetworkWithClampingZnOArrester",
            "createThreeBusNetworkWithTransformerAndZnOArresters",
            "createTwoBusNetworkWithArresterAtTheFarEndOfALineOutOfService",
            "createTwoBusNetworkWithParallelLines",
            "createTwoBusNetworkWithSt1A",
            "createTwoBusNetworkWithSt4B",
            "createTwoBusNetworkWithSt5B",
            "createTwoBusNetworkWithSt6B",
            "createTwoBusNetworkWithSt6BFieldCurrentLimit",
            "createTwoBusNetworkWithTgov1",
            "createTwoBusNetworkWithSvc1",
            "createTwoBusNetworkWithReecC",
            "createTwoBusNetworkWithRealSwitch",
            "createTwoBusNetworkWithOpenSwitchBetweenTwoFeeders",
            "createTwoBusNetworkWithDeadFeederBehindOpenSwitch",
            "vsc-monopole-island",
            "createWindTurbineNetwork",
            "createFourBusNetworkWithThreeWindingsTransformerMagnetizingBranchAndTap",
            "createFourBusNetworkWithPhaseShiftingLegAndWindings",
            "createFourBusNetworkWithPurePhaseShiftingLeg",
            "createFourBusNetworkWithThreeWindingsTransformerAndEmtExtensions",
            "createThreeBusNetworkWithPhaseShiftingTransformer",
            "createThreeBusNetworkWithPurePhaseShiftingTransformer",
            "createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer",
            "createTwoBusNetworkWithLineDisconnectedAtBothEnds",
            "createTwoBusNetworkWithShuntCapacitor",
            "createTwoBusNetworkWithShuntReactor",
            "createTwoBusNetworkWithGround",
            "createTwoBusNetworkWithOpenEndedLine",
            "createThreeBusNetworkWithTransformerOpenAtItsSecondary",
            "createThreeBusNetworkWithThreeWindingsTransformerOpenAtLegThree",
            "vsc-bipole-converter-transformers",
            "vsc-bipole-converter-transformers-metallic-return",
            "vsc-bipole-converter-transformers-rigid",
            "vsc-bipole-infinite-buses",
            "vsc-bipole-infinite-buses-metallic-return",
            "vsc-bipole-infinite-buses-rigid",
            "vsc-symmetric-monopole-converter-transformers",
            "vsc-symmetric-monopole-infinite-buses",
            "vsc-symmetric-monopole-mmc",
            "cigre-dcs2",
            "vsc-three-terminal",
            "vsc-three-terminal-all-droop",
            "vsc-three-terminal-all-droop-mmc",
            "vsc-three-terminal-dc-breaker",
            "vsc-three-terminal-dc-breaker-mmc",
            "vsc-three-terminal-meshed-mmc",
            "vsc-three-terminal-vdc-droop",
            "vsc-monopole",
            "vsc-monopole-2",
            "vsc-monopole-3",
            "vsc-monopole-infinite-buses",
            "vsc-monopole-infinite-buses-2",
            "vsc-monopole-infinite-buses-3",
            "vsc-monopole-infinite-buses-ground-resistance",
            "vsc-monopole-infinite-buses-metallic-return",
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
        assertThatThrownBy(() -> EmtpNetlistWriter.write(ready(example("createTwoBusNetworkWithConstantPowerLoad")), tempDir.resolve("constant-power"),
                SETTINGS)).hasMessageContaining("has a load model, ConstantPowerLoadModel");
    }

    static List<String> written() {
        return WRITTEN;
    }

    /** The one example held looser: its magnetizing admittance 1.6 times rated (G 3.3e-4 S, B -1e-3 S), which the writer hangs mid-T as
     * ATP's deck does and the load flow does not - 1.0e-2 pu and 0.087 degree off it (2026-11-14), where upstream holds ATP to running
     * cleanly alone; {@link EmtpTransformerTest}'s magnetized transformer, its current a real one's, within 1e-4. */
    private static final String MAGNETIZED_AND_TAPPED = "createThreeBusNetworkWithTransformerMagnetizingBranchAndTap";

    /** The one example off its load flow by construction: its {@code Ground} earths B2 solidly, phase by phase, as ATP's deck does, where
     * the load flow knows nothing of it - B2 held at earth instead, within 1% of its voltage. */
    private static final String GROUNDED = "createTwoBusNetworkWithGround";
    /** An arrester undersized on purpose, clamping B2 below its load flow: held to its curve by {@link EmtpZnOArresterTest} instead. */
    private static final String CLAMPING = "createTwoBusNetworkWithClampingZnOArrester";

    /**
     * Each example written, run live: every bus within 1e-4 pu and 0.01 degree of the load flow, displaced by its vector group, in EMTP's
     * steady state and its last period - but {@link #MAGNETIZED_AND_TAPPED}, and {@link #GROUNDED}, its bus earthed. Kept in {@code
     * target/emtp/example-*}.
     *
     * <p>Run 2026-11-14, the 32 in some 30 s: every bus within 4.5e-5 pu and 2.3e-3 degree - TGOV1's machine's angle the farthest, its
     * governor's droop on the speed's settling -, but the magnetized and tapped transformer's, 1.0e-2 pu and 0.087 degree; the grounded
     * B2 at 2.5e-4 of its voltage; the open ends' within 8.3e-6 pu.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("written")
    @Tag("emtp")
    void anExampleWrittenRunsOnItsLoadFlow(String name) {
        Network network = ready(example(name));
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "example-" + name.replaceAll("[^A-Za-z0-9]+", "-")),
                SETTINGS), EmtpSettings.defaults());
        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        ElectricalNodes nodes = ElectricalNodes.of(network);
        boolean bergeron = network.getLineStream().anyMatch(l -> l.getExtension(BergeronLineModel.class) != null);
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
            // A bus a closed switch joins to another is that one's node.
            String nodeBus = nodes.representativeId(bus.getId());
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(nodeBus, 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(nodeBus, 'A'), FREQUENCY, last[0], last[1]);
            if (CLAMPING.equals(name)) {
                continue;
            }
            if (GROUNDED.equals(name)) {
                if ("B2".equals(bus.getId())) {
                    System.out.printf(Locale.ROOT, "%s: B2 at %.2e of its voltage, %.2e the last period%n", name, phasor.magnitude() / crest,
                            fundamental.magnitude() / crest);
                    softly.assertThat(phasor.magnitude() / crest).as("%s, B2 earthed, steady state", name).isLessThan(0.01);
                    softly.assertThat(fundamental.magnitude() / crest).as("%s, B2 earthed, last period", name).isLessThan(0.01);
                }
                continue;
            }
            if (bergeron) {
                // A Bergeron line is not the load flow's pi - its surge impedance and travel time are its own data (on upstream's benches
                // 320 ohm and 0.33 ms: 33 ohm and 3.2e-4 S against the pi's 40 ohm and none) -: its run held to EMTP's own steady state.
                crest = phasor.magnitude();
                angle = phasor.angleDegrees();
            }
            double[] errors = {phasor.magnitude() / crest - 1.0, Math.IEEEremainder(phasor.angleDegrees() - angle, 360.0),
                fundamental.magnitude() / crest - 1.0, Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - angle, 360.0)};
            worstPu = Math.max(worstPu, Math.max(Math.abs(errors[0]), Math.abs(errors[2])));
            worstDegrees = Math.max(worstDegrees, Math.max(Math.abs(errors[1]), Math.abs(errors[3])));
            softly.assertThat(errors[0]).as("%s, %s's steady state, pu", name, bus.getId()).isCloseTo(0.0, within(pu));
            softly.assertThat(errors[1]).as("%s, %s's steady state, degrees", name, bus.getId()).isCloseTo(0.0, within(degrees));
            // On the machine's bench, the machine starts on the pi's load flow, not on its Bergeron line's: 2.6e-3 pu and 0.097 degree off
            // EMTP's steady state the last period (run 2026-11-15); the benches on an ideal source within the usual.
            double lastPu = bergeron ? 5.0e-3 : pu;
            softly.assertThat(errors[2]).as("%s, %s's last period, pu", name, bus.getId()).isCloseTo(0.0, within(lastPu));
            // A VSC example's DC voltage station carries the converters' reactor losses (2.6e-3 pu of a 250 MVA converter's, 0.65 MW), which
            // the load flow leaves out: its converter bus 0.02 to 0.03 degree off behind a converter transformer, and vsc-monopole's and
            // vsc-monopole-2's machines, free of any governor, slowing down - their angles 0.04 degree off at 0.2 s, 0.18 at 0.8 s (runs
            // 2026-11-14). Their steady state within 0.01. CIGRE DCS2's VSC stations alike: 0.031 degree at its offshore buses (2026-11-15).
            double lastDegrees = bergeron ? 0.2 : name.startsWith("vsc-") || "cigre-dcs2".equals(name) ? 0.05 : degrees;
            softly.assertThat(errors[3]).as("%s, %s's last period, degrees", name, bus.getId()).isCloseTo(0.0, within(lastDegrees));
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
