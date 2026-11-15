/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.SwitchOperation;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.supergrid.simuemt.pscadpro.PscadProSettings;
import com.supergrid.simuemt.pscadpro.PscadProSimulation;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The network's switches in EMTP: a closed one joining its buses into one node, an open one nothing; one the run operates ({@link
 * SwitchOperation}) a breaker between its two buses' nodes.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpSwitchTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double WINDOW = 0.02;

    /** A closed switch no event operates: its two buses one node, B2's - no device of it. Operated: its closed resistance and a switch per
     * interval it is closed for, from B2 to B2B; opened then reclosed, a second switch beside the first. */
    @Test
    void writesASwitchAsOneNodeOrAsABreaker(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithRealSwitch());
        List<String> joined = lines(network, tempDir.resolve("joined"), List.of());
        assertThat(joined).contains("_RLC;LOAD_Ra;2;2;B2a,ZLOADa,").noneMatch(l -> l.contains("SW_") || l.contains("B2Ba"));

        List<String> operated = lines(network, tempDir.resolve("operated"), List.of(SwitchOperation.opening("SW", 0.2), SwitchOperation.closing("SW", 0.3)));
        assertThat(operated).contains("_RLC;LOAD_Ra;2;2;B2Ba,ZLOADa,", "_RLC;SW_Ra;2;2;B2a,SW_Sa,", "_Sw0;SW_BRKa;2;2;SW_Sa,B2Ba,", "_Sw0;SW_BRKa2;2;2;SW_Sa,B2Ba,");
        String opening = EmtpNetlistWriter.number(NumericalAids.switchingInstant(0.2, TIME_STEP));
        String reclosing = EmtpNetlistWriter.number(NumericalAids.switchingInstant(0.3, TIME_STEP));
        assertThat(operated).contains("-1," + opening + "," + EmtpNetlistWriter.number(0.0) + ",0,0,,1,.1,.001,5,", reclosing + ",1E15,0,0,0,,1,.1,.001,5,");
    }

    /**
     * The load behind SW cut off: SW opened at 0.2 s, letting go of each phase at its current's zero - B2B dead once the last phase has
     * cleared, within half a period. Kept in {@code
     * target/emtp/switch-opened}.
     *
     * <p>Run 2026-11-15: B2B 0.99212 pu before, B2's load flow; 4e-13 pu once cleared.
     */
    @Test
    @Tag("emtp")
    void anOpenedSwitchCutsOffTheLoadBehindIt() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithRealSwitch());
        double crest = 400.0e3 * Math.sqrt(2.0 / 3.0);
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "switch-opened"), new EmtpSimulationSettings(TIME_STEP, 0.5,
                FREQUENCY), List.of(SwitchOperation.opening("SW", 0.2))), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        double before = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2B", 'A'), FREQUENCY, 0.2 - WINDOW, 0.2).magnitude() / crest;
        double after = output.peakAbsolute(EmtpNetlistWriter.voltageScope("B2B", 'A'), 0.2 + 0.5 / FREQUENCY, 0.5) / crest;
        System.out.printf(Locale.ROOT, "B2B %.5f pu before, %.2e after%n", before, after);
        softly.assertThat(before).as("B2B before, pu: B2's load flow").isCloseTo(network.getBusBreakerView().getBus("B2").getV() / 400.0, within(1.0e-4));
        softly.assertThat(after).as("B2B once cleared, pu").isLessThan(1.0e-3);
        softly.assertAll();
    }

    /**
     * Upstream's dead feeder behind its open switch - L2B_3 and its charging, a constant-impedance load at B3 - energized by SW closing at
     * 0.2 s, whatever the voltage across it: in EMTP as in PSCAD Professional, PSCAD from its steady state ({@code
     * PscadProSimulation#runFromSteadyState}). B3's phase voltages, their crests through the energization's first 50 ms, and their
     * fundamentals over the last period, against PSCAD's. Kept in {@code target/emtp/switch-energizing} and {@code
     * target/pscad/switch-energizing}.
     *
     * <p>Run 2026-11-15: B3 dead before; its crests 1.8916, 1.2282 and 1.2730 pu against PSCAD's 1.8945, 1.2286 and 1.2714; at the end
     * 1.01380 pu in both, the Ferranti rise of the line's charging.
     */
    @Test
    @Tag("emtp")
    @Tag("pscad")
    void aDeadFeederEnergizedRunsInEmtpAsInPscadProfessional() {
        double closing = 0.2;
        double runEnd = 0.5;
        String name = "switch-energizing";
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithDeadFeederBehindOpenSwitch());
        List<EmtEvent> events = List.of(SwitchOperation.closing("SW", closing));
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP, runEnd, FREQUENCY),
                events), EmtpSettings.defaults()).output();
        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, Path.of("target", "pscad", name), new PscadSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.INTERPOLATED), events, PscadProSettings.defaults());
        double crest = 400.0e3 * Math.sqrt(2.0 / 3.0);
        SoftAssertions softly = new SoftAssertions();
        for (char phase : new char[] {'A', 'B', 'C'}) {
            String emtpColumn = EmtpNetlistWriter.voltageScope("B3", phase);
            String pscadColumn = run.project().circuit().voltageChannel("B3", phase);
            double emtpBefore = emtp.peakAbsolute(emtpColumn, 0.1, closing) / crest;
            double emtpPeak = emtp.peakAbsolute(emtpColumn, closing, closing + 0.05) / crest;
            double pscadPeak = run.output().peakAbsolute(pscadColumn, closing, closing + 0.05) / crest;
            double emtpEnd = emtp.fundamentalPhasor(emtpColumn, FREQUENCY, runEnd - WINDOW, runEnd).magnitude() / crest;
            double pscadEnd = run.output().fundamentalPhasor(pscadColumn, FREQUENCY, runEnd - WINDOW, runEnd).magnitude() / crest;
            System.out.printf(Locale.ROOT, "B3%c: %.2e pu before; crest %.4f pu (PSCAD %.4f); at the end %.5f pu (PSCAD %.5f)%n", phase, emtpBefore, emtpPeak, pscadPeak,
                    emtpEnd, pscadEnd);
            softly.assertThat(emtpBefore).as("B3%c before, pu: dead", phase).isLessThan(1.0e-3);
            softly.assertThat(emtpPeak).as("B3%c's crest through the energization, pu: PSCAD's", phase).isCloseTo(pscadPeak, within(1.0e-2));
            softly.assertThat(emtpEnd).as("B3%c at the end, pu: PSCAD's", phase).isCloseTo(pscadEnd, within(1.0e-3));
        }
        softly.assertAll();
    }

    private static List<String> lines(Network network, Path directory, List<? extends EmtEvent> events) throws IOException {
        return Files.readAllLines(EmtpNetlistWriter.write(network, directory, new EmtpSimulationSettings(TIME_STEP, 0.5, FREQUENCY), events), StandardCharsets.ISO_8859_1);
    }
}
