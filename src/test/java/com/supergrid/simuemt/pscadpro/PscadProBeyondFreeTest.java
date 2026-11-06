/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.within;

/**
 * The networks past PSCAD Free's 15 electrical nodes that upstream writes for the Professional edition and that no PSCAD run
 * had held yet, run in PSCAD Professional and held to what upstream holds ATP, Xyce and Kestrel to on
 * the same scenarios: the load flow, and the physics of each switching. Each check is the network's or the circuit's, not
 * another backend's run. From the steady state where upstream's snapshot patching takes the circuit, from rest otherwise.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}, on a machine with PSCAD 5.1 Professional licensed, Intel
 * Fortran and the Windows SDK.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProBeyondFreeTest {

    private static final PscadProSettings PRO = PscadProSettings.defaults();
    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double SQRT_TWO_THIRDS = Math.sqrt(2.0 / 3.0);
    /** A node said dead: under 1 V, as upstream says it of the other backends. */
    private static final double DEAD_VOLTS = 1.0;

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
        return benches.build();
    }

    /**
     * The four phase-shifter benches - 18 to 24 nodes - fed from an ideal source, with and without a ratio tap changer beside the
     * phase one: in the last 60 ms of 0.6 s from rest, every bus at the load flow's voltage within 1e-4 pu, and at its angle from B1,
     * displaced by the vector groups', within 0.01 degree - upstream's bounds for the three other backends. From rest: past some
     * twenty nodes the snapshot's branches no longer run between the nodes {@code Main.dta} numbers them by, and upstream's patching
     * refuses it (the YNd11 and three-winding benches).
     *
     * <p>Run 2026-11-07: every bus within 3.7e-5 pu and 3.5e-4 degree of the load flow, ratio tap changer or not - the three other
     * backends' own level upstream (3.7e-5 pu, 4.8e-4 degree).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("phaseShifters")
    void aPhaseShifterPutsEveryBusWhereTheLoadFlowDoes(String name, boolean ratio, Supplier<Network> bench, @TempDir Path tempDir) {
        Network network = bench.get();
        if (ratio) {
            network = ExampleNetworks.withRatioTapChangerBesideThePhaseOne(network);
        }
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        ExampleNetworks.withLoadFlow(network);
        double runEnd = 0.6;

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings(runEnd), List.of(), PRO);

        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        Bus b1 = network.getBusBreakerView().getBus("B1");
        double[] window = {runEnd - 0.06, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            if (bus == b1) {
                continue;
            }
            double nominal = bus.getVoltageLevel().getNominalV();
            double puError = magnitude(run, bus.getId(), 'A', window) / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
            double expected = bus.getAngle() - b1.getAngle() + offsets.degrees(bus) - offsets.degrees(b1);
            double angleError = Math.IEEEremainder(angleFrom(run, "B1", bus.getId(), window) - expected, 360.0);
            System.out.printf("%s %s: %.3e pu, %.3e degree%n", name, bus.getId(), puError, angleError);
            softly.assertThat(puError).as("|V(%s)| against the load flow's, pu, %s", bus.getId(), name).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(angleError).as("V(%s)'s angle from V(B1) against the load flow's, degrees, %s", bus.getId(), name)
                    .isCloseTo(0.0, within(0.01));
        }
        softly.assertAll();
    }

    /**
     * A line out of service in the network - both terminals disconnected, 18 nodes - put back into service: its end 1 closed onto B1
     * at 0.2 s, which energizes it open-ended, its end 2 onto B2 at 0.3 s. Both ends dead before; the open end at its Ferranti rise
     * {@code 1 / (1 - x b2)} over B1 while open-ended; both ends on their buses at the end, within 1e-5 but for the breakers' closed
     * 0.01 ohm; B2 raised by the line back, by more than 1%.
     *
     * <p>Run 2026-11-07: the ends 0.13 V before; the open end 1.0104 times B1 on phase A, 1.0102 on B and C (1.0101 expected); the
     * ends on their buses within 3.2e-6; B2 up 1.31% - ATP's 1.3% upstream.
     */
    @Test
    void aLineOutOfServiceIsPutBackIntoService(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithLineDisconnectedAtBothEnds());
        double runEnd = 0.5;
        Line line = network.getLine("L1_2B");
        double ferranti = 1.0 / (1.0 - line.getX() * line.getB2());

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd),
                events("branch-l1_2b-close-one-0.2-two-0.3"), PRO);

        double[] dead = SwitchingWindows.leadingUpTo(0.2, 0.06, TIME_STEP);
        double[] openEnded = {0.26, 0.3};
        double[] last = {runEnd - 0.06, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (char phase : "ABC".toCharArray()) {
            double end1Dead = magnitude(run, "BE_L1_2B_1", phase, dead);
            double end2Dead = magnitude(run, "BE_L1_2B_2", phase, dead);
            double rise = magnitude(run, "BE_L1_2B_2", phase, openEnded) / magnitude(run, "B1", phase, openEnded);
            double end1 = relative(magnitude(run, "BE_L1_2B_1", phase, last), magnitude(run, "B1", phase, last));
            double end2 = relative(magnitude(run, "BE_L1_2B_2", phase, last), magnitude(run, "B2", phase, last));
            double raised = magnitude(run, "B2", phase, last) / magnitude(run, "B2", phase, dead);
            System.out.printf("line back, phase %s: ends dead %.2e %.2e V, rise %.5f (%.5f), ends on buses %.1e %.1e, B2 x%.4f%n", phase,
                    end1Dead, end2Dead, rise, ferranti, end1, end2, raised);
            softly.assertThat(end1Dead).as("end 1 before its closing, phase %s, V", phase).isLessThan(DEAD_VOLTS);
            softly.assertThat(end2Dead).as("end 2 before its closing, phase %s, V", phase).isLessThan(DEAD_VOLTS);
            softly.assertThat(rise).as("the open end's Ferranti rise, phase %s", phase).isCloseTo(ferranti, within(1.0e-3));
            softly.assertThat(end1).as("end 1 on B1, phase %s", phase).isLessThan(1.0e-5);
            softly.assertThat(end2).as("end 2 on B2, phase %s", phase).isLessThan(1.0e-5);
            softly.assertThat(raised).as("B2%s raised by the line back in service", phase).isGreaterThan(1.01);
        }
        softly.assertAll();
    }

    /**
     * A three-winding transformer's leg disconnected in the network - its own node, 18 nodes - closed onto B4 at 0.2 s. B4 dead
     * before; the leg's end at the star point's voltage on its ratio, within 1e-4; once closed, B4 on the leg's end within 2e-3 -
     * the breaker's 0.01 ohm under its current - and within 5% of its rated voltage.
     *
     * <p>Run 2026-11-07: B4 at 1.6e-5 V before; the leg's end within 1e-7 of the star point's voltage; B4 1.0e-3 under the leg's end
     * once closed, 0.3% off its 20 kV.
     */
    @Test
    void aThreeWindingTransformerLegIsClosed(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createThreeBusNetworkWithThreeWindingsTransformerOpenAtLegThree());
        double runEnd = 0.4;
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T2_3_4");
        double legRatedKv = transformer.getLeg3().getRatedU();
        double star = Double.parseDouble(transformer.getProperty("v")) * 1.0e3 * legRatedKv / transformer.getRatedU0() * SQRT_TWO_THIRDS;
        double rated = legRatedKv * 1.0e3 * SQRT_TWO_THIRDS;

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd),
                events("leg-t2_3_4-three-close-0.2"), PRO);

        double[] before = SwitchingWindows.leadingUpTo(0.2, 0.06, TIME_STEP);
        double[] last = {runEnd - 0.06, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (char phase : "ABC".toCharArray()) {
            double b4Dead = magnitude(run, "B4", phase, before);
            double legEnd = relative(magnitude(run, "BL_T2_3_4_3", phase, before), star);
            double onLeg = relative(magnitude(run, "B4", phase, last), magnitude(run, "BL_T2_3_4_3", phase, last));
            double onRated = relative(magnitude(run, "B4", phase, last), rated);
            System.out.printf("leg closed, phase %s: B4 dead %.2e V, leg end %.1e, B4 on the leg %.1e, on 20 kV %.4f%n", phase, b4Dead, legEnd,
                    onLeg, onRated);
            softly.assertThat(b4Dead).as("B4%s before the closing, V", phase).isLessThan(DEAD_VOLTS);
            softly.assertThat(legEnd).as("the leg's end before the closing, phase %s", phase).isLessThan(1.0e-4);
            softly.assertThat(onLeg).as("B4%s on the leg's end", phase).isLessThan(2.0e-3);
            softly.assertThat(onRated).as("B4%s against its rated voltage", phase).isLessThan(0.05);
        }
        softly.assertAll();
    }

    /**
     * A line out of service energized open-ended - its end 1 closed onto B1 at 0.2 s, its end 2 left disconnected - with and without
     * a ZnO arrester on that far end's own node, at 5 us: the open end's crest through the 60 ms after the closing, per unit of its
     * settled one, near double on phase A without the arrester (above 1.9) and clipped under 1.5 on every phase with it. From rest,
     * both: a snapshot does not hold an arrester yet (upstream refuses it).
     *
     * <p>Run 2026-11-07: 2.015, 1.542 and 1.561 pu without the arrester, 1.450, 1.358 and 1.380 with it - ATP's 2.015 and 1.451 on
     * phase A upstream.
     */
    @Test
    void anArresterClipsAnOpenEndedEnergization(@TempDir Path tempDir) {
        double runEnd = 0.3;
        double[] surge = {0.19, 0.26};
        double[] settled = {0.26, runEnd};
        double[][] crests = new double[2][3];
        for (int a = 0; a < 2; a++) {
            boolean arrester = a == 1;
            Network network = ExampleNetworks.withLoadFlow(arrester ? ExampleNetworks.createTwoBusNetworkWithArresterAtTheFarEndOfALineOutOfService()
                    : ExampleNetworks.createTwoBusNetworkWithLineDisconnectedAtBothEnds());
            PscadSimulationSettings settings = new PscadSimulationSettings(5.0e-6, runEnd, FREQUENCY, PscadEdition.PROFESSIONAL);
            PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir.resolve(arrester ? "arrester" : "bare"),
                    settings, events("branch-l1_2b-close-one-0.2"), PRO);
            for (int p = 0; p < 3; p++) {
                String channel = run.project().circuit().voltageChannel("BE_L1_2B_2", "ABC".charAt(p));
                crests[a][p] = run.output().peakAbsolute(channel, surge[0], surge[1]) / magnitude(run, "BE_L1_2B_2", "ABC".charAt(p), settled);
            }
            System.out.printf("open-ended energization, arrester %s: crests %.3f %.3f %.3f pu%n", arrester, crests[a][0], crests[a][1], crests[a][2]);
        }
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(crests[0][0]).as("phase A's crest at the open end without an arrester, pu").isGreaterThan(1.9);
        for (int p = 0; p < 3; p++) {
            softly.assertThat(crests[1][p]).as("phase %s's crest with the arrester, pu", "ABC".charAt(p)).isLessThan(1.5).isLessThan(crests[0][p]);
        }
        softly.assertAll();
    }

    private static PscadSimulationSettings settings(double runEnd) {
        return new PscadSimulationSettings(TIME_STEP, runEnd, FREQUENCY, PscadEdition.PROFESSIONAL);
    }

    private static List<EmtEvent> events(String name) {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/" + name + ".xml"));
    }

    /** The fundamental's crest at {@code node}'s phase over {@code window}, V. */
    private static double magnitude(PscadProSimulation.PscadProRun run, String node, char phase, double[] window) {
        PscadCircuit circuit = run.project().circuit();
        PscadOutput output = run.output();
        return output.fundamentalPhasor(circuit.voltageChannel(node, phase), FREQUENCY, window[0], window[1]).magnitude();
    }

    /** How far {@code to}'s phase A leads {@code from}'s over {@code window}, degrees. */
    private static double angleFrom(PscadProSimulation.PscadProRun run, String from, String to, double[] window) {
        PscadCircuit circuit = run.project().circuit();
        double fromAngle = run.output().fundamentalPhasor(circuit.voltageChannel(from, 'A'), FREQUENCY, window[0], window[1]).angleRadians();
        double toAngle = run.output().fundamentalPhasor(circuit.voltageChannel(to, 'A'), FREQUENCY, window[0], window[1]).angleRadians();
        return -Math.toDegrees(Math.IEEEremainder(toAngle - fromAngle, 2 * Math.PI));
    }

    private static double relative(double value, double reference) {
        return Math.abs(value / reference - 1.0);
    }
}
