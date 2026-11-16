/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.EmtEvent;
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

    /** The symmetric monopole, both stations protected at 2 pu and on a band of 0.8 to 1.05 pu. */
    private static Network monopole() {
        Network network = bench(MONOPOLE);
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class,
                new OvercurrentProtection(2.0, 0.0, null, 0.8, 1.05)));
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
