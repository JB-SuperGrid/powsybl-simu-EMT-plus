/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.PhaseShiftCoefficients;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Phase-shifting transformers as ATP writes them - an ideal three-phase coupling of nine ideal units (ATP's {@code
 * AtpPhaseShiftingCoupling}, its circulant coefficients {@link PhaseShiftCoefficients}) - on upstream's phase-shifter benches, fed from
 * an ideal source as the overlay holds PSCAD Professional to them ({@code PscadProBeyondFreeTest}): the two-winding phase shifter, its
 * YNd11 twin (the coupling cascaded ahead of the bank), the three-winding leg (the star point an explicit node) and that leg with
 * winding connections (the coupling on the leg's bus), each with and without a ratio tap changer beside the phase one; upstream's
 * phase-shifting transformer with leakage; and the four with upstream's magnetizing admittance. Tagged {@code emtp}, run live: every
 * bus on the load flow, its angle from B1 displaced by the vector groups'.
 *
 * <p>Run 2026-11-13: the two-winding phase shifter within 9.5e-6 pu and 3.8e-4 degree of the load flow, its YNd11 twin 3.6e-5 pu and
 * 5.4e-5 degree, the three-winding leg 8.7e-6 pu and 3.7e-4 degree, with winding connections 8.3e-6 pu and 3.5e-4 degree - a ratio tap
 * changer beside the phase one or not -, the phase-shifting transformer with leakage 2.3e-5 pu and 1.0e-3 degree; magnetized, the
 * farthest bus at 92 to 93% of its bound. Its first run put the leg with winding connections' bus 20 degrees off, twice its tap's
 * angle: the cascade's angle taken the leg's way instead of against it ({@code EmtpNetlistWriter}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpPhaseShifterTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    @Test
    void writesTheCouplingAsNineIdealUnits(@TempDir Path tempDir) throws IOException {
        Network network = ideal(ExampleNetworks.createThreeBusNetworkWithPurePhaseShiftingTransformer());
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2_3");
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        assertThat(lines.stream().filter(l -> l.startsWith("_Tr0;PST2_3_"))).hasSize(9);
        // Output a's chain: input a's unit first, its secondary from earth to the chain's first node, at rho k1.
        int unit = lines.indexOf("_Tr0;PST2_3_aa;4;4;TT2_3_1a,,PST2_3_a1,,");
        assertThat(unit).isNotNegative();
        double[] k = PhaseShiftCoefficients.compute(transformer.getPhaseTapChanger().getCurrentStep().getAlpha());
        double rho = transformer.getRatedU2() / transformer.getRatedU1();
        assertThat(Double.parseDouble(lines.get(unit + 1).split(",")[0])).isCloseTo(rho * k[0], within(1.0e-15));
        // The T: half the leakage on each side, 16L/Dt across each.
        assertThat(lines).contains("_RLC;T2_3_W1a;2;2;B2a,TT2_3_1a,", "_RLC;T2_3_D1a;2;2;B2a,TT2_3_1a,", "_RLC;T2_3_W2a;2;2;PST2_3_a3,B3a,");
    }

    static Stream<Arguments> phaseShifters() {
        Stream.Builder<Arguments> benches = Stream.builder();
        for (boolean ratio : new boolean[] {false, true}) {
            String beside = ratio ? ", ratio tap changer beside" : "";
            benches.add(Arguments.of("two-winding" + beside, ratio, (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingTransformer));
            benches.add(Arguments.of("two-winding YNd11" + beside, ratio, (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer));
            benches.add(Arguments.of("three-winding leg" + beside, ratio, (Supplier<Network>) ExampleNetworks::createFourBusNetworkWithPurePhaseShiftingLeg));
            benches.add(Arguments.of("three-winding leg with winding connections" + beside, ratio,
                    (Supplier<Network>) ExampleNetworks::createFourBusNetworkWithPhaseShiftingLegAndWindings));
        }
        benches.add(Arguments.of("phase-shifting transformer with leakage", false, (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithPhaseShiftingTransformer));
        return benches.build();
    }

    /**
     * Each bench from its steady state: every bus within 1e-4 pu and 0.01 degree of the load flow, in EMTP's steady state and its last
     * period - what the overlay holds PSCAD Professional to on the same benches. The runs are kept in {@code target/emtp/phase-shifter-*}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("phaseShifters")
    @Tag("emtp")
    void aPhaseShifterPutsEveryBusWhereTheLoadFlowDoes(String name, boolean ratio, Supplier<Network> bench) {
        Network network = bench.get();
        if (ratio) {
            network = ExampleNetworks.withRatioTapChangerBesideThePhaseOne(network);
        }
        assertEveryBus(ideal(network), name, 1.0e-4, false);
    }

    static Stream<Arguments> magnetizingBenches() {
        return Stream.of(
                Arguments.of("two-winding", (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingTransformer),
                Arguments.of("two-winding YNd11", (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer),
                Arguments.of("three-winding leg", (Supplier<Network>) ExampleNetworks::createFourBusNetworkWithPurePhaseShiftingLeg),
                Arguments.of("three-winding leg with winding connections", (Supplier<Network>) ExampleNetworks::createFourBusNetworkWithPhaseShiftingLegAndWindings));
    }

    /**
     * The four with upstream's magnetizing admittance on the phase-shifting unit ({@code ExampleNetworks#withMagnetizingAdmittanceOnThePhaseShifters}):
     * the load flow's admittance a pi, ATP's and EMTP's at the T's middle, so every bus within {@code |y| |z|} of the load flow - half a
     * two-winding unit's leakage, a three-winding leg's whole - and the farthest beyond 80% of it, the T's own drop: the overlay's
     * criterion for PSCAD Professional, ATP, Xyce and Kestrel at 87 to 92% upstream.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("magnetizingBenches")
    @Tag("emtp")
    void aPhaseShiftersMagnetizingCurrentMovesEveryBusAsTheTDoes(String name, Supplier<Network> bench) {
        Network network = ideal(ExampleNetworks.withMagnetizingAdmittanceOnThePhaseShifters(bench.get()));
        assertEveryBus(network, name + " magnetizing", magnetizingDropAcrossTheLeakage(network), true);
    }

    private static void assertEveryBus(Network network, String name, double bound, boolean theT) {
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "phase-shifter-" + name.replaceAll("[^A-Za-z0-9]+", "-")),
                SETTINGS), EmtpSettings.defaults());
        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        Bus b1 = network.getBusBreakerView().getBus("B1");
        double b1Steady = steadyState.voltage(EmtpNetlistWriter.node("B1", 'a')).orElseThrow().angleDegrees();
        double b1Last = -Math.toDegrees(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, last[0], last[1]).angleRadians());
        double angleBound = theT ? Math.toDegrees(bound) : 0.01;
        double largest = 0.0;
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            double expected = bus.getAngle() - b1.getAngle() + offsets.degrees(bus) - offsets.degrees(b1);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double steadyError = phasor.magnitude() / crest - 1.0;
            double lastError = fundamental.magnitude() / crest - 1.0;
            double steadyAngle = Math.IEEEremainder(phasor.angleDegrees() - b1Steady - expected, 360.0);
            double lastAngle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - b1Last - expected, 360.0);
            largest = Math.max(largest, Math.abs(lastError));
            System.out.printf(Locale.ROOT, "%s %s: steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", name, bus.getId(),
                    steadyError, steadyAngle, lastError, lastAngle);
            softly.assertThat(steadyError).as("%s, %s's steady state, pu", name, bus.getId()).isCloseTo(0.0, within(bound));
            softly.assertThat(steadyAngle).as("%s, %s's steady state, degrees from B1", name, bus.getId()).isCloseTo(0.0, within(angleBound));
            softly.assertThat(lastError).as("%s, %s's last period, pu", name, bus.getId()).isCloseTo(0.0, within(bound));
            softly.assertThat(lastAngle).as("%s, %s's last period, degrees from B1", name, bus.getId()).isCloseTo(0.0, within(angleBound));
        }
        if (theT) {
            System.out.printf(Locale.ROOT, "%s: the farthest bus %.3e pu off, %.0f%% of its %.3e bound%n", name, largest, 100.0 * largest / bound, bound);
            softly.assertThat(largest / bound).as("%s: the farthest bus off the load flow, in its bound - the T's drop", name).isGreaterThan(0.8);
        }
        softly.assertAll();
    }

    /** The magnetizing current across the leakage between the T and the load flow's pi: half a two-winding unit's, a leg's whole. */
    private static double magnetizingDropAcrossTheLeakage(Network network) {
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            if (transformer.hasPhaseTapChanger()) {
                return Math.hypot(transformer.getG(), transformer.getB()) * Math.hypot(transformer.getR(), transformer.getX()) / 2.0;
            }
        }
        for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
            for (ThreeWindingsTransformer.Leg leg : transformer.getLegs()) {
                if (leg.hasPhaseTapChanger()) {
                    return Math.hypot(leg.getG(), leg.getB()) * Math.hypot(leg.getR(), leg.getX());
                }
            }
        }
        throw new IllegalArgumentException("no phase shifter in " + network.getId());
    }

    /** {@code network} on an ideal source, its load flow solved. */
    private static Network ideal(Network network) {
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        return ExampleNetworks.withLoadFlow(network);
    }
}
