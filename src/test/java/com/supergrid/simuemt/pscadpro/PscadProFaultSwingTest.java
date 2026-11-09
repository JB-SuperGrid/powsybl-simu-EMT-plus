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
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.TimeSeriesIndex;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** One swing, {@code area1}'s centre of inertia against {@code area2}'s, its largest angle in degrees within {@code [min, max]};
     * its frequency and damping ratio, {@code NaN} for a swing too small to read them off. */
    record Swing(String label, List<String> area1, List<String> area2, double min, double max, double frequency, double damping) {
    }

    /**
     * A fault's case: when its fault starts, s; how long it runs, s, {@code NaN} for the case's own; its swings, none in a network
     * without machines; its DC voltages' bounds from the fault on; the largest peak-to-peak speed any machine of the network's is
     * left with over the run's last {@link #SETTLED}, Hz, {@code NaN} not to hold it; and how close every station is back, at the
     * end, to its power before the fault, of its rating, and to its DC voltage, pu.
     */
    record Expected(String name, double fault, double runEnd, List<Swing> swings, double vdcMin, double vdcMax, double settledHz,
                    double recovery) {

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
        return Stream.of(
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
        ).filter(PscadProFaultSwingTest::selected).map(Arguments::of);
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
            double[] swing = swing(network, curves, time, expectedSwing.area1(), expectedSwing.area2(), expected.fault());
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
        if (!Double.isNaN(expected.settledHz())) {
            for (String machine : network.getGeneratorStream().filter(g -> g.getExtension(GeneratorModel.class) instanceof SubtransientMachineModel)
                    .map(Generator::getId).toList()) {
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
            if (converter.getControlMode() == AcDcConverter.ControlMode.V_DC) {
                double lfVdc = initial.dcVoltage() / initial.dcNominalVoltage();
                softly.assertThat(vdcEnd).as("%s's DC voltage at the end against the load flow's, pu", station).isCloseTo(lfVdc,
                        within(expected.recovery()));
            } else {
                double pExpected = (converter.getTerminal1().getP() - DcVoltageDroopLaw.of(converter, initial)
                        .map(law -> law.powerReduction(vdcEnd) * initial.baseApparentPower()).orElse(0.0)) / rating;
                System.out.printf(Locale.ROOT, "%s %s: P %.4f expected at the end%n", expected.name(), station, pExpected);
                softly.assertThat(pEnd).as("%s's power at the end against the load flow's along its droop, of its rating", station)
                        .isCloseTo(pExpected, within(expected.recovery()));
            }
        }
        softly.assertAll();
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
