/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.arrester.ZnOArrester;
import com.powsybl.simuemt.testresources.ExampleNetworks;
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

/**
 * ZnO surge arresters on the network's lines and transformers in EMTP: per phase an EMTP ZnO column from the equipment's bus to earth.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpZnOArresterTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    /** The clamping arrester on L1_2's B2 end: a column per phase on B2, its reference voltage and its segment, from 0.5 pu. */
    @Test
    void writesAnArresterPerPhase(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithClampingZnOArrester()),
                tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)), StandardCharsets.ISO_8859_1);
        int at = lines.indexOf("_ZnO;L1_2_ZNO2a;1;1;B2a,");
        assertThat(at).isNotNegative();
        assertThat(lines.get(at + 1)).isEqualTo(EmtpNetlistWriter.number(ExampleNetworks.CLAMPING_ARRESTER_REFERENCE_VOLTAGE) + ",,,1e-06,15,5,1,?i,");
        assertThat(lines.get(at + 2)).isEqualTo(EmtpNetlistWriter.number(2500.0) + " " + EmtpNetlistWriter.number(26.0) + " " + EmtpNetlistWriter.number(0.5));
        assertThat(lines).contains("_ZnO;L1_2_ZNO2b;1;1;B2b,", "_ZnO;L1_2_ZNO2c;1;1;B2c,");
    }

    /**
     * Upstream's {@code AtpSolverIntegrationTest#znOArresterClampsTheBusVoltageJustAboveItsReferenceVoltage}: the arrester undersized on
     * purpose - 250 kV against B2's 327 kV crest -, driven into conduction from the steady state on: B2's crest between 0.85 and 1.15 of
     * its reference voltage and under 0.85 of the crest without it (about 327 kV). And on its curve: the crest {@code VREF (i / p)^(1/q)}
     * of the current it then draws, within 1%. Over the run's last period; kept in {@code target/emtp/zno-clamped} and {@code
     * -unprotected}.
     *
     * <p>Run 2026-11-15: B2's crest 324.0 kV unprotected, 228.4 kV clamped - 0.9135 VREF (ATP's 0.95 upstream) -, 237.6 A through the
     * arrester, 228.4 kV on its curve.
     */
    @Test
    @Tag("emtp")
    void anArresterClampsItsBusJustBelowItsReferenceVoltage() {
        double runEnd = 0.3;
        double unprotected = crest(ExampleNetworks.createTwoBusNetworkWithEmtExtensions(), "zno-unprotected", runEnd, null);
        Network clamping = ExampleNetworks.createTwoBusNetworkWithClampingZnOArrester();
        double[] current = new double[1];
        double clamped = crest(clamping, "zno-clamped", runEnd, current);
        double reference = ExampleNetworks.CLAMPING_ARRESTER_REFERENCE_VOLTAGE;
        ZnOArrester arrester = new ZnOArrester(reference, List.of(new com.powsybl.simuemt.extensions.arrester.ZnOSegment(2500.0, 26.0, 0.5)));
        double onCurve = reference * Math.pow(current[0] / 2500.0, 1.0 / 26.0);
        System.out.printf(Locale.ROOT, "B2's crest %.1f kV unprotected, %.1f kV clamped (%.4f VREF); the arrester's crest current %.1f A, %.1f kV on its curve%n",
                unprotected / 1e3, clamped / 1e3, clamped / reference, current[0], onCurve / 1e3);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(clamped / reference).as("B2's crest clamped, of VREF").isBetween(0.85, 1.15);
        softly.assertThat(clamped).as("B2's crest clamped against unprotected, V").isLessThan(0.85 * unprotected);
        softly.assertThat(arrester.current(clamped) / current[0]).as("the arrester's current at B2's crest, on its curve against EMTP's").isBetween(0.77, 1.3);
        softly.assertThat(Math.abs(clamped / onCurve - 1.0)).as("B2's crest against the curve's at EMTP's current").isLessThan(0.01);
        softly.assertAll();
    }

    /** B2's phase-a crest over the run's last period; {@code current}, if given, filled with the arrester's crest current then. */
    private static double crest(Network network, String name, double runEnd, double[] current) {
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(network), Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY)), EmtpSettings.defaults()).output();
        if (current != null) {
            String column = output.columnNames().stream().filter(c -> c.toUpperCase(Locale.ROOT).contains("L1_2_ZNO2A")).findFirst().orElseThrow(
                    () -> new AssertionError("no arrester current among " + output.columnNames()));
            current[0] = output.peakAbsolute(column, runEnd - 1.0 / FREQUENCY, runEnd);
        }
        return output.peakAbsolute(EmtpNetlistWriter.voltageScope("B2", 'A'), runEnd - 1.0 / FREQUENCY, runEnd);
    }
}
