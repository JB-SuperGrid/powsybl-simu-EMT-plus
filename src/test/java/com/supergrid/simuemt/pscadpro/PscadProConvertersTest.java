/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.DcNode;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.arrester.ZnOArrester;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.DcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.HybridDcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusEmf;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * The eight converter scenarios of {@link PscadProCases} that upstream could only run in PSCAD Professional - past PSCAD Free's 15
 * nodes or 1024 components - and stores as hand-made runs of 2026-11-07 ({@code PscadFileWriterTest}): run live in PSCAD Professional
 * from rest at 20 us, as upstream's, and held to the checks upstream holds those runs to, themselves ATP's or Kestrel's on the same
 * benches - the numbers copied, the code this project's own. Upstream has tagged those tests {@code professional} and left them out
 * of its builds since its {@code 5e6fb79d}, its repository keeping PSCAD Free's results only: they run here. A WT4B wind turbine blocked; Example 3's grid-forming VSC2 blocked then
 * deblocked; Example 4 on its droops losing VSC1; Example 4 through a solid DC fault fed through its stations' bridges, blocked by order,
 * by their protections, or with MMC stations; cleared selectively by its DC breaker; and an MMC station restarted after it. And four
 * that ATP, Xyce and Kestrel share and no PSCAD had run ({@code BackendComparisonTest}, {@code AtpSolverIntegrationTest}), held to
 * ATP's checks: the solid fault cut off by the stations' AC breakers, two-level and MMC; the MMC grid's cleared by its DC breaker;
 * the meshed MMC grid's faulted line cut off at both ends and reclosed.
 *
 * <p>Run 2026-11-10, on upstream's stored runs of 2026-11-07 to their last digit or nearly: WT1 8e-5 off the load flow before, no power
 * once blocked, its bus 1e-6 off the grid's EMF; VSC2 blocked at 0.99980 and 0.810 pu, -0.147 pu its first 20 ms, its crests 0.431 and
 * 0.911, back 1.6e-3 off; VSC1 blocked 2.249e-4 pu against its bridge's 2.247e-4, VSC2 and VSC3 7.7e-4 and 6.3e-4 off their share;
 * VSC3's {@code Q / V^2} 6.649 to 6.684 against 6.667 blocked by order or protected, 13.08 to 13.26 against 13.33 the MMCs'; the
 * protections blocking VSC3, VSC2 and VSC1 0.85, 4.67 and 9.93 ms after the fault, the MMCs' 0.25, 3.09 and 6.93 ms; DCB's
 * clearing, VSC3 blocked at 2.30084 s, DC2P's crest 554.9 kV, DCL2's end 0.24 kV, VSC1 and VSC2 1.5e-3 and 3.7e-4 off the load flow;
 * the MMC restart, DC3P 0.200 pu then 1.243, VSC3 -0.0023 pu its first 2 ms, its DC current's crest 1.25, the stations within 1.2e-5
 * of before. About 5 minutes the eight.
 *
 * <p>The four of 2026-11-10: no station blocked in the meshed grid, VSC3's DC current's crest 7.39 pu, every power back within 1e-5;
 * the AC breakers' cut, the stations blocked 0.85, 4.67 and 9.93 ms after the fault - the MMCs' 0.25, 3.09 and 6.93 -, nothing drawn
 * once cut off; VSC3's freewheeling decay 0.9192 and the MMC's 5.819 pu decaying to 0.3867 of it, ATP's 0.9193, 5.8125 and 0.387 -
 * since upstream's {@code b35057db}: before it PSCAD's loop held its bridge's two rails' breakers, 0.889, 5.624 and 0.373; the MMC
 * grid's DCB, VSC3 blocked at ATP's 2.30024 s, DC2P's crest 665.7 kV against ATP's 673.4. About 6 minutes the four.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProConvertersTest {

    private static final double TIME_STEP = 20.0e-6;
    private static final double FREQUENCY = 50.0;
    private static final List<String> STATIONS = List.of("VSC1", "VSC2", "VSC3");
    /** The DC faults' instant, on the time step it switches on. */
    private static final double FAULT = NumericalAids.switchingInstant(2.3, TIME_STEP);

    /** WT1 blocked at 2.3 s, run 2.8 s: on the load flow before, no power once blocked, its bus at the grid's EMF 60 ms on and at the end. */
    @Test
    void aBlockedWindTurbineLetsGoOfItsCurrent(@TempDir Path tempDir) {
        Run run = run("wind-turbine-blocked", 2.8, tempDir);
        Generator wind = run.network.getGenerator("WT1");
        Generator grid = run.network.getGenerator("GRID");
        double gridEmf = InfiniteBusEmf.fromLoadFlow(grid, (InfiniteBusGeneratorModel) grid.getExtension(GeneratorModel.class), FREQUENCY, TIME_STEP).magnitudeKv()
                / 225.0;
        PscadOutput out = run.output;
        System.out.printf(Locale.ROOT, "wind-turbine-blocked: P before %.5f (load flow %.5f), U %.5f (%.5f); blocked P %.2e Q %.2e; U %.6f and %.6f (EMF %.6f)%n",
                out.mean("WT1_WP", 2.2, 2.3), -wind.getTerminal().getP() / 100.0, out.mean("WT1_WU", 2.2, 2.3),
                wind.getTerminal().getBusView().getBus().getV() / 33.0, out.mean("WT1_WP", 2.31, 2.8), out.mean("WT1_WQ", 2.31, 2.8),
                out.mean("WT1_WU", 2.36, 2.4), out.mean("WT1_WU", 2.7, 2.8), gridEmf);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(out.mean("WT1_WP", 2.2, 2.3)).as("active power before the blocking").isCloseTo(-wind.getTerminal().getP() / 100.0, within(1.0e-3));
        softly.assertThat(out.mean("WT1_WU", 2.2, 2.3)).as("terminal voltage before the blocking")
                .isCloseTo(wind.getTerminal().getBusView().getBus().getV() / 33.0, within(1.0e-3));
        softly.assertThat(out.mean("WT1_WP", 2.31, 2.8)).as("active power once blocked").isCloseTo(0.0, within(1.0e-6));
        softly.assertThat(out.mean("WT1_WQ", 2.31, 2.8)).as("reactive power once blocked").isCloseTo(0.0, within(1.0e-6));
        softly.assertThat(out.mean("WT1_WU", 2.36, 2.4)).as("terminal voltage 60 ms on, the grid's EMF").isCloseTo(gridEmf, within(1.0e-3));
        softly.assertThat(out.mean("WT1_WU", 2.7, 2.8)).as("terminal voltage at the end, the grid's EMF").isCloseTo(gridEmf, within(1.0e-3));
        softly.assertAll();
    }

    /**
     * Example 3's grid-forming VSC2 blocked at 2.3 s and deblocked at 2.5 s, run 4.5 s: blocked, its speed on its PLL's and its bus
     * clamped by its diodes; restarted in step, its first 20 ms' power the diodes', no reactive inrush, its swing's crest; back on the
     * powers and DC voltage before.
     */
    @Test
    void aDeblockedGridFormingStationRestartsInStep(@TempDir Path tempDir) {
        double runEnd = 4.5;
        PscadOutput out = run("vsc-deblocked", runEnd, tempDir).output;
        System.out.printf(Locale.ROOT, "vsc-deblocked: blocked speed %.5f, VAC %.4f; first 20 ms %.4f; Q crest %.4f, P crest %.4f; VSC2 %.2e, VSC1 %.2e, VDC1 %.2e off%n",
                out.mean("VSC2_VSMW", 2.4, 2.5), out.mean("VSC2_VAC", 2.4, 2.5), out.mean("VSC2_P", 2.5, 2.52), out.peakAbsolute("VSC2_Q", 2.52, runEnd),
                out.peakAbsolute("VSC2_P", 2.52, runEnd), out.mean("VSC2_P", 4.3, runEnd) - out.mean("VSC2_P", 2.2, 2.3),
                out.mean("VSC1_P", 4.3, runEnd) - out.mean("VSC1_P", 2.2, 2.3), out.mean("VSC1_VDC", 4.3, runEnd) - out.mean("VSC1_VDC", 2.2, 2.3));
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(out.mean("VSC2_VSMW", 2.4, 2.5)).as("VSC2's speed blocked: on its PLL's, the bus's").isCloseTo(1.0, within(1.0e-3));
        softly.assertThat(out.mean("VSC2_VAC", 2.4, 2.5)).as("VSC2's AC voltage blocked, pu: its diodes clamping").isBetween(0.78, 0.84);
        softly.assertThat(out.mean("VSC2_P", 2.5, 2.52)).as("VSC2's power over the deblocking's first 20 ms, pu").isBetween(-0.25, 0.0);
        softly.assertThat(out.peakAbsolute("VSC2_Q", 2.52, runEnd)).as("VSC2's reactive power once restarted, pu: no inrush").isLessThan(0.5);
        softly.assertThat(out.peakAbsolute("VSC2_P", 2.52, runEnd)).as("VSC2's power's crest once restarted, pu: its swing's").isBetween(0.85, 0.95);
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.mean(id + "_P", 4.3, runEnd)).as("%s's power at the end, pu: back on before's", id)
                    .isCloseTo(out.mean(id + "_P", 2.2, 2.3), within(3.0e-3));
        }
        softly.assertThat(out.mean("VSC1_VDC", 4.3, runEnd)).as("VSC1's DC voltage at the end, pu").isCloseTo(out.mean("VSC1_VDC", 2.2, 2.3), within(1.0e-3));
        softly.assertAll();
    }

    /**
     * Example 4 all on the DC voltage droop, VSC1 blocked at 2.3 s, run 3.5 s: the stations on the load flow before; VSC1 an idle diode
     * bridge drawing its valves' grading resistors' and snubbers' share; VSC2 and VSC3 at the end where the load flow without VSC1 puts
     * them, the grading resistors' DC load shared along their droops; VSC1's DC voltage left below 0.99.
     */
    @Test
    void theStationsLeftOnTheDroopTakeABlockedStationsShare(@TempDir Path tempDir) {
        double runEnd = 3.5;
        Run run = run("vsc-blocked", runEnd, tempDir);
        PscadOutput out = run.output;
        Network withoutVsc1 = PscadProCases.preprocessed("vsc-three-terminal-all-droop", false, false);
        withoutVsc1.getVoltageSourceConverter("VSC1").getTerminal1().disconnect();
        ExampleNetworks.runLoadFlow(withoutVsc1);
        // The six valves' grading resistors on VSC1's DC side, now a load the droops share: its power, then the DC voltage it takes off.
        double gradingKv = withoutVsc1.getDcNode("DC1P").getV();
        double gradingMw = 3 * gradingKv * gradingKv / (2 * NumericalAids.VALVE_GRADING_OHM);
        double vsc2Slope = droopSlopeMwPerKv(withoutVsc1, "VSC2");
        double vsc3Slope = droopSlopeMwPerKv(withoutVsc1, "VSC3");
        double dropKv = gradingMw / (vsc2Slope + vsc3Slope);

        SoftAssertions softly = new SoftAssertions();
        for (String id : STATIONS) {
            VoltageSourceConverter converter = run.network.getVoltageSourceConverter(id);
            double rating = rating(run.network, id);
            double before = out.mean(id + "_P", 2.2, 2.3);
            softly.assertThat(before).as("%s power before, pu", id).isCloseTo(converter.getTerminal1().getP() / rating, within(0.0015));
            if ("VSC1".equals(id)) {
                double blocked = out.mean("VSC1_P", 2.35, runEnd);
                double bridge = idleBridgePu(converter, out.mean("VSC1_VAC", 2.35, runEnd));
                double vdc = out.mean("VSC1_VDC", runEnd - 0.2, runEnd);
                System.out.printf(Locale.ROOT, "vsc-blocked VSC1: before %.5f (load flow %.5f); blocked %.3e, its bridge %.3e; DC voltage %.4f%n", before,
                        converter.getTerminal1().getP() / rating, blocked, bridge, vdc);
                softly.assertThat(blocked).as("VSC1 power once blocked, pu: its idle bridge's").isCloseTo(bridge, within(1.0e-5));
                softly.assertThat(vdc).as("VSC1 DC voltage at the end, pu: no master brings it back").isLessThan(0.99);
                continue;
            }
            VoltageSourceConverter left = withoutVsc1.getVoltageSourceConverter(id);
            double share = ("VSC2".equals(id) ? vsc2Slope : vsc3Slope) * dropKv;
            double expectedP = (left.getTerminal1().getP() + share) / rating;
            DcNode pole = left.getDcTerminal1().getDcNode();
            double expectedVdc = (pole.getV() + dropKv) / pole.getNominalV();
            double p = out.mean(id + "_P", runEnd - 0.2, runEnd);
            double vdc = out.mean(id + "_VDC", runEnd - 0.2, runEnd);
            System.out.printf(Locale.ROOT, "vsc-blocked %s: before %.5f (load flow %.5f); end %.5f (%.5f), DC %.6f (%.6f)%n", id, before,
                    converter.getTerminal1().getP() / rating, p, expectedP, vdc, expectedVdc);
            softly.assertThat(p).as("%s power at the end, pu", id).isCloseTo(expectedP, within(0.0015));
            softly.assertThat(vdc).as("%s DC voltage at the end, pu", id).isCloseTo(expectedVdc, within(1.0e-4));
        }
        softly.assertAll();
    }

    /**
     * Example 4 all on the droop, each station with a 100 mH DC reactor, through a solid fault on DC3P at 2.3 s, the three blocked
     * 2 ms later, run 3.2 s: the fault fed through their bridges.
     */
    @Test
    void aSolidDcFaultIsFedThroughTheBlockedStationsBridges(@TempDir Path tempDir) {
        double runEnd = 3.2;
        Run run = run("dc-fault-blocked", runEnd, tempDir);
        SoftAssertions softly = new SoftAssertions();
        fedThroughTheBridges(softly, "dc-fault-blocked", run, runEnd, 0.07);
        softly.assertAll();
    }

    /**
     * The same with each station protected against its arms' overcurrent at 2 pu and no order to block: each blocked by its protection
     * within 20 ms - VSC3, on the faulted pole, within 1 ms, VSC2 after it and before VSC1, the farther, a millisecond apart at least -,
     * then the fault fed through their bridges as when blocked by order.
     */
    @Test
    void theStationsProtectionsBlockThemOnASolidDcFault(@TempDir Path tempDir) {
        double runEnd = 3.2;
        Run run = run("dc-fault-protected", runEnd, tempDir);
        SoftAssertions softly = new SoftAssertions();
        for (String id : STATIONS) {
            softly.assertThat(run.output.mean(id + "_RUN", 2.2, 2.299)).as("%s running before the fault", id).isEqualTo(1.0);
        }
        blockedInTurn(softly, "dc-fault-protected", run.output);
        fedThroughTheBridges(softly, "dc-fault-protected", run, runEnd, 0.07);
        softly.assertAll();
    }

    /**
     * Example 4's MMC stations protected at 2 pu through the solid fault on DC3P, no order to block: blocked in turn by their protections
     * as the fault's current reaches their arms, then the fault fed through their bridges, VSC3's AC side a bolted short behind half its
     * arm within ATP's 0.6.
     */
    @Test
    void theMmcsProtectionsBlockThemOnASolidDcFault(@TempDir Path tempDir) {
        double runEnd = 3.2;
        Run run = run("mmc-protected", runEnd, tempDir);
        SoftAssertions softly = new SoftAssertions();
        blockedInTurn(softly, "mmc-protected", run.output);
        fedThroughTheBridges(softly, "mmc-protected", run, runEnd, 0.6);
        softly.assertAll();
    }

    /**
     * Example 4 with its DC breaker DCB through the solid fault on DC3P at 2.3 s, DCB ordered open at 2.302 s and T3's grid end at
     * 2.36 s, run 4.5 s: VSC3 blocked by its protection within a millisecond; DCB's arrester bringing DCL2's current to nothing before
     * VSC1's and VSC2's protections act, the two settling along their droops on the load flow with DCB open and VSC3 out; DC2P under
     * the arrester's reference voltage; DCL2's far end at the fault's potential.
     */
    @Test
    void aDcBreakerClearsADcFaultSelectively(@TempDir Path tempDir) {
        double runEnd = 4.5;
        Run run = run("dc-breaker-selective", runEnd, tempDir);
        PscadOutput out = run.output;
        Network isolated = PscadProCases.bench("vsc-three-terminal-dc-breaker");
        isolated.getDcSwitch("DCB").setOpen(true);
        isolated.getVoltageSourceConverter("VSC3").getTerminal1().disconnect();
        ExampleNetworks.runLoadFlow(isolated);

        SoftAssertions softly = new SoftAssertions();
        double vsc3Blocked = out.firstTimeBelow("VSC3_RUN", 0.5, 2.2);
        softly.assertThat(vsc3Blocked).as("VSC3 blocked at, s: by its protection, within a millisecond of the fault").isBetween(FAULT, FAULT + 1.0e-3);
        for (String id : STATIONS) {
            VoltageSourceConverter converter = run.network.getVoltageSourceConverter(id);
            double rating = rating(run.network, id);
            softly.assertThat(out.mean(id + "_P", 2.2, 2.3)).as("%s power before, pu", id).isCloseTo(converter.getTerminal1().getP() / rating, within(0.0015));
            if ("VSC3".equals(id)) {
                continue;
            }
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.2)).as("%s blocked at, s: never, the breaker faster than its protection", id).isNaN();
            VoltageSourceConverter left = isolated.getVoltageSourceConverter(id);
            DcNode pole = left.getDcTerminal1().getDcNode();
            double p = out.mean(id + "_P", 4.0, runEnd);
            double vdc = out.mean(id + "_VDC", 4.0, runEnd);
            System.out.printf(Locale.ROOT, "dc-breaker-selective %s: end %.5f (load flow %.5f), DC %.6f (%.6f)%n", id, p, left.getTerminal1().getP() / rating, vdc,
                    pole.getV() / pole.getNominalV());
            softly.assertThat(p).as("%s power at the end, pu: along its droop without VSC3", id).isCloseTo(left.getTerminal1().getP() / rating, within(0.003));
            softly.assertThat(vdc).as("%s DC voltage at the end, pu", id).isCloseTo(pole.getV() / pole.getNominalV(), within(2.0e-4));
        }
        ZnOArrester arrester = ((HybridDcBreakerModel) run.network.getDcSwitch("DCB").getExtension(DcBreakerModel.class)).getArrester();
        double crest = out.peakAbsolute("V_DC2P", 2.3, runEnd);
        double farEnd = out.peakAbsolute("V_DC2L", 2.7, runEnd);
        System.out.printf(Locale.ROOT, "dc-breaker-selective: VSC3 blocked %.5f s; DC2P crest %.1f kV (arrester %.1f kV); DC2L from 2.7 s %.2f kV%n", vsc3Blocked,
                crest / 1.0e3, arrester.referenceVoltage() / 1.0e3, farEnd / 1.0e3);
        softly.assertThat(crest).as("DC2P's crest, V: held under the breaker's arrester's").isLessThan(arrester.referenceVoltage());
        softly.assertThat(farEnd).as("DC2L from 2.7 s, V: cut off from DC2P, at the fault's potential").isLessThan(1.0e3);
        softly.assertAll();
    }

    /**
     * Example 4's MMC grid with its DC breaker, stations protected, VSC3 with a 2 pu/s recovery ramp, through the solid fault on DC3P
     * from 2.3 to 2.5 s cleared selectively, DCB reclosed at 2.6 s, T3 at 2.7 s, VSC3 deblocked at 2.8 s, run 4 s: VSC3 blocked by its
     * protection until the deblocking and never again; the dead line recharged; VSC3 restarting from zero; the three back on before.
     */
    @Test
    void anMmcStationRestartsAfterADcFault(@TempDir Path tempDir) {
        double runEnd = 4.0;
        PscadOutput out = run("mmc-restart", runEnd, tempDir).output;
        System.out.printf(Locale.ROOT, "mmc-restart: VSC3 blocked %.5f s; DC3P %.4f pu, crest %.4f; VAC %.4f; P first 2 ms %.4f; IDC crest %.3f; Q %.4f off%n",
                out.firstTimeBelow("VSC3_RUN", 0.5, 2.2), out.mean("V_DC3P", 2.55, 2.6) / 5.0e5, out.peakAbsolute("V_DC3P", 2.6, runEnd) / 5.0e5,
                out.mean("VSC3_VAC", 2.75, 2.8), out.mean("VSC3_P", 2.8, 2.802), out.peakAbsolute("VSC3_IDC", 2.8, runEnd),
                out.mean("VSC3_Q", 3.8, runEnd) - out.mean("VSC3_Q", 2.2, 2.3));
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(out.firstTimeBelow("VSC3_RUN", 0.5, 2.2)).as("VSC3 blocked at, s: by its protection").isBetween(FAULT, FAULT + 1.0e-3);
        softly.assertThat(out.mean("VSC3_RUN", 2.31, 2.799)).as("VSC3's running flag until the deblocking").isCloseTo(0.0, within(1.0e-9));
        softly.assertThat(out.mean("VSC3_RUN", 2.801, runEnd)).as("VSC3's running flag from the deblocking").isCloseTo(1.0, within(1.0e-9));
        softly.assertThat(out.mean("V_DC3P", 2.55, 2.6) / 5.0e5).as("DC3P before the reclosing, pu: VSC3's cut current in its capacitor").isBetween(0.15, 0.3);
        softly.assertThat(out.peakAbsolute("V_DC3P", 2.6, runEnd) / 5.0e5).as("DC3P's crest once reclosed, pu").isBetween(1.1, 1.35);
        softly.assertThat(out.mean("VSC3_VAC", 2.75, 2.8)).as("VSC3's AC voltage before the deblocking, pu: T3 reclosed").isCloseTo(1.0, within(0.01));
        softly.assertThat(out.mean("VSC3_P", 2.8, 2.802)).as("VSC3's power over the deblocking's first 2 ms, pu: from zero").isCloseTo(0.0, within(0.01));
        softly.assertThat(out.peakAbsolute("VSC3_IDC", 2.8, runEnd)).as("VSC3's DC current's crest once deblocked, pu").isBetween(0.8, 1.6);
        for (String id : STATIONS) {
            System.out.printf(Locale.ROOT, "mmc-restart %s: P %.2e, DC %.2e off before's%n", id, out.mean(id + "_P", 3.8, runEnd) - out.mean(id + "_P", 2.2, 2.3),
                    out.mean(id + "_VDC", 3.8, runEnd) - out.mean(id + "_VDC", 2.2, 2.3));
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.801)).as("%s blocked from the deblocking on, s: never", id).isNaN();
            softly.assertThat(out.mean(id + "_P", 3.8, runEnd)).as("%s power at the end, pu: before the fault's", id).isCloseTo(out.mean(id + "_P", 2.2, 2.3), within(0.002));
            softly.assertThat(out.mean(id + "_VDC", 3.8, runEnd)).as("%s DC voltage at the end, pu: before the fault's", id)
                    .isCloseTo(out.mean(id + "_VDC", 2.2, 2.3), within(1.0e-3));
        }
        softly.assertThat(out.mean("VSC3_Q", 3.8, runEnd)).as("VSC3 reactive power at the end, pu: back on before the fault's")
                .isCloseTo(out.mean("VSC3_Q", 2.2, 2.3), within(0.008));
        softly.assertAll();
    }

    /**
     * Example 4 all on the droop, each station with its DC reactor and protected, through the solid fault on DC3P at 2.3 s, the three
     * stations' AC breakers ordered open 60 ms later, run 3.2 s - no PSCAD run upstream; held to ATP's checks ({@code
     * AtpSolverIntegrationTest#theStationsAcBreakersCutTheBridgesOffASolidDcFault}, 2 s earlier) and to what ATP, Xyce and Kestrel share
     * ({@code BackendComparisonTest#allThreeBackendsCutTheStationsBridgesOffASolidDcFault}): every station blocked by its protection before
     * its breaker opens, nothing drawn from the AC side once it has; VSC1's and VSC2's DC currents died out into the fault, their DC
     * voltages at earth; VSC3's freewheeling through its bridge's diodes, 0.6 s on at ATP's 0.9193 of its 2.4-2.6 s mean within the
     * 0.0015 Xyce and Kestrel are held to, its capacitor ringing.
     */
    @Test
    void theStationsAcBreakersCutTheBridgesOffASolidDcFault(@TempDir Path tempDir) {
        double runEnd = 3.2;
        Run run = run("dc-fault-stations-open", runEnd, tempDir);
        PscadOutput out = run.output;
        SoftAssertions softly = new SoftAssertions();
        cutOffTheirAcSides(softly, "dc-fault-stations-open", run, runEnd);
        double early = out.mean("VSC3_IDC", 2.4, 2.6);
        double decay = out.mean("VSC3_IDC", 3.0, runEnd) / early;
        System.out.printf(Locale.ROOT, "dc-fault-stations-open: VSC3 DC current %.3f pu at 2.4-2.6 s, %.4f of it at 3.0-3.2 s (ATP 0.9193); its DC voltage's crest %.3f%n",
                early, decay, out.peakAbsolute("VSC3_VDC", 3.0, runEnd));
        softly.assertThat(early).as("VSC3 DC current at 2.4-2.6 s, pu: its reactor's, freewheeling into the fault").isGreaterThan(15.0);
        softly.assertThat(decay).as("VSC3 DC current at 3.0-3.2 s against 2.4-2.6 s: ATP's").isCloseTo(0.9193, within(0.0015));
        softly.assertThat(out.peakAbsolute("VSC3_VDC", 3.0, runEnd)).as("VSC3 DC voltage at the end, pu: its capacitor ringing").isGreaterThan(0.2);
        softly.assertAll();
    }

    /**
     * The same with MMC stations and no DC reactor added, run 3.2 s - no PSCAD run upstream; held to ATP's checks ({@code
     * AtpSolverIntegrationTest#theStationsAcBreakersLetAnMmcsDcCurrentDieOut}) and to what the three share ({@code
     * BackendComparisonTest#allThreeBackendsLetAnMmcsDcCurrentDieOutOnceItsAcBreakerHasOpened}): VSC3's DC current round its arms' DC
     * equivalent 5.8125 pu at 2.4-2.5 s, ATP's, within the 0.03 Xyce and Kestrel are held to; 0.387 of it the next 0.1 s, ATP's, within
     * 0.01, Xyce's and Kestrel's tolerance; under 0.2% of it at 3.1-3.2 s.
     */
    @Test
    void theStationsAcBreakersLetAnMmcsDcCurrentDieOut(@TempDir Path tempDir) {
        double runEnd = 3.2;
        Run run = run("mmc-stations-open", runEnd, tempDir);
        PscadOutput out = run.output;
        SoftAssertions softly = new SoftAssertions();
        cutOffTheirAcSides(softly, "mmc-stations-open", run, runEnd);
        double current = out.mean("VSC3_IDC", 2.4, 2.5);
        double decay = out.mean("VSC3_IDC", 2.5, 2.6) / current;
        double end = out.mean("VSC3_IDC", 3.1, runEnd) / current;
        System.out.printf(Locale.ROOT, "mmc-stations-open: VSC3 DC current %.4f pu at 2.4-2.5 s (ATP 5.8125), %.4f of it the next 0.1 s (ATP 0.387), %.2e at 3.1-3.2 s%n",
                current, decay, end);
        softly.assertThat(current).as("VSC3 DC current at 2.4-2.5 s, pu: ATP's").isCloseTo(5.8125, within(0.03));
        softly.assertThat(decay).as("VSC3 DC current at 2.5-2.6 s against 2.4-2.5 s: ATP's").isCloseTo(0.387, within(0.01));
        softly.assertThat(end).as("VSC3 DC current at 3.1-3.2 s against 2.4-2.5 s: died out").isLessThan(0.002);
        softly.assertAll();
    }

    /**
     * Example 4's MMC grid with its DC breaker DCB and its 100 mH reactor, stations protected, through the solid fault on DC3P at 2.3 s
     * cleared selectively - DCB at 2.302 s, T3's grid end at 2.36 s -, run 4 s - no PSCAD run upstream; held to ATP's checks ({@code
     * AtpSolverIntegrationTest#anMmcGridsDcBreakerClearsADcFaultSelectively}) and to what the three share ({@code
     * BackendComparisonTest#allThreeBackendsClearAnMmcGridsDcFaultSelectivelyThroughADcBreaker}): VSC3 blocked by its protection at ATP's
     * 0.30024 s on, within the 2e-4 s Xyce and Kestrel are held to; VSC1 and VSC2 never, settled at the end on the load flow with DCB open
     * and VSC3 out; DC2P's crest within the 1.5 kV of ATP's 673.4 kV Xyce and Kestrel are held to. Switched on the time step, as ATP
     * and Kestrel switch ({@code PscadSwitching.ON_TIME_STEP}): run 2026-11-10, 673.58 kV and VSC3 blocked at 2.30026 s, Kestrel's 0.30026
     * s; PSCAD's interpolated switching, which opens DCB inside its step on less current, gave 665.7 kV, 1.1% under.
     */
    @Test
    void anMmcGridsDcBreakerClearsADcFaultSelectively(@TempDir Path tempDir) {
        double runEnd = 4.0;
        Run run = run("mmc-dc-breaker-selective", runEnd, tempDir);
        PscadOutput out = run.output;
        Network isolated = PscadProCases.bench("vsc-three-terminal-dc-breaker-mmc");
        isolated.getDcSwitch("DCB").setOpen(true);
        isolated.getVoltageSourceConverter("VSC3").getTerminal1().disconnect();
        ExampleNetworks.runLoadFlow(isolated);

        SoftAssertions softly = new SoftAssertions();
        double blocked = out.firstTimeBelow("VSC3_RUN", 0.5, 2.2);
        double crest = out.peakAbsolute("V_DC2P", 2.3, runEnd);
        System.out.printf(Locale.ROOT, "mmc-dc-breaker-selective: VSC3 blocked %.6f s (ATP 2.30024); DC2P crest %.1f kV (ATP 673.4)%n", blocked, crest / 1.0e3);
        softly.assertThat(blocked).as("VSC3 blocked at, s: ATP's").isCloseTo(2.30024, within(2.0e-4));
        softly.assertThat(crest / 1.0e3).as("DC2P's crest, kV: ATP's").isCloseTo(673.4, within(1.5));
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.2)).as("%s blocked at, s: never, the breaker faster than its protection", id).isNaN();
            VoltageSourceConverter left = isolated.getVoltageSourceConverter(id);
            DcNode pole = left.getDcTerminal1().getDcNode();
            double p = out.mean(id + "_P", 3.5, runEnd);
            double vdc = out.mean(id + "_VDC", 3.5, runEnd);
            System.out.printf(Locale.ROOT, "mmc-dc-breaker-selective %s: end %.5f (load flow %.5f), DC %.6f (%.6f)%n", id, p, left.getTerminal1().getP() / rating(left),
                    vdc, pole.getV() / pole.getNominalV());
            softly.assertThat(p).as("%s power at the end, pu: along its droop without VSC3", id).isCloseTo(left.getTerminal1().getP() / rating(left), within(0.003));
            softly.assertThat(vdc).as("%s DC voltage at the end, pu", id).isCloseTo(pole.getV() / pole.getNominalV(), within(2.0e-4));
        }
        softly.assertAll();
    }

    /**
     * Example 4 meshed with MMC stations, protected, through a solid fault at DCL2's midpoint from 2.3 to 2.5 s, DCB and DCB3 cutting
     * DCL2 off at both its ends at 2.302 s and reclosing it at 2.6 s, run 4 s - no PSCAD run upstream; held to ATP's checks ({@code
     * AtpSolverIntegrationTest#aMeshedGridsFaultedLineIsCutOffAtBothEndsNoStationBlocked}), which ATP, Xyce and Kestrel share: no station
     * blocked; the DC voltages within 2% of nominal through the isolation; VSC3's DC current's crest between 6.5 and 8.5 pu; VSC3 still
     * exporting through DCL3, a little less along its droop, VSC2 still drawing; every power back on before by 3 s, within 2e-4.
     */
    @Test
    void aMeshedGridsFaultedLineIsCutOffAtBothEnds(@TempDir Path tempDir) {
        double runEnd = 4.0;
        PscadOutput out = run("mmc-meshed-line-cut", runEnd, tempDir).output;
        SoftAssertions softly = new SoftAssertions();
        for (String id : STATIONS) {
            double before = out.mean(id + "_P", 2.2, 2.3);
            double after = out.mean(id + "_P", 3.0, runEnd);
            double vdc = out.mean(id + "_VDC", 2.35, 2.6);
            System.out.printf(Locale.ROOT, "mmc-meshed-line-cut %s: blocked %s; P %.5f before, %.2e off at the end; DC %.4f through the isolation%n", id,
                    out.firstTimeBelow(id + "_RUN", 0.5, 2.2), before, after - before, vdc);
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.2)).as("%s blocked, s: never, the faulted line cut off in time", id).isNaN();
            softly.assertThat(after).as("%s power at the end, pu: before the fault's", id).isCloseTo(before, within(2.0e-4));
            softly.assertThat(vdc).as("%s DC voltage through the isolation, pu", id).isCloseTo(1.0, within(0.02));
        }
        double crest = out.peakAbsolute("VSC3_IDC", 2.3, 2.31);
        System.out.printf(Locale.ROOT, "mmc-meshed-line-cut: VSC3 DC current's crest %.3f pu; P3 %.4f, P2 %.4f through the isolation%n", crest,
                out.mean("VSC3_P", 2.4, 2.5), out.mean("VSC2_P", 2.4, 2.5));
        softly.assertThat(crest).as("VSC3's DC current's crest, pu").isBetween(6.5, 8.5);
        softly.assertThat(out.mean("VSC3_P", 2.4, 2.5)).as("VSC3's power through the isolation, pu").isBetween(0.3, 0.37);
        softly.assertThat(out.mean("VSC2_P", 2.4, 2.5)).as("VSC2's power through the isolation, pu: still drawing").isBetween(-0.8, -0.73);
        softly.assertAll();
    }

    /** The stations on the load flow before; each blocked by its protection before its breaker opens; nothing drawn from the AC side once
     * it has; VSC1's and VSC2's DC currents died out and their DC voltages at earth at the end (ATP's checks). */
    private static void cutOffTheirAcSides(SoftAssertions softly, String name, Run run, double runEnd) {
        PscadOutput out = run.output;
        for (String id : STATIONS) {
            double expected = run.network.getVoltageSourceConverter(id).getTerminal1().getP() / rating(run.network, id);
            double blocked = out.firstTimeBelow(id + "_RUN", 0.5, 2.2);
            double p = out.peakAbsolute(id + "_P", 2.4, runEnd);
            double q = out.peakAbsolute(id + "_Q", 2.4, runEnd);
            System.out.printf(Locale.ROOT, "%s %s: before %.5f (load flow %.5f); blocked %.2f ms after the fault; P %.1e, Q %.1e once cut off; DC current %.1e, voltage %.1e at the end%n",
                    name, id, out.mean(id + "_P", 2.2, 2.3), expected, (blocked - FAULT) * 1.0e3, p, q, out.peakAbsolute(id + "_IDC", runEnd - 0.2, runEnd),
                    out.peakAbsolute(id + "_VDC", runEnd - 0.2, runEnd));
            softly.assertThat(out.mean(id + "_P", 2.2, 2.3)).as("%s power before, pu", id).isCloseTo(expected, within(0.0015));
            softly.assertThat(blocked).as("%s blocked at, s: by its protection, before its breaker opens", id).isBetween(FAULT, FAULT + 0.02);
            softly.assertThat(p).as("%s power once its breaker has opened, pu", id).isLessThan(1.0e-4);
            softly.assertThat(q).as("%s reactive power once its breaker has opened, pu", id).isLessThan(1.0e-4);
        }
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.peakAbsolute(id + "_IDC", runEnd - 0.2, runEnd)).as("%s DC current at the end, pu: died out", id).isLessThan(0.01);
            softly.assertThat(out.peakAbsolute(id + "_VDC", runEnd - 0.2, runEnd)).as("%s DC voltage at the end, pu: at earth", id).isLessThan(0.005);
        }
    }

    /** The stations blocked by their protections after the fault, within 20 ms: VSC3 within 1 ms, VSC2 after it and before VSC1, 1 ms apart at least. */
    private static void blockedInTurn(SoftAssertions softly, String name, PscadOutput out) {
        double[] blocked = STATIONS.stream().mapToDouble(id -> out.firstTimeBelow(id + "_RUN", 0.5, 2.2)).toArray();
        System.out.printf(Locale.ROOT, "%s: blocked after the fault VSC1 %.2f ms, VSC2 %.2f ms, VSC3 %.2f ms%n", name, (blocked[0] - FAULT) * 1.0e3,
                (blocked[1] - FAULT) * 1.0e3, (blocked[2] - FAULT) * 1.0e3);
        for (int i = 0; i < 3; i++) {
            softly.assertThat(blocked[i]).as("%s blocked at, s: by its protection, after the fault", STATIONS.get(i)).isBetween(FAULT, FAULT + 0.02);
        }
        softly.assertThat(blocked[2]).as("VSC3 blocked at, s: within a millisecond of the fault on its pole").isLessThan(FAULT + 1.0e-3);
        softly.assertThat(blocked[1]).as("VSC2 blocked at, s: after VSC3, before VSC1, the farther").isBetween(blocked[2] + 1.0e-3, blocked[0] - 1.0e-3);
    }

    /**
     * The stations on the load flow's powers before the fault, then every 0.2 s VSC3's AC side a bolted short behind its phase reactor -
     * {@code Q / V^2} its {@code 1 / X} within {@code shortCircuitTolerance} -, its DC side at earth; VSC1 and VSC2 rectifying into the
     * fault through the DC lines, VSC2's DC voltage within [0.03, 0.15], VSC1's 0.01 above it, each drawing more than 0.4 pu.
     */
    private static void fedThroughTheBridges(SoftAssertions softly, String name, Run run, double runEnd, double shortCircuitTolerance) {
        PscadOutput out = run.output;
        for (String id : STATIONS) {
            double expected = run.network.getVoltageSourceConverter(id).getTerminal1().getP() / rating(run.network, id);
            softly.assertThat(out.mean(id + "_P", 2.2, 2.3)).as("%s power before, pu", id).isCloseTo(expected, within(0.0015));
        }
        double reactance = ((AveragedConverterModel) run.network.getVoltageSourceConverter("VSC3").getExtension(VoltageSourceConverterModel.class))
                .getPhaseReactorReactance();
        for (double start = 2.4; start < runEnd - 1.0e-9; start += 0.2) {
            double end = start + 0.2;
            double v3 = out.mean("VSC3_VAC", start, end);
            double shortCircuit = out.mean("VSC3_Q", start, end) / (v3 * v3);
            double vdc2 = out.mean("VSC2_VDC", start, end);
            double vdc1 = out.mean("VSC1_VDC", start, end);
            System.out.printf(Locale.ROOT, "%s from %.1f s: VSC3 Q/V^2 %.3f (1/X %.3f), DC %.4f; VSC2 DC %.4f, VSC1 DC %.4f; P %.3f and %.3f%n", name, start,
                    shortCircuit, 1.0 / reactance, out.mean("VSC3_VDC", start, end), vdc2, vdc1, out.mean("VSC1_P", start, end), out.mean("VSC2_P", start, end));
            softly.assertThat(shortCircuit).as("VSC3 Q / V^2 from %.1f s: a bolted short behind its reactor", start).isCloseTo(1.0 / reactance,
                    within(shortCircuitTolerance));
            softly.assertThat(out.mean("VSC3_VDC", start, end)).as("VSC3 DC voltage from %.1f s, pu", start).isCloseTo(0.0, within(0.005));
            softly.assertThat(vdc2).as("VSC2 DC voltage from %.1f s, pu: above the fault's", start).isBetween(0.03, 0.15);
            softly.assertThat(vdc1).as("VSC1 DC voltage from %.1f s, pu: above VSC2's", start).isGreaterThan(vdc2 + 0.01);
            for (String id : List.of("VSC1", "VSC2")) {
                softly.assertThat(out.mean(id + "_P", start, end)).as("%s power from %.1f s, pu: drawn into the fault", id, start).isGreaterThan(0.4);
            }
        }
    }

    /** How much more {@code id} takes from the DC network for each kV its DC voltage drops, MW/kV: its droop's slope at its load flow point. */
    private static double droopSlopeMwPerKv(Network network, String id) {
        VoltageSourceConverter converter = network.getVoltageSourceConverter(id);
        AveragedConverterModel model = (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class);
        AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
        DcVoltageDroopLaw law = DcVoltageDroopLaw.of(converter, initial).orElseThrow();
        double nominalKv = initial.dcNominalVoltage();
        double u = converter.getDcTerminal1().getDcNode().getV() / nominalKv;
        double h = 1.0e-6;
        double perPu = (law.powerReduction(u - h) - law.powerReduction(u + h)) / (2 * h);
        return perPu * model.getRatedApparentPower() / nominalKv;
    }

    /** What a blocked converter's idle bridge draws on its bus at {@code voltagePu}, pu of its rating: on each phase two valves' grading
     * resistors and two RC snubbers. */
    private static double idleBridgePu(VoltageSourceConverter converter, double voltagePu) {
        double phaseVolts = voltagePu * converter.getTerminal1().getVoltageLevel().getNominalV() * 1.0e3 / Math.sqrt(3.0);
        double snubberR = NumericalAids.VALVE_SNUBBER_OHM;
        double snubberX = 1.0 / (2 * Math.PI * FREQUENCY * NumericalAids.VALVE_SNUBBER_F);
        double perPhaseSiemens = 2.0 / NumericalAids.VALVE_GRADING_OHM + 2.0 * snubberR / (snubberR * snubberR + snubberX * snubberX);
        return 3.0 * phaseVolts * phaseVolts * perPhaseSiemens / (rating(converter) * 1.0e6);
    }

    private static double rating(Network network, String id) {
        return rating(network.getVoltageSourceConverter(id));
    }

    private static double rating(VoltageSourceConverter converter) {
        return ((AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class)).getRatedApparentPower();
    }

    private record Run(Network network, PscadOutput output) {
    }

    /** {@link PscadProCases}'s scenario {@code name}, from rest as upstream's runs, to {@code runEnd}. */
    /**
     * As {@link #anMmcStationRestartsAfterADcFault}, and a second solid fault on DC3P from 3.2 to 3.4 s, DCB opened again at 3.202 s - its
     * third operation -, run 3.8 s, as upstream's {@code allThreeBackendsTripADeblockedStationAgainAtASecondFault} holds ATP, Xyce and
     * Kestrel (shifted 2 s): VSC3 running from its deblocking until the second fault, blocked by its protection within 1 ms of it and for
     * good; VSC1 and VSC2 never blocked; DCB open again, DC2P held off the fault.
     */
    @Test
    void aDeblockedStationsProtectionTripsItAgainAtASecondFault(@TempDir Path tempDir) {
        double runEnd = 3.8;
        double refault = NumericalAids.switchingInstant(3.2, 20.0e-6);
        PscadOutput out = run("mmc-restart-refault", runEnd, tempDir).output;
        double tripped = out.firstTimeBelow("VSC3_RUN", 0.5, 2.801);
        System.out.printf(Locale.ROOT, "mmc-restart-refault: VSC3 blocked again %.5f s; DC2P from 3.25 s %.4f pu; DC3P %.4f pu%n", tripped,
                out.mean("V_DC2P", 3.25, 3.4) / 5.0e5, out.mean("V_DC3P", 3.25, 3.4) / 5.0e5);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(out.mean("VSC3_RUN", 2.801, 3.199)).as("VSC3's running flag from the deblocking to the second fault").isCloseTo(1.0, within(1.0e-9));
        softly.assertThat(tripped).as("VSC3 blocked again at, s: by its protection, at the second fault").isBetween(refault, refault + 1.0e-3);
        softly.assertThat(out.mean("VSC3_RUN", 3.21, runEnd)).as("VSC3's running flag from the second fault: blocked for good").isCloseTo(0.0, within(1.0e-9));
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.1)).as("%s blocked, s: never", id).isNaN();
        }
        softly.assertThat(out.mean("V_DC2P", 3.25, 3.4) / 5.0e5).as("DC2P during the second fault, pu: DCB open again, held off it").isGreaterThan(0.8);
        softly.assertThat(out.mean("V_DC3P", 3.25, 3.4) / 5.0e5).as("DC3P during the second fault, pu: faulted").isLessThan(0.05);
        softly.assertAll();
    }

    /**
     * {@link #anMmcStationRestartsAfterADcFault}'s VSC3 faulted three times on DC3P - at 2.3 s for 0.2 s, cleared by DCB, left open, and T3,
     * reclosed at 2.55 s; at 3.2 and 3.9 s for 50 ms -, its protection restarting it with no order to ({@code AutomaticRestart}: above 0.5
     * pu for 20 ms, blocked 0.1 s at least, twice at most), run 4.5 s; held to upstream's {@code
     * AtpSolverIntegrationTest#aStationRestartsItselfTwiceThenStaysBlocked}, shifted 2 s: restarted first on its DC voltage, its diodes
     * recharging its section once T3 is reclosed, 20 ms after it rose past 0.5 pu - ATP at 2.5746 s -, then on its minimum blocked time;
     * locked out after its third trip; its section on its droop while it runs, at 1.028 pu, nothing to exchange.
     */
    @Test
    void anMmcStationRestartsItselfTwiceThenStaysBlocked(@TempDir Path tempDir) {
        double runEnd = 4.5;
        PscadOutput out = run("mmc-restart-itself", runEnd, tempDir).output;
        double[] edges = runningEdges(out, 6);
        double voltageUp = out.firstTimeAbove("VSC3_VDC", 0.5, 2.5);
        System.out.printf(Locale.ROOT, "mmc-restart-itself: VSC3 tripped at %.6f, %.6f, %.6f s, restarted at %.6f, %.6f s; DC voltage up %.6f s;"
                + " DC voltage %.4f / %.4f pu running, %.4f locked out; IDC crest %.3f pu%n", edges[0], edges[2], edges[4], edges[1], edges[3], voltageUp,
                out.mean("VSC3_VDC", 3.0, 3.2), out.mean("VSC3_VDC", 3.7, 3.9), out.mean("VSC3_VDC", 4.0, runEnd), out.peakAbsolute("VSC3_IDC", edges[1], edges[1] + 0.05));
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(edges[0]).as("VSC3's first trip, s: the first fault").isBetween(FAULT, FAULT + 1.0e-3);
        softly.assertThat(voltageUp).as("VSC3's DC voltage above the threshold at, s: T3 reclosed").isBetween(2.55, 2.56);
        softly.assertThat(edges[1] - voltageUp).as("VSC3's first restart after its DC voltage rose, s: the hold time").isCloseTo(0.02, within(2.0e-4));
        softly.assertThat(edges[1]).as("VSC3's first restart, s: ATP's 2.5746").isCloseTo(2.5746, within(1.0e-3));
        softly.assertThat(edges[2]).as("VSC3's second trip, s: the second fault").isBetween(3.2, 3.201);
        softly.assertThat(out.mean("VSC3_VDC", 3.26, 3.3)).as("VSC3's DC voltage before its second restart, pu").isBetween(0.7, 0.8);
        softly.assertThat(edges[3] - edges[2]).as("VSC3's second restart after its trip, s: the minimum blocked time").isCloseTo(0.1, within(2.0e-4));
        softly.assertThat(edges[4]).as("VSC3's third trip, s: the third fault").isBetween(3.9, 3.901);
        softly.assertThat(edges[5]).as("VSC3 restarted after its third trip: never").isNaN();
        softly.assertThat(out.mean("VSC3_VDC", 4.0, runEnd)).as("VSC3's DC voltage blocked for good, pu: its diodes'").isBetween(0.6, 0.8);
        for (double[] running : new double[][] {{3.0, 3.2}, {3.7, 3.9}}) {
            softly.assertThat(out.mean("VSC3_VDC", running[0], running[1])).as("VSC3's DC voltage restarted from %.1f s, pu: its section on its droop", running[0])
                    .isCloseTo(1.028, within(0.005));
            softly.assertThat(out.mean("VSC3_P", running[0], running[1])).as("VSC3's power restarted from %.1f s, pu: nothing to exchange", running[0])
                    .isCloseTo(0.0, within(0.01));
        }
        softly.assertThat(out.peakAbsolute("VSC3_IDC", edges[1], edges[1] + 0.05)).as("VSC3's DC current's crest at its first restart, pu").isBetween(3.0, 4.5);
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.1)).as("%s blocked, s: never, DCB open", id).isNaN();
        }
        softly.assertAll();
    }

    /**
     * The same VSC3 faulted on DC3P at 2.3, 3.5 and 4.7 s for 0.2 s, each fault cleared by DCB 2 ms after it and T3 60 ms after it, both
     * reclosed once it is gone - each breaker operated six times -, its protection restarting it on its DC voltage back from the grid
     * ({@code AutomaticRestart}: above 0.8 pu for 20 ms, blocked 0.1 s at least, twice at most), run 5.5 s; held to upstream's {@code
     * AtpSolverIntegrationTest#aStationRestartsItselfAfterEachReclosingTwiceThenStaysBlocked}, shifted 2 s: tripped at each fault,
     * restarted 20 to 25 ms after DCB's first two reclosings, back on its power before the fault each time; blocked for good after the
     * third, the grid's DC voltage back at its station; the three cycles alike.
     */
    @Test
    void anMmcStationRestartsItselfAfterEachReclosingTwiceThenStaysBlocked(@TempDir Path tempDir) {
        double runEnd = 5.5;
        PscadOutput out = run("mmc-restart-cycled", runEnd, tempDir).output;
        double[] edges = runningEdges(out, 6);
        double before = out.mean("VSC3_P", 2.2, 2.3);
        System.out.printf(Locale.ROOT, "mmc-restart-cycled: VSC3 tripped at %.6f, %.6f, %.6f s, restarted at %.6f, %.6f s; P %.4f before, %.4f and %.4f"
                + " after; DC voltage %.4f at the end%n", edges[0], edges[2], edges[4], edges[1], edges[3], before, out.mean("VSC3_P", 3.2, 3.45),
                out.mean("VSC3_P", 4.4, 4.65), out.mean("VSC3_VDC", 5.1, runEnd));
        SoftAssertions softly = new SoftAssertions();
        double[] faults = {2.3, 3.5, 4.7};
        for (int k = 0; k < 3; k++) {
            double fault = faults[k];
            softly.assertThat(edges[2 * k]).as("VSC3 blocked at, s: the fault at %.1f s", fault).isBetween(fault, fault + 1.0e-3);
            softly.assertThat(out.mean("VSC3_VAC", fault + 0.1, fault + 0.2)).as("VSC3's AC voltage, pu: T3 open after the fault at %.1f s", fault).isLessThan(0.01);
            softly.assertThat(out.mean("VSC3_VAC", fault + 0.26, fault + 0.3)).as("VSC3's AC voltage, pu: T3 reclosed after the fault at %.1f s", fault)
                    .isCloseTo(1.0, within(0.03));
            if (k < 2) {
                softly.assertThat(edges[2 * k + 1] - (fault + 0.3)).as("VSC3 restarted after DCB's reclosing at %.1f s, s: its DC voltage back, held 20 ms",
                        fault + 0.3).isBetween(0.02, 0.025);
                softly.assertThat(out.mean("VSC3_P", fault + 0.9, fault + 1.15)).as("VSC3's power back after the fault at %.1f s, pu", fault)
                        .isCloseTo(before, within(2.0e-3));
            }
        }
        softly.assertThat(edges[5]).as("VSC3 restarted after its third trip: never").isNaN();
        softly.assertThat(out.mean("VSC3_VDC", 5.1, runEnd)).as("VSC3's DC voltage, pu: the grid's, DCB reclosed").isGreaterThan(0.95);
        softly.assertThat(out.mean("VSC3_VDC", 3.6, 3.8)).as("VSC3's DC voltage through the second fault's clearing, pu: as through the first's")
                .isCloseTo(out.mean("VSC3_VDC", 2.4, 2.6), within(1.0e-3));
        softly.assertThat(out.mean("VSC3_P", 4.4, 4.65)).as("VSC3's power after its second restart, pu: as after its first")
                .isCloseTo(out.mean("VSC3_P", 3.2, 3.45), within(1.0e-3));
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(out.firstTimeBelow(id + "_RUN", 0.5, 2.1)).as("%s blocked, s: never", id).isNaN();
        }
        softly.assertAll();
    }

    /** VSC3's running flag's first {@code count} edges from 2.1 s - down, up, down... -, each past the one before by a microsecond, NaN from
     * the first missing on. */
    private static double[] runningEdges(PscadOutput out, int count) {
        double[] edges = new double[count];
        Arrays.fill(edges, Double.NaN);
        double t = 2.1;
        for (int edge = 0; edge < count; edge++) {
            t = edge % 2 == 0 ? out.firstTimeBelow("VSC3_RUN", 0.5, t + 1.0e-6) : out.firstTimeAbove("VSC3_RUN", 0.5, t + 1.0e-6);
            edges[edge] = t;
            if (Double.isNaN(t)) {
                break;
            }
        }
        return edges;
    }

    /**
     * Example 4 master-slave, VSC3's power reference stepped by 0.2 pu at 2.5 s, run 3.5 s - upstream's {@code
     * AtpSolverIntegrationTest#aMasterSlaveStationTakesItsNewSetpointAndTheMasterTheRest}, shifted 2 s: VSC3 at its new setpoint within 60
     * ms, VSC1, the master, delivering the rest, each station and DC voltage moved as the load flow with VSC3's targetP 50 MW more.
     */
    @Test
    void aMasterSlaveStationTakesItsNewSetpointAndTheMasterTheRest(@TempDir Path tempDir) {
        double runEnd = 3.5;
        Run run = run("vsc-master-slave-step", runEnd, tempDir);
        SoftAssertions softly = new SoftAssertions();
        stepMovesEachStationAsTheLoadFlow(softly, run, "vsc-three-terminal", "VSC3", 0.2, 2.5, runEnd, 1.0e-4);
        System.out.printf(Locale.ROOT, "vsc-master-slave-step: VSC3 %.5f pu 60 ms on%n", run.output.mean("VSC3_P", 2.56, 2.58));
        softly.assertThat(run.output.mean("VSC3_P", 2.56, 2.58)).as("VSC3 power 60 ms after the step, pu: there").isCloseTo(0.6, within(0.005));
        softly.assertAll();
    }

    /**
     * Example 4 all on the droop, VSC3's power reference ramped by 0.2 pu over 0.2 s from 2.5 s, run 3.5 s - upstream's {@code
     * AtpSolverIntegrationTest#aRampedPowerStepReachesTheSameEndWithoutRinging}, shifted 2 s: the stepped load flow's end, VSC3's power
     * rising window after window along the ramp, then within 0.005 pu of its end, no ringing.
     */
    @Test
    void aRampedPowerStepReachesTheSameEndWithoutRinging(@TempDir Path tempDir) {
        double runEnd = 3.5;
        Run run = run("vsc-droop-ramp", runEnd, tempDir);
        PscadOutput out = run.output;
        SoftAssertions softly = new SoftAssertions();
        stepMovesEachStationAsTheLoadFlow(softly, run, "vsc-three-terminal-all-droop", "VSC3", 0.2, 2.5, runEnd, 1.0e-3);
        double previous = out.mean("VSC3_P", 2.48, 2.5);
        for (double start = 2.5; start < 2.7 - 1.0e-9; start += 0.02) {
            double mean = out.mean("VSC3_P", start, start + 0.02);
            softly.assertThat(mean).as("VSC3 power from %.2f s, pu: rising along the ramp", start).isGreaterThan(previous);
            previous = mean;
        }
        double end = out.mean("VSC3_P", runEnd - 0.2, runEnd);
        double ringing = 0.0;
        for (double start = 2.7; start < runEnd - 1.0e-9; start += 0.02) {
            ringing = Math.max(ringing, Math.abs(out.mean("VSC3_P", start, start + 0.02) - end));
        }
        System.out.printf(Locale.ROOT, "vsc-droop-ramp: VSC3 %.5f pu at the end, within %.2e of it from 2.7 s%n", end, ringing);
        softly.assertThat(ringing).as("VSC3 power from 2.7 s, pu off its end: no ringing").isLessThan(0.005);
        softly.assertAll();
    }

    /**
     * Example 4's VSC3, its fault ride-through with a 2 pu/s active power recovery ramp, through a solid three-phase fault on B3 from 2.3 to
     * 2.4 s, run 3.5 s, master-slave and all on the droop - upstream's {@code AtpSolverIntegrationTest#aStationsPowerComesBackAlongItsRecoveryRampAfterAnAcFault}
     * and {@code #aDroopStationsPowerComesBackAlongItsRecoveryRampAfterAnAcFault}, shifted 2 s: its power gone through the fault, back
     * along the ramp - 1.8 to 2.1 pu/s from 2.5 to 2.6 s master-slave, 1.2 to 2.1 on the droop, still on its way 0.12 s after the
     * clearing -, on its own before at the end.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vsc-recovery-ramp", "vsc-droop-recovery-ramp"})
    void aStationsPowerComesBackAlongItsRecoveryRampAfterAnAcFault(String name, @TempDir Path tempDir) {
        double runEnd = 3.5;
        boolean droop = name.contains("droop");
        PscadOutput out = run(name, runEnd, tempDir).output;
        double before = out.mean("VSC3_P", 2.2, 2.3);
        double slope = (out.mean("VSC3_P", 2.59, 2.61) - out.mean("VSC3_P", 2.49, 2.51)) / 0.1;
        System.out.printf(Locale.ROOT, "%s: VSC3 %.4f pu before, %.4f through the fault, slope %.3f pu/s, %.4f 0.12 s on, %.4f at the end%n", name, before,
                out.mean("VSC3_P", 2.36, 2.4), slope, out.mean("VSC3_P", 2.52, 2.54), out.mean("VSC3_P", 2.8, runEnd));
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(out.mean("VSC3_P", 2.36, 2.4)).as("VSC3's power through the fault, pu").isCloseTo(0.0, within(0.01));
        softly.assertThat(slope).as("VSC3's power's slope once the voltage is back, pu/s").isBetween(droop ? 1.2 : 1.8, 2.1);
        if (droop) {
            softly.assertThat(out.mean("VSC3_P", 2.52, 2.54)).as("VSC3's power 0.12 s after the clearing, pu: still on its way back").isLessThan(0.33);
        }
        softly.assertThat(out.mean("VSC3_P", 2.8, runEnd)).as("VSC3's power at the end, pu: its own before").isCloseTo(before, within(2.0e-3));
        softly.assertAll();
    }

    /**
     * {@code steppedId}'s power reference stepped by {@code deltaPu}: each station's power and DC voltage at the end, less its offset from the
     * load flow's over the 0.2 s before the step at {@code stepAt}, on the load flow of {@code bench} with its targetP that much more - the V_DC master's power
     * on it outright -, its own within 1.5e-3 pu of it (upstream's {@code AtpSolverIntegrationTest#checkThePowerStepMovesEachStationAsTheLoadFlow}).
     */
    private static void stepMovesEachStationAsTheLoadFlow(SoftAssertions softly, Run run, String bench, String steppedId, double deltaPu, double stepAt,
                                                          double runEnd, double tolerance) {
        Network stepped = PscadProCases.bench(bench);
        VoltageSourceConverter steppedConverter = stepped.getVoltageSourceConverter(steppedId);
        steppedConverter.setTargetP(steppedConverter.getTargetP() + deltaPu * rating(steppedConverter));
        ExampleNetworks.runLoadFlow(stepped);
        PscadOutput out = run.output;
        for (VoltageSourceConverter before : run.network.getVoltageSourceConverters()) {
            String id = before.getId();
            VoltageSourceConverter after = stepped.getVoltageSourceConverter(id);
            double rating = rating(before);
            double offset = out.mean(id + "_P", stepAt - 0.2, stepAt) - before.getTerminal1().getP() / rating;
            double end = out.mean(id + "_P", runEnd - 0.2, runEnd);
            double target = after.getTerminal1().getP() / rating;
            DcNode poleBefore = before.getDcTerminal1().getDcNode();
            DcNode poleAfter = after.getDcTerminal1().getDcNode();
            double dcOffset = out.mean(id + "_VDC", stepAt - 0.2, stepAt) - poleBefore.getV() / poleBefore.getNominalV();
            double dcEnd = out.mean(id + "_VDC", runEnd - 0.2, runEnd) - dcOffset;
            System.out.printf(Locale.ROOT, "%s: %s offset %.2e before, %.5f at the end against the stepped load flow's %.5f; DC %.2e off it%n", bench, id,
                    offset, end, target, dcEnd - poleAfter.getV() / poleAfter.getNominalV());
            softly.assertThat(offset).as("%s power before the step, pu, against the load flow's", id).isCloseTo(0.0, within(0.003));
            if (before.getControlMode() == AcDcConverter.ControlMode.V_DC) {
                softly.assertThat(end).as("%s power at the end, pu: the DC voltage's master, the stepped load flow's", id).isCloseTo(target, within(0.003));
            } else {
                softly.assertThat(end - offset).as("%s power at the end less its offset before, pu: the stepped load flow's", id).isCloseTo(target, within(tolerance));
            }
            if (steppedId.equals(id)) {
                softly.assertThat(end).as("%s power at the end, pu: its new point", id).isCloseTo(target, within(1.5e-3));
            }
            softly.assertThat(dcEnd).as("%s DC voltage at the end less its offset before, pu", id).isCloseTo(poleAfter.getV() / poleAfter.getNominalV(), within(2.0e-4));
        }
    }

    /**
     * Example 3 between infinite buses, its grid-forming VSC2's power reference stepped by 0.1 pu at 5 s, run 6 s - upstream's {@code
     * AtpSolverIntegrationTest#aGridFormingStationsVirtualMachineSwingsToItsNewPowerReference}, shifted 4.5 s, its virtual machine's slow
     * swing from rest settled: swinging to the new reference on its inertia, past it at 5.34 s, never drawing deeper than before; the
     * frequency the infinite buses' at the end; each station and DC voltage moved as the load flow with VSC2's targetP 25 MW more.
     */
    @Test
    void aGridFormingStationsVirtualMachineSwingsToItsNewPowerReference(@TempDir Path tempDir) {
        double runEnd = 6.0;
        Run run = run("vsc-gfm-step", runEnd, tempDir);
        PscadOutput out = run.output;
        SoftAssertions softly = new SoftAssertions();
        stepMovesEachStationAsTheLoadFlow(softly, run, "vsc-monopole-infinite-buses-3", "VSC2", 0.1, 5.0, runEnd, 1.0e-3);
        System.out.printf(Locale.ROOT, "vsc-gfm-step: VSC2 crest %.4f pu drawn, %.4f at 5.34 s, speed %.6f at the end%n", out.peakAbsolute("VSC2_P", 5.0, runEnd),
                out.mean("VSC2_P", 5.32, 5.36), out.mean("VSC2_VSMW", runEnd - 0.1, runEnd));
        softly.assertThat(out.peakAbsolute("VSC2_P", 5.0, runEnd)).as("VSC2 power, pu drawn: no deeper than before").isLessThan(0.801);
        softly.assertThat(out.mean("VSC2_P", 5.32, 5.36)).as("VSC2 power at 5.34 s, pu drawn: past its new reference, on its inertia").isGreaterThan(-0.695);
        softly.assertThat(out.mean("VSC2_VSMW", runEnd - 0.1, runEnd)).as("VSC2's speed at the end: the infinite buses'").isCloseTo(1.0, within(1.0e-4));
        softly.assertAll();
    }

    private static Run run(String name, double runEnd, Path tempDir) {
        PscadProCases.Case scenario = PscadProCases.all().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(scenario.network().get());
        PscadSimulationSettings base = scenario.settings();
        PscadSimulationSettings settings = new PscadSimulationSettings(base.timeStepSeconds(), runEnd, base.nominalFrequencyHz(), base.edition(), base.switching());
        return new Run(network, PscadProSimulation.run(network, tempDir, settings, scenario.events(), PscadProSettings.defaults()).output());
    }
}
