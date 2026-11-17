/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.supergrid.simuemt.emtp.EmtpNetlistWriter;
import com.supergrid.simuemt.emtp.EmtpOutput;
import com.supergrid.simuemt.emtp.EmtpRunner;
import com.supergrid.simuemt.emtp.EmtpSettings;
import com.supergrid.simuemt.emtp.EmtpSimulationSettings;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Two Bergeron lines in a row from their steady state in PSCAD Professional ({@link #bench}): upstream's patch took one transmission
 * line only, and reads several since its {@code pscad-bergeron-lines}. PSCAD Professional splits the circuit at each line: its template's
 * snapshot has three subsystems - B2 and its load in 1, B3 and its load in 2, B1 in 3 -, B2's holding an end of each line; the lines'
 * data after subsystem 3, a column per mode, L1_2's then L2_3's. In the first period and in the last, every bus within 1e-6 pu of
 * EMTP's run of the same bench, which starts from its steady state too, and at the same angle from B1 within 1e-3 degree; and the same
 * in both periods within 1e-8 pu and 1e-4 degree. Kept in {@code target/bergeron-lines} and {@code target/emtp/bergeron-lines}: its
 * template's snapshot and Main.dta, and the patched snapshot it restarted from, are what {@code bergeron-lines-professional/} holds
 * ({@link PscadProBergeronLinesSnapshotTest}).
 *
 * <p>Run 2026-11-17, 51 s: B2 1.0023721 pu and -1.87785 degrees, B3 1.0026997 pu and -2.22435 degrees, the same to every digit printed
 * in the first period as in the last; EMTP's the same in its last period, 3e-7 pu under in its first.
 *
 * <p>Tagged {@code pscad} and {@code emtp}: run with {@code mvn test -Ppscad,emtp}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
@Tag("emtp")
class PscadProBergeronLinesTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double SQRT_TWO_THIRDS = Math.sqrt(2.0 / 3.0);
    private static final List<String> BUSES = List.of("B1", "B2", "B3");
    static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(TIME_STEP, 0.4, FREQUENCY, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);

    @Test
    void twoBergeronLinesInARowStartSteadyAcrossThreeSubsystems() throws IOException {
        Path workDir = Path.of("target", "bergeron-lines");
        double runEnd = SETTINGS.stopTimeSeconds();
        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(bench(), workDir, SETTINGS, List.of(), PscadProSettings.defaults());
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(bench(), Path.of("target", "emtp", "bergeron-lines"), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY)), EmtpSettings.defaults()).output();

        try (var snapshots = Files.walk(workDir.resolve("template"))) {
            Path template = snapshots.filter(p -> p.getFileName().toString().equals("two_bus.snp")).findFirst().orElseThrow();
            assertThat(Files.readAllLines(template).stream().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).as("the template's subsystems").hasSize(3);
        }
        double crest = 400.0e3 * SQRT_TWO_THIRDS;
        double[] first = {TIME_STEP, TIME_STEP + 1.0 / FREQUENCY};
        double[] last = {runEnd - 1.0 / FREQUENCY, runEnd};
        SoftAssertions softly = new SoftAssertions();
        for (String bus : BUSES) {
            double[] magnitude = new double[2];
            double[] angle = new double[2];
            int w = 0;
            for (double[] window : new double[][] {first, last}) {
                magnitude[w] = PscadProBeyondFreeTest.magnitude(run, bus, 'A', window) / crest;
                angle[w] = PscadProBeyondFreeTest.angleFrom(run, "B1", bus, window);
                Phasor ours = emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus, 'A'), FREQUENCY, window[0], window[1]);
                Phasor reference = emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, window[0], window[1]);
                double emtpAngle = -Math.toDegrees(Math.IEEEremainder(ours.angleRadians() - reference.angleRadians(), 2 * Math.PI));
                System.out.printf(Locale.ROOT, "%s from %.5f s: PSCAD %.7f pu %.5f degrees, EMTP %.7f pu %.5f degrees%n", bus, window[0], magnitude[w],
                        angle[w], ours.magnitude() / crest, emtpAngle);
                softly.assertThat(magnitude[w] - ours.magnitude() / crest).as("|V(%s)| against EMTP's, pu, from %.5f s", bus, window[0])
                        .isCloseTo(0.0, within(1.0e-6));
                softly.assertThat(Math.IEEEremainder(angle[w] - emtpAngle, 360.0)).as("V(%s)'s angle from V(B1) against EMTP's, degrees, from %.5f s",
                        bus, window[0]).isCloseTo(0.0, within(1.0e-3));
                w++;
            }
            softly.assertThat(magnitude[1] - magnitude[0]).as("|V(%s)|, its last period's against its first's, pu", bus).isCloseTo(0.0, within(1.0e-8));
            softly.assertThat(Math.IEEEremainder(angle[1] - angle[0], 360.0)).as("V(%s)'s angle, its last period's against its first's, degrees", bus)
                    .isCloseTo(0.0, within(1.0e-4));
        }
        softly.assertAll();
    }

    /**
     * Upstream's Bergeron restart bench - an ideal infinite bus on B1, L1_2 a Bergeron line of 320 ohm and 0.33 ms to B2 and its load
     * - and a second line on from B2, L2_3, 280 ohm and 0.21 ms, to B3 and a load of its own, 50 MW and 10 Mvar; the load flow run.
     */
    static Network bench() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
        Substation s3 = network.newSubstation().setId("S3").add();
        VoltageLevel vl3 = s3.newVoltageLevel().setId("VL3").setNominalV(400.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        Bus b3 = vl3.getBusBreakerView().newBus().setId("B3").add();
        network.newLine().setId("L2_3")
                .setVoltageLevel1("VL2").setBus1("B2").setConnectableBus1("B2")
                .setVoltageLevel2(vl3.getId()).setBus2(b3.getId()).setConnectableBus2(b3.getId())
                .setR(2.0).setX(25.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add();
        network.getLine("L2_3").addExtension(BergeronLineModel.class, new BergeronLineModel(280.0, 2.1e-4));
        vl3.newLoad().setId("LOAD3").setBus(b3.getId()).setConnectableBus(b3.getId()).setP0(50.0).setQ0(10.0).add();
        return ExampleNetworks.withLoadFlow(network);
    }
}
