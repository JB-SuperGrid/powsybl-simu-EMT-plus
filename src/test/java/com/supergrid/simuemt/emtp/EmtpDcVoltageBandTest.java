/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.hvdc.AutomaticRestart;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * A converter's protection on its DC voltage band ({@link OvercurrentProtection}'s {@code dcUndervoltage} and {@code dcOvervoltage}) in
 * EMTP: tripping on its DC voltage, pole less return, read raw, out of the band; its {@link AutomaticRestart} waiting for it back within
 * the band, and a blocked MMC's capacitor within it by {@link AutomaticRestart#CAPACITOR_BAND_MARGIN_PU}. Held to ATP's runs in
 * upstream's {@code AtpSolverIntegrationTest}, which {@code PscadProDcVoltageBandTest} holds PSCAD Professional to, and, on a protected
 * MMC with its return solidly earthed, which upstream runs nowhere, to PSCAD Professional's.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpDcVoltageBandTest {

    private static final double FREQUENCY = 50.0;
    private static final String MONOPOLE = "vsc-symmetric-monopole-converter-transformers";

    /**
     * {@code aProtectionsDcUndervoltageBlocksTheInverterOnceTheRectifierIsLost} and its overvoltage twin: the symmetric monopole, both
     * stations protected at 2 pu on a band of 0.8 to 1.05 pu, one blocked at 0.3 s, run 1 s: the other blocked on its band at ATP's
     * instant within 150 us, its DC voltage at the end within ATP's bounds.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"vsc1, VSC2, 0.33824, 0.7, 0.8", "vsc2, VSC1, 0.31394, 1.03, 1.08"})
    @Tag("emtp")
    void theOtherStationTripsOnItsBand(String lost, String tripped, double atp, double endMin, double endMax) {
        EmtpOutput output = run(protectedBench(MONOPOLE, null), "band-" + lost, "converter-blocking-" + lost + "-0.3", 1.0);
        SoftAssertions softly = new SoftAssertions();
        blockedAt(softly, output, tripped, atp, 1.5e-4);
        end(softly, output, tripped, 1.0, endMin, endMax);
        softly.assertAll();
    }

    /**
     * {@code aRigidBipoleLosingAPoleTripsTheOtherOnItsDcUndervoltage}: the rigid bipole losing its negative pole at 0.3 s, VSC2P alone
     * protected on an undervoltage of 0.8 pu, run 1.5 s: VSC2P blocked at ATP's 0.3306 s within 50 us.
     */
    @Test
    @Tag("emtp")
    void aRigidBipoleLosingAPoleTripsTheOtherOnItsDcUndervoltage() {
        Network network = ExampleNetworks.readVscBench("vsc-bipole-converter-transformers-rigid", EmtExtensionsXmlReader::readNetwork);
        network.getVoltageSourceConverter("VSC2P").addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0, null, 0.8, Double.NaN));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        EmtpOutput output = run(network, "band-rigid", "converter-blocking-vsc1n-vsc2n-0.3", 1.5);
        SoftAssertions softly = new SoftAssertions();
        blockedAt(softly, output, "VSC2P", 0.3306, 5.0e-5);
        softly.assertAll();
    }

    /**
     * {@code allThreeBackendsRestartAStationOnlyWithinItsDcVoltageBandAlike}: the same monopole, both stations restarting by themselves
     * ({@code AutomaticRestart(0.5, 0.02, 0.1, 2)}), run 1.5 s. VSC1 blocked: VSC2 never restarted, held under its band, its DC voltage
     * on ATP's 0.768 pu within 2e-3 at the end. VSC2 blocked: VSC1 restarted once back under 1.05 pu at ATP's 0.88728 s within 50 ms,
     * never blocked again, on ATP's 1.000 pu within 2e-3.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"vsc1, VSC2, false, 0.768", "vsc2, VSC1, true, 1.000"})
    @Tag("emtp")
    void aStationRestartsOnlyWithinItsBand(String lost, String tripped, boolean restarts, double atpEnd) {
        EmtpOutput output = run(protectedBench(MONOPOLE, new AutomaticRestart(0.5, 0.02, 0.1, 2)), "band-restart-" + lost, "converter-blocking-" + lost + "-0.3",
                1.5);
        SoftAssertions softly = new SoftAssertions();
        double blocked = output.firstTimeBelow(run(tripped), 0.5, 0.1);
        double restarted = output.firstTimeAbove(run(tripped), 0.5, blocked);
        System.out.printf(Locale.ROOT, "%s blocked at %.5f s, restarted at %.5f s%n", tripped, blocked, restarted);
        softly.assertThat(blocked).as("%s blocked at, s: on its band", tripped).isBetween(0.305, 0.345);
        if (restarts) {
            softly.assertThat(restarted).as("%s restarted at, s, against ATP's", tripped).isCloseTo(0.88728, within(0.05));
            softly.assertThat(output.firstTimeBelow(run(tripped), 0.5, restarted)).as("%s blocked again: never", tripped).isNaN();
        } else {
            softly.assertThat(restarted).as("%s restarted: never", tripped).isNaN();
        }
        double end = output.mean(vdc(tripped), 1.3, 1.5);
        System.out.printf(Locale.ROOT, "%s DC voltage %.4f at the end (ATP %.3f)%n", tripped, end, atpEnd);
        softly.assertThat(end).as("%s DC voltage at the end, pu, against ATP's", tripped).isCloseTo(atpEnd, within(0.002));
        softly.assertAll();
    }

    /**
     * {@code anMmcsAutomaticRestartWaitsForItsCapacitorWithinItsBandAndItNeverComes}: the symmetric MMC monopole, both stations protected
     * and restarting as above, one blocked at 0.3 s, run 1.5 s: the other blocked on its band at ATP's instant within 1 ms, never
     * restarted - its terminal back within the band, its capacitor not -, its DC terminal bled off to 0.4 to 0.55 pu.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"vsc1, VSC2, 0.31468", "vsc2, VSC1, 0.31412"})
    @Tag("emtp")
    void aSymmetricMmcNeverRestartsWithItsCapacitorOutOfItsBand(String lost, String tripped, double atp) {
        EmtpOutput output = run(protectedBench("vsc-symmetric-monopole-mmc", new AutomaticRestart(0.5, 0.02, 0.1, 2)), "band-mmc-" + lost,
                "converter-blocking-" + lost + "-0.3", 1.5);
        SoftAssertions softly = new SoftAssertions();
        double blocked = blockedAt(softly, output, tripped, atp, 1.0e-3);
        softly.assertThat(output.firstTimeAbove(run(tripped), 0.5, blocked)).as("%s restarted: never", tripped).isNaN();
        end(softly, output, tripped, 1.5, 0.4, 0.55);
        softly.assertAll();
    }

    /**
     * {@code PscadProDcVoltageBandTest#anMmcRestartsOnlyWithItsCapacitorWithinItsBand}: the three-terminal all-droop MMC grid, its returns
     * solidly earthed, every station protected and restarting as above, one blocked at 0.3 s, run 1.5 s - no ATP run of it: PSCAD
     * Professional's. VSC2 lost: VSC1 and VSC3 on their overvoltage at PSCAD's 0.31696 and 0.31698 s, never restarted though their terminals
     * are back within the band from 0.4 to 0.7 s, 0.6343 pu at the end. The instants within 2 ms, the ends within 0.02 pu of PSCAD's.
     *
     * <p>Run 2026-11-16: blocked at 0.31692 s both, 40 and 60 us before PSCAD's, 0.6276 pu at the end. Not the rectifier VSC1 lost: there
     * EMTP's blocked VSC1 holds its capacitor's charge, the grid dipping 2% under the others' droop, nothing tripped, where PSCAD's draws 4
     * pu from the DC grid at once and the others trip on their undervoltage - left out until upstream's PSCAD backend is looked into.
     */
    @ParameterizedTest(name = "{0} blocked")
    @CsvSource({"vsc2, VSC1, 0.31696, VSC3, 0.31698, 0.6343, true"})
    @Tag("emtp")
    void aSolidlyEarthedMmcRestartsOnlyWithItsCapacitorWithinItsBand(String lost, String first, double firstAt, String second, double secondAt, double pscadEnd,
                                                                    boolean backWithin) {
        EmtpOutput output = run(protectedBench("vsc-three-terminal-all-droop-mmc", new AutomaticRestart(0.5, 0.02, 0.1, 2)), "band-mmc3-" + lost,
                "converter-blocking-" + lost + "-0.3", 1.5);
        SoftAssertions softly = new SoftAssertions();
        String[] ids = {first, second};
        double[] instants = {firstAt, secondAt};
        for (int k = 0; k < 2; k++) {
            double blocked = blockedAt(softly, output, ids[k], instants[k], 2.0e-3);
            softly.assertThat(output.firstTimeAbove(run(ids[k]), 0.5, blocked)).as("%s restarted: never", ids[k]).isNaN();
            if (backWithin) {
                softly.assertThat(output.mean(vdc(ids[k]), 0.4, 0.7)).as("%s's DC terminal from 0.4 to 0.7 s, pu: back within its band", ids[k])
                        .isBetween(0.85, 1.0);
            }
            double end = output.mean(vdc(ids[k]), 1.4, 1.5);
            System.out.printf(Locale.ROOT, "%s DC voltage %.4f at the end (PSCAD %.4f)%n", ids[k], end, pscadEnd);
            softly.assertThat(end).as("%s DC voltage at the end, pu, against PSCAD's", ids[k]).isCloseTo(pscadEnd, within(0.02));
        }
        softly.assertAll();
    }

    /** {@code name}, every station protected at 2 pu on a band of 0.8 to 1.05 pu, restarting on {@code restart} if any. */
    private static Network protectedBench(String name, AutomaticRestart restart) {
        Network network = ExampleNetworks.readVscBench(name, EmtExtensionsXmlReader::readNetwork);
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class,
                new OvercurrentProtection(2.0, 0.0, restart, 0.8, 1.05)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    private static EmtpOutput run(Network network, String name, String events, double runEnd) {
        return EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name), new EmtpSimulationSettings(20.0e-6, runEnd, FREQUENCY),
                EmtEventsXml.read(ExampleNetworks.resource("/events/" + events + ".xml"))), EmtpSettings.defaults()).output();
    }

    /** {@code id} blocked at {@code reference} within {@code tolerance}; the instant it was. */
    private static double blockedAt(SoftAssertions softly, EmtpOutput output, String id, double reference, double tolerance) {
        double blocked = output.firstTimeBelow(run(id), 0.5, 0.1);
        System.out.printf(Locale.ROOT, "%s blocked at %.5f s (reference %.5f, off %.1f us)%n", id, blocked, reference, (blocked - reference) * 1.0e6);
        softly.assertThat(blocked).as("%s blocked at, s", id).isCloseTo(reference, within(tolerance));
        return blocked;
    }

    /** {@code id}'s DC voltage over the run's last 0.1 s within {@code [min, max]}. */
    private static void end(SoftAssertions softly, EmtpOutput output, String id, double runEnd, double min, double max) {
        double value = output.mean(vdc(id), runEnd - 0.1, runEnd);
        System.out.printf(Locale.ROOT, "%s DC voltage %.4f at the end%n", id, value);
        softly.assertThat(value).as("%s DC voltage at the end, pu", id).isBetween(min, max);
    }

    private static String run(String id) {
        return "control:" + id + "_RUN";
    }

    private static String vdc(String id) {
        return "control:" + id + "_VDC";
    }
}
