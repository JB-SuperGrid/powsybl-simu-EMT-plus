/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.hvdc.AutomaticRestart;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * A converter's protection tripping on its DC voltage band ({@link OvercurrentProtection}'s {@code dcUndervoltage} and {@code
 * dcOvervoltage}, upstream's {@code 46002b2e}): the three cases upstream asks the Professional repository to run - a protected
 * converter alone exceeds PSCAD Free's 15 nodes, so upstream ran none - held to ATP's, as {@code BackendComparisonTest} holds Xyce and
 * Kestrel to it ({@code allThreeBackendsTripAProtectionOnItsDcVoltageBandAlike}, {@code
 * allThreeBackendsTripARigidBipolesOtherPoleOnItsDcUndervoltageAlike}). Run live in PSCAD Professional from rest at 20 us, switching on
 * the time step as ATP does, the events 2 s on from ATP's: the blocking instant within ATP's 150 us (the symmetric monopole) or 50 us
 * (the rigid bipole), and the ends within {@code AtpSolverIntegrationTest}'s bounds.
 *
 * <p>Run 2026-11-16, 110 s the three, on upstream's PSCAD backend blocking a converter on a return not solidly earthed and earthing a
 * capacitor's midpoint: VSC2 blocked at 0.33832 s (ATP 0.33824, 80 us), its DC voltage 0.776 pu at the end; VSC1 at 0.31394 s, ATP's,
 * 1.049 pu; VSC2P at 0.33060 s, ATP's, VSC1P 5.4e-4 pu at the end, the DC voltages 0.766 to 1.098 pu; the stations idle within 2.3e-4.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProDcVoltageBandTest {

    /** PSCAD's events 2 s on from ATP's: from rest, the machines and the controls let go first. */
    private static final double SHIFT = 2.0;
    private static final String MONOPOLE = "vsc-symmetric-monopole-converter-transformers";
    private static final String RIGID = "vsc-bipole-converter-transformers-rigid";

    /**
     * {@code AtpSolverIntegrationTest#aProtectionsDcUndervoltageBlocksTheInverterOnceTheRectifierIsLost}: both stations protected at 2
     * pu and on a band of 0.8 to 1.05 pu, the rectifier VSC1 blocked at 0.3 s: VSC2 blocked on its undervoltage at ATP's 0.33824 s
     * within 150 us; at the end its DC voltage between 0.7 and 0.8 pu (ATP 0.775), both stations idle within 1e-3 pu.
     */
    @Test
    void theInvertersUndervoltageBlocksItOnceTheRectifierIsLost(@TempDir Path tempDir) {
        PscadOutput out = run(monopole(), List.of(new ConverterBlocking("VSC1", SHIFT + 0.3)), 1.0, tempDir);
        double runEnd = SHIFT + 1.0;
        SoftAssertions softly = new SoftAssertions();
        blockedAt(softly, out, "VSC2", 0.33824, 1.5e-4);
        end(softly, out, "VSC2_VDC", runEnd, 0.7, 0.8);
        idle(softly, out, runEnd, "VSC1", "VSC2");
        softly.assertAll();
    }

    /**
     * {@code AtpSolverIntegrationTest#aProtectionsDcOvervoltageBlocksTheRectifierOnceTheInverterIsLost}: the same, the inverter VSC2
     * blocked at 0.3 s: VSC1 blocked on its overvoltage at ATP's 0.31394 s within 150 us; at the end its DC voltage between 1.03 and 1.08
     * pu (ATP 1.048), both stations idle.
     */
    @Test
    void theRectifiersOvervoltageBlocksItOnceTheInverterIsLost(@TempDir Path tempDir) {
        PscadOutput out = run(monopole(), List.of(new ConverterBlocking("VSC2", SHIFT + 0.3)), 1.0, tempDir);
        double runEnd = SHIFT + 1.0;
        SoftAssertions softly = new SoftAssertions();
        blockedAt(softly, out, "VSC1", 0.31394, 1.5e-4);
        end(softly, out, "VSC1_VDC", runEnd, 1.03, 1.08);
        idle(softly, out, runEnd, "VSC1", "VSC2");
        softly.assertAll();
    }

    /**
     * {@code AtpSolverIntegrationTest#aRigidBipoleLosingAPoleTripsTheOtherOnItsDcUndervoltage}: the rigid bipole losing its negative pole
     * - VSC1N and VSC2N blocked at 0.3 s -, VSC2P alone protected, on an undervoltage of 0.8 pu: VSC2P blocked at ATP's 0.3306 s within
     * 50 us; at the end VSC1P's power under 2e-3 pu, every DC voltage between 0.7 and 1.2 pu (ATP 0.77 to 1.10).
     */
    @Test
    void aRigidBipoleLosingAPoleTripsTheOtherOnItsDcUndervoltage(@TempDir Path tempDir) {
        Network network = bench(RIGID);
        network.getVoltageSourceConverter("VSC2P").addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0, null, 0.8, Double.NaN));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        PscadOutput out = run(network, List.of(new ConverterBlocking("VSC1N", SHIFT + 0.3), new ConverterBlocking("VSC2N", SHIFT + 0.3)), 1.5, tempDir);
        double runEnd = SHIFT + 1.5;
        SoftAssertions softly = new SoftAssertions();
        blockedAt(softly, out, "VSC2P", 0.3306, 5.0e-5);
        double p1 = out.mean("VSC1P_P", runEnd - 0.1, runEnd);
        System.out.printf(Locale.ROOT, "VSC1P %.5f pu at the end%n", p1);
        softly.assertThat(Math.abs(p1)).as("VSC1P's power at the end, pu").isLessThan(0.002);
        for (String id : List.of("VSC1P", "VSC1N", "VSC2P", "VSC2N")) {
            end(softly, out, id + "_VDC", runEnd, 0.7, 1.2);
        }
        softly.assertAll();
    }

    /**
     * {@code BackendComparisonTest#allThreeBackendsRestartAStationOnlyWithinItsDcVoltageBandAlike} (upstream's {@code 4730e880}): the
     * same, both stations restarting by themselves ({@code AutomaticRestart(0.5, 0.02, 0.1, 2)}), run 1.5 s: a station restarts only
     * once its DC voltage is back within its band. VSC1 blocked: VSC2 blocked, never restarted, its DC voltage at the end on ATP's 0.768
     * pu within 2e-3. VSC2 blocked: VSC1 blocked, restarted once at ATP's 0.88728 s within 50 ms, never blocked again, its DC voltage at
     * the end on ATP's 1.000 pu within 2e-3. Run 2026-11-16 once upstream's PSCAD backend no longer zeroes a restarting converter's integrals
     * before its release: VSC2 blocked at 0.33832 s, never restarted, 0.7692 pu at the end; VSC1 blocked at 0.31396 s, restarted at
     * 0.91010 s, 1.0000 pu at the end.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"VSC1, VSC2, false, 0.768", "VSC2, VSC1, true, 1.000"})
    void aStationRestartsOnlyWithinItsDcVoltageBand(String lost, String tripped, boolean restarts, double atpEnd, @TempDir Path tempDir) {
        PscadOutput out = run(monopole(new AutomaticRestart(0.5, 0.02, 0.1, 2)), List.of(new ConverterBlocking(lost, SHIFT + 0.3)), 1.5, tempDir);
        double runEnd = SHIFT + 1.5;
        SoftAssertions softly = new SoftAssertions();
        double blocked = out.firstTimeBelow(tripped + "_RUN", 0.5, SHIFT + 0.1) - SHIFT;
        double restarted = out.firstTimeAbove(tripped + "_RUN", 0.5, SHIFT + blocked) - SHIFT;
        System.out.printf(Locale.ROOT, "%s blocked at %.5f s, restarted at %.5f s%n", tripped, blocked, restarted);
        softly.assertThat(blocked).as("%s blocked at, s: on its DC voltage", tripped).isBetween(0.305, 0.345);
        if (restarts) {
            softly.assertThat(restarted).as("%s restarted at, s, against ATP's", tripped).isCloseTo(0.88728, within(0.05));
            softly.assertThat(out.firstTimeBelow(tripped + "_RUN", 0.5, SHIFT + restarted)).as("%s blocked again: never", tripped).isNaN();
        } else {
            softly.assertThat(restarted).as("%s restarted: never, under its band", tripped).isNaN();
        }
        double end = out.mean(tripped + "_VDC", runEnd - 0.2, runEnd);
        System.out.printf(Locale.ROOT, "%s_VDC %.4f at the end (ATP %.3f)%n", tripped, end, atpEnd);
        softly.assertThat(end).as("%s's DC voltage at the end, pu, against ATP's", tripped).isCloseTo(atpEnd, within(0.002));
        softly.assertAll();
    }

    /**
     * Upstream's {@code b0f48a99} asks the Professional repository to run a protected MMC with its return solidly earthed, on a DC
     * voltage band, restarting by itself: {@code vsc-three-terminal-all-droop-mmc}, every station protected at 2 pu, on a band of 0.8 to
     * 1.05 pu, {@code AutomaticRestart(0.5, 0.02, 0.1, 2)}, one station blocked at 0.3 s, run 1.5 s. The inverter VSC2 lost: VSC1 and
     * VSC3 trip on their overvoltage; their DC terminals bleed back through the band - within it from 0.4 to 0.7 s, long enough for a
     * restart on the terminal alone - but their capacitors, behind their diodes, stay at the limit they tripped on: none restarts, the DC
     * voltage some 0.63 pu at the end. No ATP here: EMTP's ({@code EmtpDcVoltageBandTest}) is on PSCAD's within 60 us and 7e-3 pu.
     *
     * <p>Run 2026-11-16, 80 s: VSC1 and VSC3 blocked at 0.31696 and 0.31698 s, 0.6343 pu over the last 0.1 s.
     *
     * <p>Not the rectifier VSC1 lost: there PSCAD's blocked VSC1 draws 4 pu from the DC grid at once, its terminal down to 0.65 pu within
     * 2 ms, its AC power nil - its capacitor behind its diode discharged by something -, and the others trip on their undervoltage;
     * EMTP's holds its charge, the grid dipping 2% under the others' droop. To be investigated in upstream's PSCAD backend.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"VSC2, VSC1, VSC3, true, 0.60, 0.65"})
    void anMmcRestartsOnlyWithItsCapacitorWithinItsBand(String lost, String first, String second, boolean overvoltage, double endMin, double endMax,
                                                        @TempDir Path tempDir) {
        Network network = bench("vsc-three-terminal-all-droop-mmc");
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class,
                new OvercurrentProtection(2.0, 0.0, new AutomaticRestart(0.5, 0.02, 0.1, 2), 0.8, 1.05)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        PscadOutput out = run(network, List.of(new ConverterBlocking(lost, SHIFT + 0.3)), 1.5, tempDir);
        double runEnd = SHIFT + 1.5;
        SoftAssertions softly = new SoftAssertions();
        for (String id : List.of(first, second)) {
            double blocked = out.firstTimeBelow(id + "_RUN", 0.5, SHIFT + 0.1) - SHIFT;
            System.out.printf(Locale.ROOT, "%s blocked at %.5f s%n", id, blocked);
            softly.assertThat(blocked).as("%s blocked at, s: on its band", id).isBetween(0.3, 0.32);
            softly.assertThat(out.firstTimeAbove(id + "_RUN", 0.5, SHIFT + blocked)).as("%s restarted: never", id).isNaN();
            if (overvoltage) {
                double within = out.mean(id + "_VDC", SHIFT + 0.4, SHIFT + 0.7);
                softly.assertThat(within).as("%s's DC terminal from 0.4 to 0.7 s, pu: back within its band", id).isBetween(0.85, 1.0);
            }
            end(softly, out, id + "_VDC", runEnd, endMin, endMax);
        }
        softly.assertAll();
    }

    /** The symmetric monopole, both stations protected at 2 pu and on a band of 0.8 to 1.05 pu. */
    private static Network monopole() {
        return monopole(null);
    }

    /** The same, each station restarting by itself on {@code restart}, if any. */
    private static Network monopole(AutomaticRestart restart) {
        Network network = bench(MONOPOLE);
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class,
                new OvercurrentProtection(2.0, 0.0, restart, 0.8, 1.05)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    private static Network bench(String name) {
        return PscadProCases.bench(name);
    }

    private static PscadOutput run(Network network, List<? extends EmtEvent> events, double atpRunEnd, Path tempDir) {
        PscadSimulationSettings base = PscadProCases.CONVERTERS_ON_TIME_STEP;
        PscadSimulationSettings settings = new PscadSimulationSettings(base.timeStepSeconds(), SHIFT + atpRunEnd, base.nominalFrequencyHz(), base.edition(),
                base.switching());
        return PscadProSimulation.run(ExampleNetworks.withLoadFlow(network), tempDir, settings, events, PscadProSettings.defaults()).output();
    }

    /** {@code id} blocked at ATP's {@code atp}, its running flag's fall 2 s on, within {@code tolerance}. */
    private static void blockedAt(SoftAssertions softly, PscadOutput out, String id, double atp, double tolerance) {
        double blocked = out.firstTimeBelow(id + "_RUN", 0.5, SHIFT + 0.1) - SHIFT;
        System.out.printf(Locale.ROOT, "%s blocked at %.5f s (ATP %.5f, off %.1f us)%n", id, blocked, atp, (blocked - atp) * 1.0e6);
        softly.assertThat(blocked).as("%s blocked at, s, against ATP's", id).isCloseTo(atp, within(tolerance));
    }

    /** {@code column}'s mean over the run's last 0.1 s within {@code [min, max]}. */
    private static void end(SoftAssertions softly, PscadOutput out, String column, double runEnd, double min, double max) {
        double value = out.mean(column, runEnd - 0.1, runEnd);
        System.out.printf(Locale.ROOT, "%s %.4f at the end%n", column, value);
        softly.assertThat(value).as("%s at the end", column).isBetween(min, max);
    }

    /** Each of {@code ids}' power under 1e-3 pu over the run's last 0.1 s. */
    private static void idle(SoftAssertions softly, PscadOutput out, double runEnd, String... ids) {
        for (String id : ids) {
            double p = out.mean(id + "_P", runEnd - 0.1, runEnd);
            System.out.printf(Locale.ROOT, "%s %.5f pu at the end%n", id, p);
            softly.assertThat(Math.abs(p)).as("%s's power at the end, pu: idle", id).isLessThan(1.0e-3);
        }
    }
}
