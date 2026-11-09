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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The gap upstream documents on an AC6A's field current limiter in PSCAD: on the cross-backend swing bench, its AC6A's
 * {@code VFELIM} at 11, through a fault on B2's phase A from 1.5 to 1.6 s, PSCAD's limiter output {@code VHL} sits 5 and 7 above
 * ATP's, Xyce's and Kestrel's over the fault's first 80 ms, and agrees after. Run here from the steady state at 20, 10 and 5 us,
 * switching on the time step as upstream compares it: whether the gap follows the time step, and every channel around the fault
 * written to {@code target/ac6a-limiter/<step>us.csv}, to lay over the other backends' curves.
 *
 * <p>Run 2026-11-09: at 20 us upstream's run again, {@code VHL} 28.06, 52.42, 72.38 and 15.54, {@code VFE} 10.47 at 1.70 s; at 10 us
 * 27.66, 51.39, 71.78, at 5 us 27.45, 50.74, 71.23 - the time step a quarter moves the gap by 0.6 to 1.7, not its 5 and 7. Through the
 * fault {@code VFE} ripples 3 to 4 peak to peak at 100 Hz, the field current's, and {@code VHL}, its gain 92, sits on 0 or 75 most of
 * the time (1.50 to 1.54 s: on 0 half of it, on 75 a quarter): its 40 ms mean moves some 20 times {@code VFE}'s, not 92 times. The gap
 * is then {@code VFE} some 0.25 to 0.31 higher on average, 2.3 to 2.5%, or its ripple another shape - not the 0.05 to 0.08 the gain
 * alone gives. The third window, {@code VHL} on 75 three quarters of the time, agrees whatever.
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
    /** The other backends' limiter output upstream, 40 ms means: window start, mean. */
    private static final double[][] OTHERS = {{1.50, 23.1}, {1.54, 45.2}, {1.58, 72.1}, {1.84, 15.56}};

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
        for (double[] window : OTHERS) {
            System.out.printf(Locale.ROOT, "%d us: VHL %.2f-%.2f s %.3f (others %.2f), VFE %.4f%n", stepMicroseconds, window[0], window[0] + 0.04,
                    output.mean("GEN_A6VHL", window[0], window[0] + 0.04), window[1], output.mean("GEN_A6VFE", window[0], window[0] + 0.04));
        }
        System.out.printf(Locale.ROOT, "%d us: VFE 1.70-1.72 s %.4f (others 10.4)%n", stepMicroseconds, output.mean("GEN_A6VFE", 1.70, 1.72));
        assertThat(output.peakAbsolute("GEN_A6VHL", 0.0, 1.49)).as("the limiter idle before the fault").isZero();
        assertThat(output.mean("GEN_A6VFE", 1.70, 1.72)).as("the field signal at 1.70 s, the other backends' 10.4").isCloseTo(10.4, within(0.2));
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
