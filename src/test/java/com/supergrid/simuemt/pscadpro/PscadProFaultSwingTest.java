/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.TimeSeriesIndex;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.within;

/**
 * The large networks' faults of {@link PscadProCases}, past PSCAD Free's 15 nodes, run in PSCAD Professional and held to what
 * their runs by hand showed on 2026-11-08 - no other backend runs them here, so each check is the network's physics: the swing
 * between its areas, if it has machines, the DC grid riding the fault, every station back on its own law.
 *
 * <p>The swing is the angle between the two areas' centres of inertia, each machine weighted by {@code H S}, integrated from their
 * speeds from the fault on; its frequency and damping from its successive extremes after the clearing, the damping ratio from the
 * logarithmic decrement of their peak-to-peak amplitudes a period apart. The DC voltages within their bounds from the fault to the
 * end. At the end each station back on its law, from the load flow - a run from rest is not always settled when the fault comes,
 * CIGRE B4's DC voltages still moving by 6e-4 pu at 2.5 s: the DC voltage's on its voltage within 1e-3 pu, the others on their power
 * within 1e-3 of their rating, along their droop from where the DC voltage settles - elsewhere when a cable is cut.
 *
 * <p>Run 2026-11-09, at 20 us, 8 s, from rest, the fault at 2.5 s cleared at 2.6 s: Kundur's two areas, the fault at bus 7, area 1
 * swinging 20.4 degrees against area 2 at 0.595 Hz, zeta 0.201 on the PSSs, the DC voltages within 0.944 and 1.053 pu; at bus 8,
 * the tie's middle, under 1 degree, the DC voltages within 0.980 and 1.040 pu. IEEE 39, the fault at bus 16: New England's nine
 * machines 20.6 degrees against G39 at 0.601 Hz, zeta 0.165 (G34 alone 33.4 degrees), CS16's AC bus down to 0.14 pu, its power
 * reversed (-0.51 to +0.54 of its rating), the DC voltages within 0.944 and 1.115 pu. IEEE 68, the fault at bus 1, at 50 us: New
 * England 2.60 degrees against New York, area 5 2.86 - several modes mixed, the extremes no decaying sine's -, every machine's
 * speed within 0.004 Hz peak to peak from 7 s (G67's; New England's within 0.0002), the DC voltages within 0.984 and 1.008 pu.
 * IEEE 14, the fault at bus 4, run 8 s: the DC voltages within 0.986 and 1.011 pu, the machines within 0.0065 Hz peak to peak over
 * the last second, the synchronous condensers G6 and G8 still 0.030 and 0.024 Hz, their 1.4 Hz mode a tenth of what it was 1 s
 * after the fault. CIGRE B4, the fault at BAB1, run 8 s: the DC voltages within 0.914 and 1.071 pu. PROMOTioN, the fault at A1's
 * grid and at B1's, run 5 s: the DC voltages within 0.966 and 1.087 pu, and 0.991 and 1.110; on cable D1-D2 at D1, its two
 * breakers opened 3 ms on, run 5 s: within 0.866 and 1.099 pu, B1 on its droop at the DC voltage the cut grid settles to, 1.2e-3 pu
 * under its own before. Every station of every case on its law within 1e-4 of its rating.
 *
 * <p>The same networks losing a station at 2.5 s, its AC breaker opened at 2.54 s, run 8 s - 2026-11-09. Kundur's CS7, area 1's
 * 200 MW to area 2 back on the tie: area 1 swinging 18.9 degrees against area 2 at 0.566 Hz, zeta 0.207, to its new angle, 12.5
 * degrees on; CS9 delivering the wind's 200 MW alone; the DC voltages within 0.945 and 1.016 pu. IEEE 39's CS4, 350 MW into the
 * load centre: CS16 at the hub taking it up to its current limit, 1.1 pu - 1.108 of its rating -, the DC voltage left to rise to
 * 1.0075 pu, where CS26's droop takes up the rest, 0.633 to 0.544 of its rating; New England 3.4 degrees against G39 at 0.590 Hz, zeta
 * 0.170; the DC voltages within 0.995 and 1.077 pu. IEEE 68's CS33, New York's 400 MW import, at 50 us: CS16 importing 0.357 of its
 * rating less, down to 0.026; New England 5.1 degrees against New York, area 5 3.5; the DC voltages within 0.992 and 1.038 pu. The
 * centre of inertia's frequency down 0.1, 2.3 and 3.3 mHz at most - the power moved within one AC network, not lost to it; every
 * machine's speed within 0.004 Hz peak to peak over the last second. A station on its current limit is held to it instead of its
 * law; the station lost to none of its power from 0.2 s on.
 *
 * <p>The same, the V_DC station lost - nothing holding the DC voltage any more, the droops left to settle it where they balance the
 * power, as their laws have it. Kundur's CS9, area 2's 400 MW: CS7, its only droop, reversed from taking 0.613 of its rating to
 * delivering 0.644, the wind's 200 MW, the DC voltage up to 1.131 pu, 1.175 at most; area 1 swinging 42.2 degrees against area 2 at
 * 0.527 Hz, zeta 0.21. IEEE 39's CS16: CS4 up from 0.654 to 0.962 of its rating, CS26 down from 0.633 to 0.311, the DC voltage up to
 * 1.026 to 1.038 pu; New England 1.3 degrees against G39 at 0.609 Hz, zeta 0.175. IEEE 68's CS16, at 50 us: the DC voltage down to
 * 0.977 pu, CS41 exporting more, CS33 and CS52 importing less; New England 5.9 degrees against New York, area 5 5.1, drifting to
 * their new angles. The nadir 9.2, 2.2 and 10.2 mHz. CSW, grid-forming, answers the DC overvoltage on its own DC voltage droop, its
 * island speeding up - to 1.026 pu by 8 s on Kundur -, the wind park deaf to it: CSW left 0.017 of its rating under the wind's power
 * there, the island's losses at that frequency.
 *
 * <p>IEEE 39 with a high share of power electronics, run 8 s at 50 us, the fault or bus 16's load up 494 MW at 2.5 s - the runs of
 * 2026-11-08 again. The fault: New England against G39 21.4 degrees at 0.594 Hz, zeta 0.173, with its machines; 33.2 degrees at
 * 0.680 Hz, zeta 0.160, four of them grid-following, no inertia of the converters'; 20.3 degrees at 0.464 Hz, zeta 0.296,
 * grid-forming, their virtual machines back within 0.0015 of their power; six replaced, 15.8 degrees grid-forming, 30.4 mixed (CL33
 * alone grid-forming) - six grid-following, the network is lost, G31 out of step: held apart ({@link #ieee39WithSixGridFollowingSourcesIsLostThroughTheFault}). The load step: the centre of inertia's
 * nadir 80, 110 and 67 mHz, its RoCoF over 500 ms -0.075, -0.100 and -0.048 Hz/s, with the machines, grid-following and
 * grid-forming; 141 and 64 mHz, -0.115 and -0.038 Hz/s, six replaced; the grid-forming stations' virtual machines taking up 0.024 to
 * 0.027 of their rating for good, New England settling on its new angle without swinging.
 * Every channel is written, a sample a millisecond, to {@code target/fault-swing/<case>.csv}. About 3 minutes a case;
 * {@code -Dpscad.faults=ieee68-dc-fault-b1,...} runs only those.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProFaultSwingTest {

    private static final double FREQUENCY = 50.0;
    /** A fault's duration, s: every case's is cleared 100 ms on. */
    private static final double FAULT_DURATION = 0.1;
    /** Every channel, one sample a millisecond, to {@code target/fault-swing/<case>.csv}. */
    private static final double CSV_STEP = 1.0e-3;
    /** Two extremes of the swing closer than this are one: a few ripples on a crest. */
    private static final double HALF_PERIOD_MIN = 0.3;
    /** For how long, at the end of the run, the machines' speeds are held settled, s. */
    private static final double SETTLED = 1.0;

    /** One swing, {@code area1}'s centre of inertia against {@code area2}'s - {@code area1} empty for every other machine of the
     * network's -, its largest angle in degrees within {@code [min, max]}; its frequency and damping ratio, {@code NaN} for a swing too
     * small to read them off. */
    record Swing(String label, List<String> area1, List<String> area2, double min, double max, double frequency, double damping) {
    }

    /** The frequency of every machine's centre of inertia after the event, from where it was over the 100 ms before: its nadir, mHz,
     * within {@code [nadirMin, nadirMax]}, and its RoCoF over the first 500 ms, Hz/s, within {@code [rocofMin, rocofMax]}. */
    record Frequency(double nadirMin, double nadirMax, double rocofMin, double rocofMax) {
    }

    /**
     * A case: when its fault or its load step comes, s; how long it runs, s, {@code NaN} for the case's own; its swings, none in a
     * network without machines; its DC voltages' bounds from the event on; the largest peak-to-peak speed any machine of the network's
     * is left with over the run's last {@link #SETTLED}, Hz, {@code NaN} not to hold it; how close every station is back on its law at
     * the end, of its rating or pu - a grid-forming one's, {@code gridFormingRecovery}, its virtual machine taking up its share of a
     * load step for good; and its centre of inertia's frequency, {@code null} not to hold it.
     */
    record Expected(String name, double fault, double runEnd, List<Swing> swings, double vdcMin, double vdcMax, double settledHz,
                    double recovery, double gridFormingRecovery, Frequency frequency) {

        Expected(String name, double fault, double runEnd, List<Swing> swings, double vdcMin, double vdcMax, double settledHz, double recovery) {
            this(name, fault, runEnd, swings, vdcMin, vdcMax, settledHz, recovery, recovery, null);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> faults() {
        List<String> kundur1 = List.of("G1", "G2");
        List<String> kundur2 = List.of("G3", "G4");
        // IEEE 68's areas: New England's nine machines, New York's four, and areas 3, 4 and 5, one equivalent machine each.
        List<String> newEngland68 = List.of("G53", "G54", "G55", "G56", "G57", "G58", "G59", "G60", "G61");
        List<String> newYork = List.of("G62", "G63", "G64", "G65");
        return Stream.concat(Stream.of(
                new Expected("kundur-dc-fault-b7", 2.5, Double.NaN, List.of(new Swing("area 1 against area 2", kundur1, kundur2, 18.0, 23.0, 0.595,
                        0.20)), 0.93, 1.07, Double.NaN, 1.0e-3),
                new Expected("kundur-dc-fault-b8", 2.5, Double.NaN, List.of(new Swing("area 1 against area 2", kundur1, kundur2, 0.0, 1.5, Double.NaN,
                        Double.NaN)), 0.97, 1.05, Double.NaN, 1.0e-3),
                new Expected("ieee39-dc-fault-b16", 2.5, Double.NaN, List.of(new Swing("New England against G39",
                        List.of("G30", "G31", "G32", "G33", "G34", "G35", "G36", "G37", "G38"), List.of("G39"), 18.5, 23.5, 0.60, 0.165)), 0.93, 1.13,
                        Double.NaN, 1.0e-3),
                // Its swings mix several modes, their extremes not a decaying sine's: their damping held through every machine's speed.
                new Expected("ieee68-dc-fault-b1", 2.5, Double.NaN, List.of(
                        new Swing("New England against New York", newEngland68, newYork, 2.0, 3.3, Double.NaN, Double.NaN),
                        new Swing("area 5 against New York", List.of("G68"), newYork, 2.2, 3.6, Double.NaN, Double.NaN)), 0.98, 1.012, 0.006, 1.0e-3),
                // Run 8 s and 5 s, not the converters' scenarios' 3, for their machines and stations to settle; IEEE 14's synchronous
                // condensers G6 and G8 swing at 1.4 Hz, slower to die out.
                new Expected("ieee14-dc-fault-b4", 2.5, 8.0, List.of(), 0.975, 1.025, 0.04, 1.0e-3),
                new Expected("cigre-b4-fault-b1", 2.5, 8.0, List.of(), 0.90, 1.085, Double.NaN, 1.0e-3),
                new Expected("promotion-dc-ac-fault-a1", 2.3, 5.0, List.of(), 0.95, 1.10, Double.NaN, 1.0e-3),
                new Expected("promotion-dc-ac-fault-b1", 2.3, 5.0, List.of(), 0.975, 1.125, Double.NaN, 1.0e-3),
                new Expected("promotion-dc-fault-cd1d2", 2.3, 5.0, List.of(), 0.85, 1.115, Double.NaN, 1.0e-3)
        ), Stream.concat(stationLosses(), ieee39Ibr())).filter(PscadProFaultSwingTest::selected).map(Arguments::of);
    }

    /**
     * The three large networks each losing a station in full flow, blocked, its AC breaker opened 40 ms on - its power gone, the others'
     * on their laws: IEEE 39's CS16 taking up CS4's 350 MW on its current limit, the DC voltage rising until CS26's droop takes the rest.
     * Each station carried power between two parts of one AC network: its loss shifts the flows, the areas swinging to their new angle,
     * the centre of inertia's frequency hardly moving.
     */
    private static Stream<Expected> stationLosses() {
        List<String> newEngland68 = List.of("G53", "G54", "G55", "G56", "G57", "G58", "G59", "G60", "G61");
        List<String> newYork = List.of("G62", "G63", "G64", "G65");
        Frequency unmoved = new Frequency(-6.0, 0.0, -0.1, 0.1);
        return Stream.of(
                new Expected("kundur-dc-loss-cs7", 2.5, Double.NaN, List.of(new Swing("area 1 against area 2", List.of("G1", "G2"), List.of("G3", "G4"),
                        16.5, 21.5, 0.566, 0.207)), 0.93, 1.03, 0.006, 1.0e-3, 1.0e-3, unmoved),
                new Expected("ieee39-dc-loss-cs4", 2.5, Double.NaN, List.of(new Swing("New England against G39",
                        List.of("G30", "G31", "G32", "G33", "G34", "G35", "G36", "G37", "G38"), List.of("G39"), 2.8, 4.0, 0.590, 0.170)), 0.98, 1.09,
                        0.005, 1.0e-3, 2.0e-3, unmoved),
                // Its swings mix several modes, their extremes not a decaying sine's: their damping held through every machine's speed.
                new Expected("ieee68-dc-loss-cs33", 2.5, Double.NaN, List.of(
                        new Swing("New England against New York", newEngland68, newYork, 4.3, 5.9, Double.NaN, Double.NaN),
                        new Swing("area 5 against New York", List.of("G68"), newYork, 2.9, 4.1, Double.NaN, Double.NaN)), 0.98, 1.045, 0.003, 1.0e-3,
                        1.0e-3, unmoved),
                // Their V_DC stations lost: the DC voltage left to the droops. CSW, grid-forming, answers the DC overvoltage along its own
                // DC voltage droop by lowering its power reference: its island, the wind park deaf to frequency, speeding up instead - 1.026
                // pu by the end on Kundur, still rising -, CSW left on the wind's power less the island's losses at that frequency.
                new Expected("kundur-dc-loss-cs9", 2.5, Double.NaN, List.of(new Swing("area 1 against area 2", List.of("G1", "G2"), List.of("G3", "G4"),
                        37.0, 47.0, 0.527, 0.21)), 0.99, 1.19, 0.012, 1.0e-3, 0.025, new Frequency(-14.0, 0.0, -0.05, 0.13)),
                new Expected("ieee39-dc-loss-cs16", 2.5, Double.NaN, List.of(new Swing("New England against G39",
                        List.of("G30", "G31", "G32", "G33", "G34", "G35", "G36", "G37", "G38"), List.of("G39"), 1.0, 1.6, 0.609, 0.175)), 0.98, 1.07,
                        0.003, 1.0e-3, 6.0e-3, unmoved),
                // New York's import lost with it: New England and area 5 drifting slowly to their new angles against it, too few extremes
                // in 8 s to read a frequency off.
                new Expected("ieee68-dc-loss-cs16", 2.5, Double.NaN, List.of(
                        new Swing("New England against New York", newEngland68, newYork, 5.0, 6.9, Double.NaN, Double.NaN),
                        new Swing("area 5 against New York", List.of("G68"), newYork, 4.3, 5.9, Double.NaN, Double.NaN)), 0.94, 1.01, 0.003, 1.0e-3,
                        1.0e-3, new Frequency(-15.0, 0.0, -0.05, 0.05)));
    }

    /**
     * IEEE 39 with a high share of power electronics ({@link Ieee39IbrNetwork}): its machines, then four, then six of them replaced by
     * grid-following, grid-forming or mixed converter sources, through a fault and a load step at bus 16 - but the six replaced
     * grid-following through the fault, where the network is lost, G31 out of step, held apart. Every machine left against G39.
     */
    private static Stream<Expected> ieee39Ibr() {
        List<String> g39 = List.of("G39");
        String label = "New England against G39";
        double nan = Double.NaN;
        return Stream.of(
                // A solid three-phase fault, 100 ms
                new Expected("ieee39-sync-fault-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 19.0, 24.0, 0.594, 0.173)), 0.0, 9.0, 0.03,
                        1.0e-3),
                new Expected("ieee39-gfl-fault-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 29.5, 37.0, 0.680, 0.160)), 0.85, 1.12, 0.07,
                        1.0e-3),
                new Expected("ieee39-gfm-fault-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 18.0, 22.5, 0.464, 0.296)), 0.86, 1.115, 0.006,
                        1.0e-3, 0.005, null),
                // Six machines replaced, the swings no decaying sine's past the first: their damping held through every machine's speed.
                new Expected("ieee39-gfm65-fault-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 14.0, 17.5, nan, nan)), 0.86, 1.11, 0.007,
                        1.0e-3, 0.005, null),
                new Expected("ieee39-mix65-fault-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 27.0, 34.0, nan, nan)), 0.85, 1.115, 0.01,
                        1.0e-3, 0.005, null),
                // Bus 16's load up 494 MW. Grid-forming, New England settles on its new angle without swinging: its amplitude alone.
                new Expected("ieee39-sync-load-step-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 6.2, 7.8, 0.600, 0.228)), 0.0, 9.0, 0.005,
                        1.0e-3, 1.0e-3, new Frequency(-87.0, -74.0, -0.083, -0.068)),
                new Expected("ieee39-gfl-load-step-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 8.8, 11.0, 0.687, 0.201)), 0.97, 1.01, 0.01,
                        1.0e-3, 1.0e-3, new Frequency(-119.0, -102.0, -0.110, -0.090)),
                new Expected("ieee39-gfm-load-step-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 4.3, 5.5, nan, nan)), 0.97, 1.01, 0.002,
                        1.0e-3, 0.035, new Frequency(-73.0, -62.0, -0.053, -0.043)),
                new Expected("ieee39-gfl65-load-step-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 10.0, 12.3, 0.784, 0.133)), 0.97, 1.01,
                        0.02, 1.0e-3, 1.0e-3, new Frequency(-152.0, -130.0, -0.127, -0.104)),
                new Expected("ieee39-gfm65-load-step-b16", 2.5, nan, List.of(new Swing(label, List.of(), g39, 3.0, 3.8, nan, nan)), 0.97, 1.01, 0.0015,
                        1.0e-3, 0.035, new Frequency(-69.0, -59.0, -0.042, -0.034)));
    }

    /** All the faults, or those {@code -Dpscad.faults=kundur-dc-fault-b7,...} names. */
    private static boolean selected(Expected expected) {
        String names = System.getProperty("pscad.faults", "");
        return names.isBlank() || List.of(names.split(",")).contains(expected.name());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void aLargeNetworkRidesItsFault(Expected expected) throws IOException {
        PscadProCases.Case fault = PscadProCases.all().filter(c -> c.name().equals(expected.name())).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(fault.network().get());
        Path workDir = Files.createDirectories(Path.of("target", "fault-swing", expected.name()));

        PscadSimulationSettings settings = fault.settings();
        if (!Double.isNaN(expected.runEnd())) {
            settings = new PscadSimulationSettings(settings.timeStepSeconds(), expected.runEnd(), settings.nominalFrequencyHz(), settings.edition(),
                    settings.switching());
        }

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, workDir, settings, fault.events(), PscadProSettings.defaults());

        PscadOutput output = run.output();
        Map<String, DoubleTimeSeries> curves = output.curves();
        writeCsv(curves, workDir.resolveSibling(expected.name() + ".csv"));
        double[] time = times(curves);
        SoftAssertions softly = new SoftAssertions();
        for (Swing expectedSwing : expected.swings()) {
            List<String> area1 = expectedSwing.area1().isEmpty() ? machines(network).stream().filter(m -> !expectedSwing.area2().contains(m)).toList()
                    : expectedSwing.area1();
            double[] swing = swing(network, curves, time, area1, expectedSwing.area2(), expected.fault());
            List<double[]> extremes = extremes(time, swing, expected.fault() + FAULT_DURATION);
            double largest = 0.0;
            for (int i = 0; i < time.length; i++) {
                largest = time[i] >= expected.fault() ? Math.max(largest, Math.abs(swing[i])) : largest;
            }
            String label = expectedSwing.label();
            System.out.printf(Locale.ROOT, "%s, %s: swing %.2f degrees, extremes %s%n", expected.name(), label, largest,
                    extremes.stream().map(e -> String.format(Locale.ROOT, "%.3f s %+.2f", e[0], e[1])).toList());
            softly.assertThat(largest).as("%s, degrees", label).isBetween(expectedSwing.min(), expectedSwing.max());
            if (!Double.isNaN(expectedSwing.frequency())) {
                double frequency = (extremes.size() - 1) / (2.0 * (extremes.get(extremes.size() - 1)[0] - extremes.get(0)[0]));
                double damping = damping(extremes);
                System.out.printf(Locale.ROOT, "%s, %s: %.3f Hz, zeta %.3f%n", expected.name(), label, frequency, damping);
                softly.assertThat(frequency).as("%s, frequency, Hz", label).isCloseTo(expectedSwing.frequency(), within(0.03));
                softly.assertThat(damping).as("%s, damping ratio", label).isCloseTo(expectedSwing.damping(), within(0.03));
            }
        }
        double runEnd = time[time.length - 1];
        List<String> lost = fault.events().stream().filter(ConverterBlocking.class::isInstance).map(e -> ((ConverterBlocking) e).converterId()).toList();
        if (expected.frequency() != null) {
            double[] centre = centreOfInertia(network, curves, machines(network));
            double before = 0.0;
            int count = 0;
            double nadir = 0.0;
            double atEvent = Double.NaN;
            double afterHalfSecond = Double.NaN;
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= expected.fault() - 0.1 && time[i] < expected.fault()) {
                    before += centre[i];
                    count++;
                }
            }
            before /= count;
            for (int i = 0; i < time.length; i++) {
                double deviation = (centre[i] - before) * FREQUENCY * 1.0e3;
                if (time[i] >= expected.fault()) {
                    nadir = Math.min(nadir, deviation);
                    atEvent = Double.isNaN(atEvent) ? deviation : atEvent;
                }
                if (time[i] >= expected.fault() + 0.5 && Double.isNaN(afterHalfSecond)) {
                    afterHalfSecond = deviation;
                }
            }
            double rocof = (afterHalfSecond - atEvent) / 0.5 / 1.0e3;
            System.out.printf(Locale.ROOT, "%s: centre of inertia's nadir %.1f mHz, RoCoF over 500 ms %.4f Hz/s%n", expected.name(), nadir, rocof);
            softly.assertThat(nadir).as("the centre of inertia's nadir, mHz").isBetween(expected.frequency().nadirMin(), expected.frequency().nadirMax());
            softly.assertThat(rocof).as("the centre of inertia's RoCoF over 500 ms, Hz/s").isBetween(expected.frequency().rocofMin(),
                    expected.frequency().rocofMax());
        }
        if (!Double.isNaN(expected.settledHz())) {
            for (String machine : machines(network)) {
                double[] w = curves.get(machine + "_W").toArray();
                double low = Double.MAX_VALUE;
                double high = -Double.MAX_VALUE;
                for (int i = 0; i < time.length; i++) {
                    if (time[i] >= runEnd - SETTLED) {
                        low = Math.min(low, w[i]);
                        high = Math.max(high, w[i]);
                    }
                }
                double peakToPeak = (high - low) * FREQUENCY;
                System.out.printf(Locale.ROOT, "%s %s: speed %.4f Hz peak to peak over the last %.0f s%n", expected.name(), machine, peakToPeak,
                        SETTLED);
                softly.assertThat(peakToPeak).as("%s's speed peak to peak over the last %.0f s, Hz", machine, SETTLED).isLessThan(expected.settledHz());
            }
        }
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            String station = converter.getId();
            double vdcLow = Double.MAX_VALUE;
            double vdcHigh = -Double.MAX_VALUE;
            double[] vdc = curves.get(station + "_VDC").toArray();
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= expected.fault()) {
                    vdcLow = Math.min(vdcLow, vdc[i]);
                    vdcHigh = Math.max(vdcHigh, vdc[i]);
                }
            }
            double pBefore = output.mean(station + "_P", expected.fault() - 0.1, expected.fault());
            double pEnd = output.mean(station + "_P", runEnd - 0.04, runEnd);
            double vdcBefore = output.mean(station + "_VDC", expected.fault() - 0.1, expected.fault());
            double vdcEnd = output.mean(station + "_VDC", runEnd - 0.04, runEnd);
            System.out.printf(Locale.ROOT, "%s %s: V_DC %.4f to %.4f pu, %.5f before, %.5f at the end; P %.4f before, %.4f at the end%n",
                    expected.name(), station, vdcLow, vdcHigh, vdcBefore, vdcEnd, pBefore, pEnd);
            softly.assertThat(vdcLow).as("%s's lowest DC voltage, pu", station).isGreaterThan(expected.vdcMin());
            softly.assertThat(vdcHigh).as("%s's highest DC voltage, pu", station).isLessThan(expected.vdcMax());
            // Each station back on its own law, from the load flow - a run from rest not always settled yet when the fault comes: the DC
            // voltage's on its voltage, the others on their power, along their droop, if any, from where the DC voltage settles, which a
            // grid cut short of a cable settles elsewhere.
            AveragedConverterModel model = (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class);
            AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
            double rating = model.getRatedApparentPower();
            if (lost.contains(station)) {
                // A station lost: blocked, its AC breaker open, none of its power left.
                softly.assertThat(output.peakAbsolute(station + "_P", expected.fault() + 0.2, runEnd)).as("%s's power once lost, of its rating", station)
                        .isLessThan(1.0e-4);
            } else if (converter.getControlMode() == AcDcConverter.ControlMode.V_DC) {
                // On its voltage - or, asked for more than its current limit lets through, on that limit, the DC voltage left to the droops.
                double maxCurrent = GridFollowingControl.of(converter).getCurrentLimit().maxCurrent();
                double currentEnd = Math.hypot(pEnd, output.mean(station + "_Q", runEnd - 0.04, runEnd)) / output.mean(station + "_VAC", runEnd - 0.04, runEnd);
                if (currentEnd > maxCurrent - expected.recovery()) {
                    System.out.printf(Locale.ROOT, "%s %s: on its current limit, %.5f of %.2f%n", expected.name(), station, currentEnd, maxCurrent);
                    softly.assertThat(currentEnd).as("%s's current at the end against its limit, pu", station).isCloseTo(maxCurrent,
                            within(expected.recovery()));
                } else {
                    double lfVdc = initial.dcVoltage() / initial.dcNominalVoltage();
                    softly.assertThat(vdcEnd).as("%s's DC voltage at the end against the load flow's, pu", station).isCloseTo(lfVdc,
                            within(expected.recovery()));
                }
            } else {
                double pExpected = (converter.getTerminal1().getP() - DcVoltageDroopLaw.of(converter, initial)
                        .map(law -> law.powerReduction(vdcEnd) * initial.baseApparentPower()).orElse(0.0)) / rating;
                System.out.printf(Locale.ROOT, "%s %s: P %.4f expected at the end%n", expected.name(), station, pExpected);
                double tolerance = converter.getExtension(VscControlModel.class) instanceof GridFormingControl ? expected.gridFormingRecovery()
                        : expected.recovery();
                softly.assertThat(pEnd).as("%s's power at the end against the load flow's along its droop, of its rating", station)
                        .isCloseTo(pExpected, within(tolerance));
            }
        }
        softly.assertAll();
    }

    /** When a station blocks, ms after a DC fault, within {@code [min, max]}. */
    record Blocking(String station, double min, double max) {
    }

    /**
     * PROMOTioN's five terminals through a solid pole-to-pole fault at D1, the most meshed node, from 2.3 s, run 3 s - no station back
     * on its law, every one blocked; held to their runs by hand of 2026-11-08.
     *
     * <p>Its stations protected against their arms' overcurrent, nothing clearing it: every station blocks on its own overcurrent,
     * nearest first, its DC current up to 4.8 to 5.4 pu - run 2026-11-09, D1 0.4 ms on, C2 12.6, D2 13.3, B1 35.8, A1 93.6 - then the
     * half-bridges' diodes feed the fault from the AC grids, the onshore PCCs down to 0.22 and 0.29 pu and staying under 0.4, the DC
     * voltages down under 0.16 pu, never up to A1's and B1's choppers.
     *
     * <p>Cleared the non-selective way, as PROMOTioN's D4.2 has it: every station blocked on the fault's detection, 2 ms on, then
     * its AC breaker opening, 40 ms on - the AC voltages up to 1.22 pu (D2's), then gone; the DC voltages down to -0.08 pu; the DC
     * currents freewheeling through the diodes into the fault, D1's gone by 2.7 s, the others' decaying, 0.29 to 0.53 pu still at 3 s.
     * About 1 minute a case.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"promotion-dc-fault-d1", "promotion-dc-fault-d1-cleared"})
    void aDcFaultBlocksEveryStation(String name) throws IOException {
        PscadProCases.Case fault = PscadProCases.all().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(fault.network().get());
        Path workDir = Files.createDirectories(Path.of("target", "fault-swing", name));

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, workDir, fault.settings(), fault.events(), PscadProSettings.defaults());

        Map<String, DoubleTimeSeries> curves = run.output().curves();
        writeCsv(curves, workDir.resolveSibling(name + ".csv"));
        double[] time = times(curves);
        double faultTime = 2.3;
        boolean cleared = name.endsWith("-cleared");
        List<Blocking> blockings = cleared
                ? Stream.of("CD1", "CC2", "CD2", "CB1", "CA1").map(s -> new Blocking(s, 0.0, 2.5)).toList()
                : List.of(new Blocking("CD1", 0.0, 2.0), new Blocking("CC2", 10.0, 16.0), new Blocking("CD2", 10.0, 17.0), new Blocking("CB1", 30.0, 42.0),
                        new Blocking("CA1", 85.0, 105.0));
        SoftAssertions softly = new SoftAssertions();
        for (Blocking blocking : blockings) {
            String station = blocking.station();
            double[] running = curves.get(station + "_RUN").toArray();
            double[] idc = curves.get(station + "_IDC").toArray();
            double[] vac = curves.get(station + "_VAC").toArray();
            double[] vdc = curves.get(station + "_VDC").toArray();
            double blocked = Double.NaN;
            double peak = 0.0;
            double vacLow = Double.MAX_VALUE;
            double vacHigh = 0.0;
            double vdcLow = Double.MAX_VALUE;
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= faultTime) {
                    blocked = Double.isNaN(blocked) && running[i] < 0.5 ? (time[i] - faultTime) * 1.0e3 : blocked;
                    peak = Math.max(peak, Math.abs(idc[i]));
                    vacLow = Math.min(vacLow, vac[i]);
                    vacHigh = Math.max(vacHigh, vac[i]);
                    vdcLow = Math.min(vdcLow, vdc[i]);
                }
            }
            double end = time[time.length - 1];
            double idcLate = run.output().mean(station + "_IDC", 2.68, 2.72);
            double idcEnd = run.output().mean(station + "_IDC", end - 0.02, end);
            double vacEnd = run.output().mean(station + "_VAC", end - 0.02, end);
            double vdcEnd = run.output().mean(station + "_VDC", end - 0.02, end);
            System.out.printf(Locale.ROOT, "%s %s: blocked %.1f ms on, |I_DC| up to %.2f pu, %.3f at 2.7 s, %.3f at the end; V_AC %.3f to %.3f, %.3f at"
                    + " the end; V_DC from %.3f, %.3f at the end%n", name, station, blocked, peak, idcLate, idcEnd, vacLow, vacHigh, vacEnd, vdcLow, vdcEnd);
            softly.assertThat(blocked).as("%s blocked, ms after the fault", station).isBetween(blocking.min(), blocking.max());
            if (cleared) {
                softly.assertThat(vacHigh).as("%s's highest AC voltage, pu", station).isLessThan(1.25);
                softly.assertThat(vacEnd).as("%s's AC voltage at the end, its breaker open, pu", station).isLessThan(0.01);
                softly.assertThat(vdcLow).as("%s's lowest DC voltage, pu", station).isGreaterThan(-0.1);
                if ("CD1".equals(station)) {
                    softly.assertThat(Math.abs(idcLate)).as("D1's DC current at 2.7 s, pu").isLessThan(0.01);
                } else {
                    softly.assertThat(idcEnd).as("%s's DC current at the end, decaying, pu", station).isBetween(0.15, 0.7).isLessThan(idcLate);
                }
            } else {
                softly.assertThat(peak).as("%s's DC current's peak, pu", station).isBetween(4.4, 5.6);
                softly.assertThat(vdcEnd).as("%s's DC voltage at the end, pu", station).isLessThan(0.25);
                if (List.of("CA1", "CB1").contains(station)) {
                    softly.assertThat(vacLow).as("%s's PCC's lowest AC voltage, pu", station).isBetween(0.18, 0.33);
                    softly.assertThat(vacEnd).as("%s's PCC's AC voltage at the end, nothing clearing the fault, pu", station).isLessThan(0.5);
                }
            }
        }
        for (String chopper : List.of("CA1_PCHOP", "CB1_PCHOP")) {
            softly.assertThat(Arrays.stream(curves.get(chopper).toArray()).max().orElseThrow()).as("%s, the DC voltage never up to it", chopper)
                    .isZero();
        }
        softly.assertAll();
    }

    /**
     * IEEE 39 with six machines replaced by grid-following converter sources, through the solid three-phase fault at bus 16: the
     * network lost, as its run by hand of 2026-11-08 had it - what {@link #aLargeNetworkRidesItsFault} leaves out, held here so that
     * the limit stays known. The grid-following stations' AC voltages held near 0.85 pu after the clearing, G31, the machine nearest
     * the fault, speeding up and out of step against the other machines' centre of inertia within 0.75 s, every grid-following
     * station's AC voltage past 1.3 or under 0.3 pu by then. Past that the run no longer means anything: its voltages run away to
     * hundreds of pu.
     *
     * <p>Run 2026-11-09: the stations' AC voltages 0.77 to 0.86 pu just after the clearing, out of [0.3, 1.3] pu 0.35 to 0.46 s
     * after it; G31 up to 1.0558 pu, out of step 0.61 s after it.
     */
    @Test
    void ieee39WithSixGridFollowingSourcesIsLostThroughTheFault() throws IOException {
        String name = "ieee39-gfl65-fault-b16";
        PscadProCases.Case fault = PscadProCases.all().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(fault.network().get());
        Path workDir = Files.createDirectories(Path.of("target", "fault-swing", name));

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, workDir, fault.settings(), fault.events(), PscadProSettings.defaults());

        Map<String, DoubleTimeSeries> curves = run.output().curves();
        writeCsv(curves, workDir.resolveSibling(name + ".csv"));
        double[] time = times(curves);
        double faultTime = 2.5;
        double clearing = faultTime + FAULT_DURATION;
        List<String> others = machines(network).stream().filter(m -> !"G31".equals(m)).toList();
        double[] angle = swing(network, curves, time, List.of("G31"), others, faultTime);
        double[] speed = curves.get("G31_W").toArray();
        double fastest = 0.0;
        double outOfStep = Double.NaN;
        for (int i = 0; i < time.length; i++) {
            if (time[i] >= faultTime && time[i] <= clearing + 0.7) {
                fastest = Math.max(fastest, speed[i]);
            }
            if (time[i] >= faultTime && Double.isNaN(outOfStep) && Math.abs(angle[i]) > 180.0) {
                outOfStep = time[i] - clearing;
            }
        }
        System.out.printf(Locale.ROOT, "%s: G31 up to %.4f pu within 0.7 s of the clearing, out of step %.3f s after it%n", name, fastest, outOfStep);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(fastest).as("G31's speed within 0.7 s of the clearing, pu").isBetween(1.045, 1.065);
        softly.assertThat(outOfStep).as("G31 out of step against the other machines, s after the clearing").isBetween(0.45, 0.75);
        // The grid-following stations, on the AC grid's side of their links: the CR ones, on their own, ride it.
        for (VoltageSourceConverter converter : network.getVoltageSourceConverterStream().filter(c -> c.getId().startsWith("CL")).toList()) {
            String station = converter.getId();
            double[] vac = curves.get(station + "_VAC").toArray();
            double out = Double.NaN;
            for (int i = 0; i < time.length && Double.isNaN(out); i++) {
                out = time[i] > clearing + 0.05 && (vac[i] > 1.3 || vac[i] < 0.3) ? time[i] - clearing : out;
            }
            double after = run.output().mean(station + "_VAC", clearing + 0.05, clearing + 0.15);
            System.out.printf(Locale.ROOT, "%s %s: V_AC %.3f just after the clearing, out of [0.3, 1.3] %.3f s after it%n", name, station, after, out);
            softly.assertThat(after).as("%s's AC voltage just after the clearing, pu", station).isBetween(0.7, 0.9);
            softly.assertThat(out).as("%s's AC voltage out of [0.3, 1.3] pu, s after the clearing", station).isBetween(0.2, 0.75);
        }
        softly.assertAll();
    }

    /**
     * IEEE 39 with its DC grid through a solid pole-to-ground fault at BD16, its hub, from 2.5 s - no DC breaker: every station and
     * the wind park blocked 2 ms on, the stations' AC breakers opening 40 ms on, the whole DC grid lost to New England. The DC side:
     * every station blocked within 5 ms of the fault and passing no power from 0.2 s on, its AC side dead once its breaker is open;
     * the healthy stations' DC voltages gone; CS16's DC current through its reactor up to 12 to 20 pu, then freewheeling through its
     * diodes into the fault, which nothing clears on the DC side, decaying. The AC side: New England short of the DC grid's 400 MW of
     * wind and its stations' transits, its centre of inertia's frequency down to its nadir and settling lower on its governors'
     * droops; New England swinging against G39 to its new angle; every machine settled by the end.
     *
     * <p>Run 2026-11-09: CS16 blocked by its own protection 1.94 ms on, the others on their order, 2.02 ms; CS16's DC current up to
     * 15.9 pu, 9.7 at 2.7 s, 3.2 at the end; the healthy stations' DC voltages down to -0.31 pu, then gone; the centre of inertia's
     * nadir 88 mHz at 4.2 s, its RoCoF over 500 ms -0.080 Hz/s, 68 mHz down at the end; New England swinging 8.0 degrees against G39, settling near -3;
     * every machine within 0.0045 Hz peak to peak over the last second.
     */
    @Test
    void ieee39LosesItsDcGridThroughADcFault() throws IOException {
        String name = "ieee39-dc-dc-fault-bd16-cleared";
        PscadProCases.Case fault = PscadProCases.all().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(fault.network().get());
        Path workDir = Files.createDirectories(Path.of("target", "fault-swing", name));

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, workDir, fault.settings(), fault.events(), PscadProSettings.defaults());

        PscadOutput output = run.output();
        Map<String, DoubleTimeSeries> curves = output.curves();
        writeCsv(curves, workDir.resolveSibling(name + ".csv"));
        double[] time = times(curves);
        double faultTime = 2.5;
        double end = time[time.length - 1];
        SoftAssertions softly = new SoftAssertions();
        // The DC side.
        for (String station : List.of("CS4", "CS16", "CS26", "CSW")) {
            double blocked = (output.firstTimeBelow(station + "_RUN", 0.5, 2.4) - faultTime) * 1.0e3;
            double power = output.peakAbsolute(station + "_P", faultTime + 0.2, end);
            double vacEnd = output.mean(station + "_VAC", end - 0.02, end);
            double vdcEnd = output.mean(station + "_VDC", end - 0.02, end);
            System.out.printf(Locale.ROOT, "%s %s: blocked %.2f ms on, |P| up to %.2e pu from 0.2 s on, V_AC %.4f and V_DC %.5f pu at the end%n", name,
                    station, blocked, power, vacEnd, vdcEnd);
            softly.assertThat(blocked).as("%s blocked, ms after the fault", station).isBetween(0.0, 5.0);
            softly.assertThat(power).as("%s's power from 0.2 s after the fault, pu", station).isLessThan(1.0e-4);
            softly.assertThat(vacEnd).as("%s's AC side at the end, its breaker open, pu", station).isLessThan(0.01);
            if (!"CS16".equals(station)) {
                softly.assertThat(Math.abs(vdcEnd)).as("%s's DC voltage at the end, pu", station).isLessThan(0.01);
            }
        }
        double cs16Peak = output.peakAbsolute("CS16_IDC", faultTime, end);
        double cs16Early = output.mean("CS16_IDC", 2.69, 2.71);
        double cs16End = output.mean("CS16_IDC", end - 0.02, end);
        System.out.printf(Locale.ROOT, "%s CS16: I_DC up to %.2f pu, %.3f at 2.7 s, %.3f at the end%n", name, cs16Peak, cs16Early, cs16End);
        softly.assertThat(cs16Peak).as("CS16's DC current's peak through its reactor, pu").isBetween(12.0, 20.0);
        softly.assertThat(cs16End).as("CS16's DC current freewheeling into the fault at the end, pu").isBetween(1.0, 6.0).isLessThan(cs16Early);
        // The AC side.
        double[] centre = centreOfInertia(network, curves, machines(network));
        double before = 0.0;
        int count = 0;
        for (int i = 0; i < time.length; i++) {
            if (time[i] >= faultTime - 0.1 && time[i] < faultTime) {
                before += centre[i];
                count++;
            }
        }
        before /= count;
        double nadir = 0.0;
        double atFault = Double.NaN;
        double halfSecond = Double.NaN;
        for (int i = 0; i < time.length; i++) {
            double deviation = (centre[i] - before) * FREQUENCY * 1.0e3;
            if (time[i] >= faultTime) {
                nadir = Math.min(nadir, deviation);
                atFault = Double.isNaN(atFault) ? deviation : atFault;
            }
            halfSecond = time[i] >= faultTime + 0.5 && Double.isNaN(halfSecond) ? deviation : halfSecond;
        }
        double rocof = (halfSecond - atFault) / 0.5 / 1.0e3;
        double settled = (output.mean("G39_W", end - 0.5, end) - before) * FREQUENCY * 1.0e3;
        double[] swing = swing(network, curves, time, machines(network).stream().filter(m -> !"G39".equals(m)).toList(), List.of("G39"), faultTime);
        double largest = Arrays.stream(swing).map(Math::abs).max().orElseThrow();
        System.out.printf(Locale.ROOT, "%s: nadir %.1f mHz, RoCoF %.4f Hz/s, %.1f mHz at the end; New England %.2f degrees against G39%n", name, nadir,
                rocof, settled, largest);
        softly.assertThat(nadir).as("the centre of inertia's nadir, mHz").isBetween(-96.0, -80.0);
        softly.assertThat(rocof).as("the centre of inertia's RoCoF over 500 ms, Hz/s").isBetween(-0.088, -0.072);
        softly.assertThat(settled).as("the frequency at the end, mHz from before").isBetween(-75.0, -61.0);
        softly.assertThat(largest).as("New England against G39, degrees").isBetween(7.0, 9.0);
        for (String machine : machines(network)) {
            double[] w = curves.get(machine + "_W").toArray();
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (int i = 0; i < time.length; i++) {
                if (time[i] >= end - SETTLED) {
                    low = Math.min(low, w[i]);
                    high = Math.max(high, w[i]);
                }
            }
            softly.assertThat((high - low) * FREQUENCY).as("%s's speed peak to peak over the last second, Hz", machine).isLessThan(0.007);
        }
        softly.assertAll();
    }

    /** Every machine of {@code network}'s. */
    private static List<String> machines(Network network) {
        return network.getGeneratorStream().filter(g -> g.getExtension(GeneratorModel.class) instanceof SubtransientMachineModel)
                .map(Generator::getId).toList();
    }

    /** The angle of {@code area1}'s centre of inertia against {@code area2}'s, degrees, from the fault on. */
    private static double[] swing(Network network, Map<String, DoubleTimeSeries> curves, double[] time, List<String> area1, List<String> area2,
                                  double fault) {
        double[] speed1 = centreOfInertia(network, curves, area1);
        double[] speed2 = centreOfInertia(network, curves, area2);
        double[] angle = new double[time.length];
        for (int i = 1; i < time.length; i++) {
            double slip = 0.5 * (speed1[i] - speed2[i] + speed1[i - 1] - speed2[i - 1]);
            angle[i] = time[i] <= fault ? 0.0 : angle[i - 1] + slip * 360.0 * FREQUENCY * (time[i] - time[i - 1]);
        }
        return angle;
    }

    /** The speed of {@code machines}' centre of inertia, each weighted by its {@code H S}, pu. */
    private static double[] centreOfInertia(Network network, Map<String, DoubleTimeSeries> curves, List<String> machines) {
        double[] speed = null;
        double total = 0.0;
        for (String id : machines) {
            Generator generator = network.getGenerator(id);
            double weight = ((SubtransientMachineModel) generator.getExtension(GeneratorModel.class)).getInertiaConstant() * generator.getRatedS();
            double[] w = curves.get(id + "_W").toArray();
            speed = speed == null ? new double[w.length] : speed;
            for (int i = 0; i < w.length; i++) {
                speed[i] += weight * w[i];
            }
            total += weight;
        }
        for (int i = 0; i < speed.length; i++) {
            speed[i] /= total;
        }
        return speed;
    }

    /** The swing's extremes after the clearing, {time, angle}: the samples, a millisecond apart, highest or lowest within half
     * {@link #HALF_PERIOD_MIN} on either side, each at least {@link #HALF_PERIOD_MIN} after the one before. */
    private static List<double[]> extremes(double[] time, double[] swing, double clearing) {
        List<Integer> kept = new ArrayList<>();
        double next = 0.0;
        for (int i = 0; i < time.length; i++) {
            if (time[i] + 1.0e-9 >= next) {
                kept.add(i);
                next += CSV_STEP;
            }
        }
        int half = (int) Math.round(HALF_PERIOD_MIN / 2.0 / CSV_STEP);
        List<double[]> extremes = new ArrayList<>();
        for (int k = half; k < kept.size() - half; k++) {
            double t = time[kept.get(k)];
            double value = swing[kept.get(k)];
            boolean highest = true;
            boolean lowest = true;
            for (int j = k - half; j <= k + half; j++) {
                highest &= swing[kept.get(j)] <= value;
                lowest &= swing[kept.get(j)] >= value;
            }
            if (t > clearing + 0.05 && (highest || lowest) && (extremes.isEmpty() || t - extremes.get(extremes.size() - 1)[0] > HALF_PERIOD_MIN)) {
                extremes.add(new double[] {t, value});
            }
        }
        return extremes;
    }

    /** The damping ratio from the logarithmic decrement of the peak-to-peak amplitudes a period apart. */
    private static double damping(List<double[]> extremes) {
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i + 3 < extremes.size(); i++) {
            double first = Math.abs(extremes.get(i + 1)[1] - extremes.get(i)[1]);
            double next = Math.abs(extremes.get(i + 3)[1] - extremes.get(i + 2)[1]);
            sum += Math.log(first / next);
            count++;
        }
        double decrement = sum / count;
        return decrement / Math.sqrt(4.0 * Math.PI * Math.PI + decrement * decrement);
    }

    private static double[] times(Map<String, DoubleTimeSeries> curves) {
        TimeSeriesIndex index = curves.values().iterator().next().getMetadata().getIndex();
        double[] time = new double[index.getPointCount()];
        for (int i = 0; i < time.length; i++) {
            time[i] = index.getInstantAt(i).getEpochSecond() + index.getInstantAt(i).getNano() * 1.0e-9;
        }
        return time;
    }

    private static void writeCsv(Map<String, DoubleTimeSeries> curves, Path csv) throws IOException {
        List<String> names = List.copyOf(curves.keySet());
        double[][] columns = names.stream().map(n -> curves.get(n).toArray()).toArray(double[][]::new);
        double[] time = times(curves);
        double next = 0.0;
        try (Writer writer = Files.newBufferedWriter(csv)) {
            writer.write("time," + String.join(",", names) + "\n");
            for (int row = 0; row < time.length; row++) {
                if (time[row] + 1.0e-9 < next) {
                    continue;
                }
                next += CSV_STEP;
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.6f", time[row]));
                for (double[] column : columns) {
                    line.append(',').append(String.format(Locale.ROOT, "%.7g", column[row]));
                }
                writer.write(line.append('\n').toString());
            }
        }
    }
}
