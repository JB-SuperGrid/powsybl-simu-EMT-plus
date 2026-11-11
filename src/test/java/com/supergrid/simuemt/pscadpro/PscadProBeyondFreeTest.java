/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerPhaseAngleClockAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSteadyState;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.TimeSeriesIndex;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.within;

/**
 * The networks past PSCAD Free's 15 electrical nodes that upstream writes for the Professional edition and that no PSCAD run
 * had held yet, run in PSCAD Professional and held to what upstream holds ATP, Xyce and Kestrel to on
 * the same scenarios: the load flow, and the physics of each switching. Each check is the network's or the circuit's, not
 * another backend's run. From the steady state, but for the arrester: upstream's snapshot does not hold one yet.
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
     * phase one, from their steady state: in the first period and in the last 60 ms of 0.2 s, every bus at the load flow's voltage
     * within 1e-4 pu, and at its angle from B1, displaced by the vector groups', within 0.01 degree - upstream's bounds for the
     * three other backends. Past some twenty nodes PSCAD Professional renumbers the snapshot's nodes (the YNd11 and three-winding
     * benches), which upstream's patching reads through the snapshot's map.
     *
     * <p>Run 2026-11-07: every bus within 3.7e-5 pu and 3.5e-4 degree of the load flow, ratio tap changer or not, the same in the
     * first period as at the end - the three other backends' own level upstream (3.7e-5 pu, 4.8e-4 degree).
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
        double runEnd = 0.2;

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd), List.of(), PRO);

        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        Bus b1 = network.getBusBreakerView().getBus("B1");
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{TIME_STEP, TIME_STEP + 1.0 / FREQUENCY}, {runEnd - 0.06, runEnd}}) {
            for (Bus bus : network.getBusBreakerView().getBuses()) {
                if (bus == b1) {
                    continue;
                }
                double nominal = bus.getVoltageLevel().getNominalV();
                double puError = magnitude(run, bus.getId(), 'A', window) / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
                double expected = bus.getAngle() - b1.getAngle() + offsets.degrees(bus) - offsets.degrees(b1);
                double angleError = Math.IEEEremainder(angleFrom(run, "B1", bus.getId(), window) - expected, 360.0);
                System.out.printf("%s %s from %.5f s: %.3e pu, %.3e degree%n", name, bus.getId(), window[0], puError, angleError);
                softly.assertThat(puError).as("|V(%s)| against the load flow's, pu, %s, from %.5f s", bus.getId(), name, window[0])
                        .isCloseTo(0.0, within(1.0e-4));
                softly.assertThat(angleError)
                        .as("V(%s)'s angle from V(B1) against the load flow's, degrees, %s, from %.5f s", bus.getId(), name, window[0])
                        .isCloseTo(0.0, within(0.01));
            }
        }
        softly.assertAll();
    }

    /**
     * The two-winding phase-shifter bench fed from an ideal source with upstream's heavy magnetizing admittance on its phase-shifting
     * unit ({@code ExampleNetworks#withMagnetizingAdmittanceOnThePhaseShifters}), from its steady state over 0.2 s: upstream's {@code
     * BackendComparisonTest#allThreeBackendsDrawAPhaseShiftersMagnetizingCurrentAlike}, which ATP, Xyce and Kestrel share and no PSCAD
     * had run. The backends hang the admittance in the middle of a T, between the two halves of the unit's leakage, the load flow's pi
     * at one end: over the last 60 ms every bus within {@code |y| |z|} of the load flow, magnitude and angle, the admittance's current
     * across half the leakage; and more than 1% off the same bench without the admittance, the control. The comparison's three other
     * benches - YNd11, three-winding leg, with winding connections - are a bank of PSCAD's transformers, whose magnetizing current
     * PSCAD takes up to 50% of rated: their 159% is refused.
     *
     * <p>Run 2026-11-11: B2 and B3 within 9.5e-6 pu and 2.2e-5 rad of the load flow, against a bound of 2.6e-3; 4.03% off the control,
     * upstream's 4.0 to 4.5%.
     */
    @Test
    void aPhaseShiftersMagnetizingCurrentMovesEveryBusAsTheTDoes(@TempDir Path tempDir) {
        String name = "two-winding";
        Supplier<Network> bench = ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingTransformer;
        double runEnd = 0.2;
        double[] window = {runEnd - 0.06, runEnd};
        Network control = bench.get();
        control.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        ExampleNetworks.withLoadFlow(control);
        PscadProSimulation.PscadProRun controlRun = PscadProSimulation.runFromSteadyState(control, tempDir.resolve("control"), settings(runEnd), List.of(), PRO);
        Network network = ExampleNetworks.withMagnetizingAdmittanceOnThePhaseShifters(bench.get());
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        ExampleNetworks.withLoadFlow(network);
        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir.resolve("magnetizing"), settings(runEnd), List.of(), PRO);

        double bound = magnetizingDropAcrossTheLeakage(network);
        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        Bus b1 = network.getBusBreakerView().getBus("B1");
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            if (bus == b1) {
                continue;
            }
            double nominal = bus.getVoltageLevel().getNominalV();
            double measured = magnitude(run, bus.getId(), 'A', window);
            double withoutIt = magnitude(controlRun, bus.getId(), 'A', window);
            double puError = measured / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
            double expected = bus.getAngle() - b1.getAngle() + offsets.degrees(bus) - offsets.degrees(b1);
            double angleError = Math.toRadians(Math.IEEEremainder(angleFrom(run, "B1", bus.getId(), window) - expected, 360.0));
            double fromControl = Math.abs(measured - withoutIt) / withoutIt;
            System.out.printf(Locale.ROOT, "%s magnetizing %s: %.3e pu, %.3e rad against the load flow (bound %.3e); %.2f%% off the control%n", name, bus.getId(),
                    puError, angleError, bound, 100.0 * fromControl);
            softly.assertThat(Math.abs(puError)).as("|V(%s)| against the load flow's, pu, %s", bus.getId(), name).isLessThan(bound);
            softly.assertThat(Math.abs(angleError)).as("V(%s)'s angle from V(B1) against the load flow's, radians, %s", bus.getId(), name).isLessThan(bound);
            softly.assertThat(fromControl).as("|V(%s)| with the admittance against the control, %s", bus.getId(), name).isGreaterThan(0.01);
        }
        softly.assertAll();
    }

    /** {@code |y| |z|} of {@code network}'s two-winding phase-shifting unit: its magnetizing admittance's magnitude times half its leakage
     * impedance's - between the T's and the pi's places for it -, both on the same side. */
    private static double magnetizingDropAcrossTheLeakage(Network network) {
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            if (transformer.hasPhaseTapChanger()) {
                return Math.hypot(transformer.getG(), transformer.getB()) * Math.hypot(transformer.getR(), transformer.getX()) / 2.0;
            }
        }
        throw new IllegalArgumentException("no two-winding phase shifter in " + network.getId());
    }

    /**
     * The YNd11 phase shifter bench fed from an ideal source, 21 nodes, from a template run in PSCAD Professional - whose snapshot
     * numbers the nodes otherwise than Main.dta does, past some twenty - and its restart through a fault on B3's phase A from 0.1 to
     * 0.15 s, run 0.4 s: upstream's {@code PscadFileWriterTest#writeRestartReadsTheTemplatesNodesInTheSnapshotsOwnNumbering}, which read a
     * stored Professional run and is left out of upstream's builds since its {@code 5e6fb79d}, held to its checks on a run of the day.
     * Every bus phase on the circuit's steady state ({@code PscadSteadyState}) in the restart's first period to 5e-6 kV and in its last,
     * from 0.38 s, to 1e-6 kV, each two samples' mean too; the faulted phase above 100 kV the step before the fault and under 0.1 kV
     * at it. Read in PSCAD's numbering rather than the snapshot's, the restart is refused.
     *
     * <p>Run 2026-11-10: steady to 3.7e-6 kV in the first period - upstream's stored run's 3.7e-6 - and 3.1e-7 kV in the last; B3A
     * 249.9 kV the step before the fault, 0.9 V at it.
     */
    @Test
    void aRestartReadsTheTemplatesNodesInTheSnapshotsOwnNumbering(@TempDir Path tempDir) {
        Network network = ExampleNetworks.createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer();
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        ExampleNetworks.withLoadFlow(network);
        PscadSimulationSettings settings = new PscadSimulationSettings(TIME_STEP, 0.4, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP);

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings, events("fault-b3-a-0.1-0.15-at-order"), PRO);

        PscadCircuit circuit = run.project().circuit();
        PscadSteadyState state = PscadSteadyState.solve(circuit, settings);
        Map<String, DoubleTimeSeries> curves = run.output().curves();
        SoftAssertions softly = new SoftAssertions();
        double[] largest = new double[2];
        for (String bus : List.of("B1", "B2", "B3")) {
            for (char phase : "ABC".toCharArray()) {
                PscadSteadyState.Complex phasor = state.voltage(bus, phase);
                DoubleTimeSeries series = curves.get(circuit.voltageChannel(bus, phase));
                // PscadOutput's volts, the steady state's kV.
                double[] values = Arrays.stream(series.toArray()).map(v -> v / 1.0e3).toArray();
                TimeSeriesIndex index = series.getMetadata().getIndex();
                for (int row = 0; row < values.length; row++) {
                    double tau = index.getInstantAt(row).getEpochSecond() + index.getInstantAt(row).getNano() * 1.0e-9;
                    boolean first = tau <= 0.02 + 1.0e-9;
                    if (!first && tau < 0.38 - 1.0e-9) {
                        continue;
                    }
                    // The restart's tau is the steady state at the snapshot's 0.02 s + tau.
                    double steady = phasor.at(state.omega(), 0.02 + tau) / 1.0e3;
                    double gap = Math.abs(values[row] - steady);
                    largest[first ? 0 : 1] = Math.max(largest[first ? 0 : 1], gap);
                    softly.assertThat(values[row]).as("%s%c at %s s, kV", bus, phase, tau).isCloseTo(steady, within(first ? 5.0e-6 : 1.0e-6));
                    if (!first && row > 0) {
                        double before = index.getInstantAt(row - 1).getEpochSecond() + index.getInstantAt(row - 1).getNano() * 1.0e-9;
                        double mean = (phasor.at(state.omega(), 0.02 + before) / 1.0e3 + steady) / 2.0;
                        softly.assertThat((values[row - 1] + values[row]) / 2.0).as("mean of two samples, %s%c at %s s, kV", bus, phase, tau)
                                .isCloseTo(mean, within(1.0e-6));
                    }
                }
            }
        }
        double beforeFault = sampleAt(curves.get(circuit.voltageChannel("B3", 'A')), 0.1 - TIME_STEP);
        double atFault = sampleAt(curves.get(circuit.voltageChannel("B3", 'A')), 0.1);
        System.out.printf(Locale.ROOT, "ynd11 restart: steady to %.2e kV in the first period, %.2e in the last; B3A %.3f kV the step before the fault, %.4f at it%n",
                largest[0], largest[1], beforeFault / 1.0e3, atFault / 1.0e3);
        softly.assertThat(beforeFault / 1.0e3).as("B3A the step before the fault, kV").isGreaterThan(100.0);
        softly.assertThat(Math.abs(atFault / 1.0e3)).as("B3A at the fault, kV").isLessThan(0.1);
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

    static Stream<Arguments> windingConnections() {
        return Stream.of(
                Arguments.of(WindingConnectionType.Y_GROUNDED, WindingConnectionType.Y_GROUNDED, WindingConnectionType.DELTA, 0, 11),
                Arguments.of(WindingConnectionType.Y_GROUNDED, WindingConnectionType.DELTA, WindingConnectionType.DELTA, 1, 11),
                Arguments.of(WindingConnectionType.DELTA, WindingConnectionType.Y_GROUNDED, WindingConnectionType.Y, 11, 1),
                Arguments.of(WindingConnectionType.Y_GROUNDED, WindingConnectionType.Y_GROUNDED, WindingConnectionType.Y_GROUNDED, 6, 4));
    }

    /**
     * A three-winding transformer with winding connections, legs 2 and 3 at their clock numbers against leg 1 - a delta tertiary, a
     * delta leg 1, star-star at 6 on a leg moved and reversed -, leg 2's star point earthed through 20 ohm: a node more than PSCAD Free
     * allows on this bench. Upstream's steady-state check of it ({@code PscadSteadyStateTest#settlesAtTheLoadFlowDisplacedByEachLegsClockNumber},
     * removed with the writer's Professional tests in its {@code bd32eb0d}) run live here: from the steady state, 0.2 s, in the first
     * period and the last 60 ms every bus at the load flow's voltage within 1e-4 pu and at its angle from B2, the infinite bus,
     * displaced by its leg's clock number, within 0.01 degree - the phase shifters' bounds.
     */
    @ParameterizedTest(name = "{0}-{1}-{2} {3}/{4}")
    @MethodSource("windingConnections")
    void aThreeWindingTransformerDisplacesEachLegByItsClockNumber(WindingConnectionType connection1, WindingConnectionType connection2,
                                                                 WindingConnectionType connection3, int clock2, int clock3, @TempDir Path tempDir) {
        Network network = ExampleNetworks.createThreeBusNetworkWithThreeWindingsTransformer();
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T2_3_4");
        ThreeWindingsTransformerFortescueAdder fortescue = transformer.newExtension(ThreeWindingsTransformerFortescueAdder.class);
        fortescue.leg1().withConnectionType(connection1).withFreeFluxes(true).add();
        fortescue.leg2().withConnectionType(connection2).withFreeFluxes(true).add();
        fortescue.leg3().withConnectionType(connection3).withFreeFluxes(true).add();
        fortescue.add();
        transformer.newExtension(ThreeWindingsTransformerPhaseAngleClockAdder.class).withPhaseAngleClockLeg2(clock2).withPhaseAngleClockLeg3(clock3).add();
        transformer.getExtension(ThreeWindingsTransformerFortescue.class).getLeg2().setGroundingX(20.0);
        ExampleNetworks.withLoadFlow(network);
        double runEnd = 0.2;

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd), List.of(), PRO);

        String what = connection1 + "-" + connection2 + "-" + connection3 + " " + clock2 + "/" + clock3;
        holdsEveryBusOnTheLoadFlow(run, network, "B2", what, runEnd, new SoftAssertions()).assertAll();
    }

    /**
     * A bus whose IIDM id spells an internal node of the machine's - T_GEN, against the node GEN stands on behind its power meter - on the
     * UEL2C's two-bus bench, a line and a load on it: upstream's {@code PscadFileWriterTest#writeRenamesAnInternalNodeABusSpells}, removed
     * with the writer's Professional tests in its {@code bd32eb0d}, run live here. The bus keeps its name - the internal node takes another -
     * and holds the load flow's voltage, as B1 and B2 do: from the steady state, 0.2 s, in the first period and the last 60 ms, every bus
     * within 1e-4 pu and 0.01 degree of the load flow from B1.
     */
    @Test
    void aBusSpellingAMachinesInternalNodeKeepsItsName(@TempDir Path tempDir) {
        Network network = ExampleNetworks.createTwoBusNetworkWithUel2C(false);
        VoltageLevel vl2 = network.getVoltageLevel("VL2");
        vl2.getBusBreakerView().newBus().setId("T_GEN").add();
        network.newLine().setId("L2_T").setBus1("B2").setConnectableBus1("B2").setBus2("T_GEN").setConnectableBus2("T_GEN")
                .setR(1.0).setX(10.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0).add();
        vl2.newLoad().setId("LT").setBus("T_GEN").setConnectableBus("T_GEN").setP0(10.0).setQ0(2.0).add();
        ExampleNetworks.withLoadFlow(network);
        double runEnd = 0.2;

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd), List.of(), PRO);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(run.project().circuit().busNodes()).as("T_GEN's node, the bus's own name").containsEntry("T_GEN", "T_GEN");
        holdsEveryBusOnTheLoadFlow(run, network, "B1", "T_GEN", runEnd, softly).assertAll();
    }

    /** Every bus but {@code reference} in the first period and the last 60 ms: at the load flow's voltage within 1e-4 pu, and at its angle
     * from {@code reference}, displaced by the vector groups', within 0.01 degree. */
    private static SoftAssertions holdsEveryBusOnTheLoadFlow(PscadProSimulation.PscadProRun run, Network network, String reference, String what,
                                                             double runEnd, SoftAssertions softly) {
        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        Bus origin = network.getBusBreakerView().getBus(reference);
        for (double[] window : new double[][] {{TIME_STEP, TIME_STEP + 1.0 / FREQUENCY}, {runEnd - 0.06, runEnd}}) {
            for (Bus bus : network.getBusBreakerView().getBuses()) {
                if (bus == origin) {
                    continue;
                }
                double nominal = bus.getVoltageLevel().getNominalV();
                double puError = magnitude(run, bus.getId(), 'A', window) / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
                double expected = bus.getAngle() - origin.getAngle() + offsets.degrees(bus) - offsets.degrees(origin);
                double angleError = Math.IEEEremainder(angleFrom(run, reference, bus.getId(), window) - expected, 360.0);
                System.out.printf(Locale.ROOT, "%s %s from %.5f s: %.3e pu, %.3e degree%n", what, bus.getId(), window[0], puError, angleError);
                softly.assertThat(puError).as("|V(%s)| against the load flow's, pu, %s, from %.5f s", bus.getId(), what, window[0]).isCloseTo(0.0, within(1.0e-4));
                softly.assertThat(angleError).as("V(%s)'s angle from V(%s) against the load flow's, degrees, %s, from %.5f s", bus.getId(), reference, what,
                        window[0]).isCloseTo(0.0, within(0.01));
            }
        }
        return softly;
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

    /**
     * A chain of twelve buses, ten 400 kV lines on from B2 of the two-bus network fed by its infinite bus, each open at its far end,
     * from its steady state: in the first period and in the last 60 ms of 0.2 s, every bus at the load flow's voltage within 1e-4 pu
     * and at its angle from B1 within 0.01 degree, as the phase shifters'.
     *
     * <p>Run 2026-11-09: every bus 8.1e-6 to 8.2e-6 pu under the load flow and 2.8e-4 to 3.1e-4 degree off, in the first period as at
     * the end.
     */
    @Test
    void aChainOfTwelveBusesSitsOnTheLoadFlow(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(PscadProCases.all().filter(c -> "twelve-bus-chain".equals(c.name())).findFirst().orElseThrow()
                .network().get());
        double runEnd = 0.2;

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings(runEnd), List.of(), PRO);

        Bus b1 = network.getBusBreakerView().getBus("B1");
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{TIME_STEP, TIME_STEP + 1.0 / FREQUENCY}, {runEnd - 0.06, runEnd}}) {
            for (Bus bus : network.getBusBreakerView().getBuses()) {
                if (bus == b1) {
                    continue;
                }
                double nominal = bus.getVoltageLevel().getNominalV();
                double puError = magnitude(run, bus.getId(), 'A', window) / (nominal * 1.0e3 * SQRT_TWO_THIRDS) - bus.getV() / nominal;
                double angleError = Math.IEEEremainder(angleFrom(run, "B1", bus.getId(), window) - (bus.getAngle() - b1.getAngle()), 360.0);
                System.out.printf("twelve-bus chain %s from %.5f s: %.3e pu, %.3e degree%n", bus.getId(), window[0], puError, angleError);
                softly.assertThat(puError).as("|V(%s)| against the load flow's, pu, from %.5f s", bus.getId(), window[0]).isCloseTo(0.0, within(1.0e-4));
                softly.assertThat(angleError).as("V(%s)'s angle from V(B1) against the load flow's, degrees, from %.5f s", bus.getId(), window[0])
                        .isCloseTo(0.0, within(0.01));
            }
        }
        softly.assertAll();
    }

    /**
     * {@link #aThreeWindingTransformerLegIsClosed}'s transformer, its leg left open, run from rest rather than from its steady state:
     * in the last 60 ms of 0.4 s, B4 dead, the leg's end at the star point's voltage on its ratio within 1e-3 - the state its closing
     * starts from, settled to from rest.
     *
     * <p>Run 2026-11-09: B4 at 0 V, the leg's end within 1e-7 of the star point's voltage, as from the steady state.
     */
    @Test
    void aThreeWindingTransformerLegLeftOpenSettlesFromRest(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createThreeBusNetworkWithThreeWindingsTransformerOpenAtLegThree());
        double runEnd = 0.4;
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T2_3_4");
        double star = Double.parseDouble(transformer.getProperty("v")) * 1.0e3 * transformer.getLeg3().getRatedU() / transformer.getRatedU0()
                * SQRT_TWO_THIRDS;

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings(runEnd), List.of(), PRO);

        double[] last = {runEnd - 0.06, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (char phase : "ABC".toCharArray()) {
            double b4Dead = magnitude(run, "B4", phase, last);
            double legEnd = relative(magnitude(run, "BL_T2_3_4_3", phase, last), star);
            System.out.printf("leg left open from rest, phase %s: B4 dead %.2e V, leg end %.1e%n", phase, b4Dead, legEnd);
            softly.assertThat(b4Dead).as("B4%s, V", phase).isLessThan(DEAD_VOLTS);
            softly.assertThat(legEnd).as("the leg's end against the star point's voltage on its ratio, phase %s", phase).isLessThan(1.0e-3);
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

    /** {@code series}'s sample at {@code time}, the one nearest it, V. */
    private static double sampleAt(DoubleTimeSeries series, double time) {
        double[] values = series.toArray();
        TimeSeriesIndex index = series.getMetadata().getIndex();
        int nearest = 0;
        double gap = Double.MAX_VALUE;
        for (int row = 0; row < values.length; row++) {
            double t = index.getInstantAt(row).getEpochSecond() + index.getInstantAt(row).getNano() * 1.0e-9;
            if (Math.abs(t - time) < gap) {
                gap = Math.abs(t - time);
                nearest = row;
            }
        }
        return values[nearest];
    }

    private static double relative(double value, double reference) {
        return Math.abs(value / reference - 1.0);
    }
}
