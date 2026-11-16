/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.simuemt.extensions.circuit.CoupledLineParameters;
import com.powsybl.simuemt.extensions.circuit.LineEndShunt;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lumped lines with zero-sequence data ({@code LineFortescue}) in EMTP: a coupled R-L, its shunt {@link LineEndShunt}'s, a conductor
 * the line is not running on dead-ended. On upstream's coupled-line benches - an ideal source, the line, a shunt of known admittance,
 * no machine -, against the closed form every backend is held to: the nodal solve {@code (1 + Z (Yd + Y2)) V2 = (1 + Z Yd) V1} on the
 * connected phases, {@code Z} the line's series matrix ({@link CoupledLineParameters}) with an open conductor's row and column struck
 * out, {@code Yd} the 16L/Dt across each phase's self inductance, {@code Y2} the line's shunt at side 2 - an element between a connected
 * phase and an open one to earth - and the bench's own.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpCoupledLineTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double RUN_END = 0.4;

    /** The coupled line as one {@code _PI} group of six pins, its self and mutual R and L; an open conductor's ends nodes of its own. */
    @ParameterizedTest(name = "phase a open {0}")
    @CsvSource({"false", "true"})
    void writesACoupledLine(boolean openPhaseA, @TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.createCoupledLineBench(openPhaseA);
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).contains(openPhaseA ? "_PI;L1_2;6;6;L1_2_OKa,B1b,B1c,L1_2_OMa,B2b,B2c," : "_PI;L1_2;6;6;B1a,B1b,B1c,B2a,B2b,B2c,");
        CoupledLineParameters coupled = CoupledLineParameters.of(network.getLine("L1_2"));
        int at = lines.indexOf("-3,1,1,1,1,1,1,2,");
        assertThat(lines.get(at + 1)).isEqualTo(EmtpNetlistWriter.number(coupled.rSelf()) + " " + EmtpNetlistWriter.number(coupled.rMutual()) + " "
                + EmtpNetlistWriter.number(coupled.rMutual()));
    }

    /**
     * Each bench, with and without its line shunt and zero-sequence shunt data, every conductor connected or phase a's open, run 0.4 s:
     * B2's connected phases on the closed form within 1e-4 of B1's, over the last two periods - the open conductor's own bus dead.
     */
    @ParameterizedTest(name = "phase a open {0}, line shunt {1}")
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    @Tag("emtp")
    void aCoupledLineRunsOnItsClosedForm(boolean openPhaseA, boolean lineShunt) {
        Network network = ExampleNetworks.withLoadFlow(lineShunt ? ExampleNetworks.createCoupledLineShuntBench(openPhaseA, true)
                : ExampleNetworks.createCoupledLineBench(openPhaseA));
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "coupled-" + openPhaseA + "-" + lineShunt),
                new EmtpSimulationSettings(TIME_STEP, RUN_END, FREQUENCY)), EmtpSettings.defaults()).output();
        double[][] v1 = new double[3][];
        double[][] v2 = new double[3][];
        for (int k = 0; k < 3; k++) {
            v1[k] = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", "ABC".charAt(k)), FREQUENCY, RUN_END - 0.04, RUN_END));
            v2[k] = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", "ABC".charAt(k)), FREQUENCY, RUN_END - 0.04, RUN_END));
        }
        boolean[] open = {openPhaseA, false, false};
        double[][] predicted = closedForm(network.getLine("L1_2"), v1, open);
        double scale = Math.hypot(v1[1][0], v1[1][1]);
        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < 3; k++) {
            if (open[k]) {
                System.out.printf(Locale.ROOT, "B2%c, the open conductor's: %.2e of B1's%n", "ABC".charAt(k), Math.hypot(v2[k][0], v2[k][1]) / scale);
                softly.assertThat(Math.hypot(v2[k][0], v2[k][1]) / scale).as("B2%c dead", "ABC".charAt(k)).isLessThan(1.0e-4);
                continue;
            }
            double gap = Math.hypot(v2[k][0] - predicted[k][0], v2[k][1] - predicted[k][1]) / scale;
            System.out.printf(Locale.ROOT, "B2%c: %.2e off the closed form%n", "ABC".charAt(k), gap);
            softly.assertThat(gap).as("B2%c against the closed form", "ABC".charAt(k)).isLessThan(1.0e-4);
        }
        softly.assertAll();
    }

    /** The bench's far-end voltages on its connected phases from its source's {@code v1}; {@code null} on an open conductor. */
    private static double[][] closedForm(Line line, double[][] v1, boolean[] open) {
        CoupledLineParameters coupled = CoupledLineParameters.of(line);
        LineEndShunt shunt = LineEndShunt.of(line, TwoSides.TWO);
        double[] self = {coupled.rSelf(), coupled.xSelf()};
        double[] mutual = {coupled.rMutual(), coupled.xMutual()};
        // Y2: to earth on each phase, the line's and the bench's; between each pair, the line's, an open phase's elements to earth.
        double[][][] y = new double[3][3][];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                y[i][j] = i == j ? new double[] {shunt.groundConductance() + ExampleNetworks.COUPLED_LINE_BENCH_G,
                    shunt.groundSusceptance() + ExampleNetworks.COUPLED_LINE_BENCH_B} : new double[] {0.0, 0.0};
            }
        }
        double[] pair = {shunt.phaseToPhaseConductance(), shunt.phaseToPhaseSusceptance()};
        for (int k = 0; k < 3; k++) {
            int other = (k + 1) % 3;
            for (int end : new int[] {k, other}) {
                if (!open[end]) {
                    y[end][end] = add(y[end][end], pair);
                }
            }
            if (!open[k] && !open[other]) {
                y[k][other] = subtract(y[k][other], pair);
                y[other][k] = subtract(y[other][k], pair);
            }
        }
        double yd = 1.0 / NumericalAids.seriesInductanceDampingOhm(coupled.xSelf() / (2 * Math.PI * FREQUENCY), TIME_STEP);
        int[] connected = java.util.stream.IntStream.range(0, 3).filter(k -> !open[k]).toArray();
        int n = connected.length;
        double[][][] a = new double[n][n + 1][];
        for (int i = 0; i < n; i++) {
            double[] rhs = v1[connected[i]];
            for (int j = 0; j < n; j++) {
                double[] zij = i == j ? self : mutual;
                double[] ydz = {yd * zij[0], yd * zij[1]};
                double[] entry = add(i == j ? new double[] {1.0, 0.0} : new double[] {0.0, 0.0}, ydz);
                for (int m = 0; m < n; m++) {
                    entry = add(entry, multiply(i == m ? self : mutual, y[connected[m]][connected[j]]));
                }
                a[i][j] = entry;
                rhs = add(rhs, multiply(ydz, v1[connected[j]]));
            }
            a[i][n] = rhs;
        }
        for (int p = 0; p < n; p++) {
            for (int i = p + 1; i < n; i++) {
                double[] factor = divide(a[i][p], a[p][p]);
                for (int j = p; j <= n; j++) {
                    a[i][j] = subtract(a[i][j], multiply(factor, a[p][j]));
                }
            }
        }
        double[][] v2 = new double[3][];
        for (int i = n - 1; i >= 0; i--) {
            double[] rhs = a[i][n];
            for (int j = i + 1; j < n; j++) {
                rhs = subtract(rhs, multiply(a[i][j], v2[connected[j]]));
            }
            v2[connected[i]] = divide(rhs, a[i][i]);
        }
        return v2;
    }

    /** A phasor as {@code {re, im}}: {@link EmtpOutput}'s angle is a lag, conjugated back. */
    private static double[] complex(Phasor phasor) {
        return new double[] {phasor.magnitude() * Math.cos(phasor.angleRadians()), -phasor.magnitude() * Math.sin(phasor.angleRadians())};
    }

    private static double[] add(double[] x, double[] y) {
        return new double[] {x[0] + y[0], x[1] + y[1]};
    }

    private static double[] subtract(double[] x, double[] y) {
        return new double[] {x[0] - y[0], x[1] - y[1]};
    }

    private static double[] multiply(double[] x, double[] y) {
        return new double[] {x[0] * y[0] - x[1] * y[1], x[0] * y[1] + x[1] * y[0]};
    }

    private static double[] divide(double[] x, double[] y) {
        double d = y[0] * y[0] + y[1] * y[1];
        return new double[] {(x[0] * y[0] + x[1] * y[1]) / d, (x[1] * y[0] - x[0] * y[1]) / d};
    }
}
