/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.TimeSeriesIndex;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.within;

/**
 * An AC6A's field current limiter in PSCAD Professional against ATP's, Xyce's and Kestrel's, and how the time step moves it: on
 * upstream's cross-backend swing bench, its AC6A's {@code VFELIM} at 11, through a fault on B2's phase A from 1.5 to 1.6 s, run from
 * the steady state at 20, 10 and 5 us, switching on the time step as upstream compares it; every channel around the fault written
 * to {@code target/ac6a-limiter/<step>us.csv}.
 *
 * <p>PSCAD looked 5 and 7 above the others over the fault's first 80 ms: the others' reference values, 23.1 and 45.2, were wrong
 * since first written down - upstream found them 28.2 and 52.6 (its {@code c9eca2ce}), ATP, Xyce and Kestrel within 0.07 of one
 * another. At 20 us, their time step, PSCAD's limiter output {@code VHL} is held to theirs within 0.5, as upstream holds its own
 * run, and its field signal at 1.70 s to Xyce's 10.46 within 0.05. Through the fault {@code VHL}, 92 times {@code VFE} past its
 * threshold, sits on 0 or 75 most of the time, its 40 ms means magnifying the least gap in {@code VFE}'s 100 Hz ripple: a finer time
 * step moves them by up to 1.9, held within 2.
 *
 * <p>Run 2026-11-09: {@code VHL} 28.06, 52.42, 72.38 and 15.54 at 20 us, against the others' 28.25, 52.6, 72.4 and 15.57; 27.66,
 * 51.39, 71.78 and 15.26 at 10 us; 27.45, 50.74, 71.23 and 14.95 at 5 us. {@code VFE} at 1.70 s 10.47, 10.51 and 10.54.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProAc6ALimiterTest {

    private static final double FREQUENCY = 50.0;
    private static final double RUN_END = 2.0;
    /** Each end of the bench's line charged by this, cancelled by a reactor on its bus, as upstream's swing bench. */
    private static final double LINE_END_SUSCEPTANCE = 2.5e-4;
    /** Where the curves are kept: the fault, from a little before to a little after. */
    private static final double CSV_START = 1.45;
    private static final double CSV_END = 1.90;
    /** ATP's, Xyce's and Kestrel's limiter output upstream, 40 ms means (2026-11-09): window start, mean. */
    private static final double[][] OTHERS = {{1.50, 28.25}, {1.54, 52.6}, {1.58, 72.4}, {1.84, 15.57}};
    /** Xyce's field signal at 1.70 s, upstream. */
    private static final double XYCE_FIELD_SIGNAL = 10.46;
    /** The other backends' time step, us: PSCAD held to them as upstream holds it there, a finer step held looser. */
    private static final int THEIR_STEP = 20;

    @ParameterizedTest(name = "{0} us")
    @ValueSource(ints = {20, 10, 5})
    void theLimiterThroughTheFault(int stepMicroseconds) throws IOException {
        Path workDir = Files.createDirectories(Path.of("target", "ac6a-limiter", stepMicroseconds + "us"));
        PscadSimulationSettings settings = new PscadSimulationSettings(stepMicroseconds * 1.0e-6, RUN_END, FREQUENCY, PscadEdition.PROFESSIONAL,
                PscadSwitching.ON_TIME_STEP);

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(swingBench(), workDir, settings, events("fault-b2-a-1.5-1.6"),
                PscadProSettings.defaults());

        PscadOutput output = run.output();
        writeCsv(output.curves(), workDir.resolveSibling(stepMicroseconds + "us.csv"));
        boolean theirs = stepMicroseconds == THEIR_STEP;
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.peakAbsolute("GEN_A6VHL", 0.0, 1.49)).as("the limiter idle before the fault").isZero();
        for (double[] window : OTHERS) {
            double limiter = output.mean("GEN_A6VHL", window[0], window[0] + 0.04);
            System.out.printf(Locale.ROOT, "%d us: VHL %.2f-%.2f s %.3f (others %.2f), VFE %.4f%n", stepMicroseconds, window[0], window[0] + 0.04,
                    limiter, window[1], output.mean("GEN_A6VFE", window[0], window[0] + 0.04));
            softly.assertThat(limiter).as("the limiter from %.2f s against the other backends'", window[0]).isCloseTo(window[1],
                    within(theirs ? 0.5 : 2.0));
        }
        double field = output.mean("GEN_A6VFE", 1.70, 1.72);
        System.out.printf(Locale.ROOT, "%d us: VFE 1.70-1.72 s %.4f (Xyce %.2f)%n", stepMicroseconds, field, XYCE_FIELD_SIGNAL);
        softly.assertThat(field).as("the field signal at 1.70 s against Xyce's").isCloseTo(XYCE_FIELD_SIGNAL, within(theirs ? 0.05 : 0.1));
        softly.assertAll();
    }

    /** Upstream's cross-backend swing bench: the two-bus network, its line's charging cancelled at each end, the machine under the
     * AC6A whose field current limiter acts. */
    private static Network swingBench() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        network.getLine("L1_2").setB1(LINE_END_SUSCEPTANCE).setB2(LINE_END_SUSCEPTANCE);
        for (String bus : List.of("B1", "B2")) {
            network.getBusBreakerView().getBus(bus).getVoltageLevel().newShuntCompensator().setId("REACTOR_" + bus).setBus(bus).setConnectableBus(bus)
                    .setSectionCount(1).newLinearModel().setBPerSection(-LINE_END_SUSCEPTANCE).setGPerSection(0.0).setMaximumSectionCount(1).add().add();
        }
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac6AModelWithFieldCurrentLimit());
        return ExampleNetworks.withLoadFlow(network);
    }

    private static List<EmtEvent> events(String name) {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/" + name + ".xml"));
    }

    /** Every channel from {@link #CSV_START} to {@link #CSV_END}, one row a sample. */
    private static void writeCsv(Map<String, DoubleTimeSeries> curves, Path csv) throws IOException {
        List<String> names = List.copyOf(curves.keySet());
        double[][] columns = names.stream().map(name -> curves.get(name).toArray()).toArray(double[][]::new);
        TimeSeriesIndex index = curves.get(names.get(0)).getMetadata().getIndex();
        try (Writer writer = Files.newBufferedWriter(csv)) {
            writer.write("time," + String.join(",", names) + "\n");
            for (int row = 0; row < index.getPointCount(); row++) {
                double time = index.getInstantAt(row).getEpochSecond() + index.getInstantAt(row).getNano() * 1.0e-9;
                if (time < CSV_START || time > CSV_END) {
                    continue;
                }
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.7f", time));
                for (double[] column : columns) {
                    line.append(',').append(String.format(Locale.ROOT, "%.7g", column[row]));
                }
                writer.write(line.append('\n').toString());
            }
        }
    }
}
