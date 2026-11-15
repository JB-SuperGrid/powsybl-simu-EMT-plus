/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.ModalLineParameters;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.testresources.ExampleNetworks;
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
 * {@link BergeronLineModel} lines in EMTP: its constant-parameter line, {@code _TLM}, in Clarke's modes.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpBergeronLineTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double FAULT = 0.3;
    private static final double CLEARED = 0.6;
    private static final double RUN_END = 0.62;

    /** The modal line: its six ends, its modes - the ground mode's 700 ohm and 0.5 ms first, its rz -, Clarke's matrix; no lumped
     * device of it. */
    @Test
    void writesAModalBergeronLine(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.createModalBergeronLineBench(true);
        Line line = network.getLine("L1_2");
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)),
                StandardCharsets.ISO_8859_1);
        int at = lines.indexOf("_TLM;L1_2;6;6;B1a,B1b,B1c,B2a,B2b,B2c,");
        assertThat(at).isNotNegative();
        assertThat(lines.get(at + 1)).isEqualTo("3,0,0,3,1,1,1,1,");
        assertThat(lines.get(at + 2)).isEqualTo("1 " + EmtpNetlistWriter.number(ModalLineParameters.of(line).groundResistance()) + " "
                + EmtpNetlistWriter.number(700.0) + " " + EmtpNetlistWriter.number(5.0e-4));
        assertThat(lines.get(at + 3)).isEqualTo("1 " + EmtpNetlistWriter.number(line.getR()) + " " + EmtpNetlistWriter.number(320.0) + " "
                + EmtpNetlistWriter.number(3.3e-4));
        assertThat(lines.subList(at + 8, at + 11)).containsExactly("0", "0", "0");
        assertThat(lines).noneMatch(l -> l.startsWith("_RLC;L1_2_"));
    }

    /**
     * Upstream's {@code AtpSolverIntegrationTest#aDistributedLineGroundModeRaisesTheHealthyPhaseAsItsOwnTwoPortPredicts}: the line alone
     * from an ideal source, open at B2, a bolted fault on B2's phase a from 0.3 to 0.6 s - the healthy phase b moving by {@code k} times
     * phase a's pre-fault voltage, {@code k = (w - u) / (2u + w)} of each mode's two-port; nothing for three independent phases ({@code
     * w = u}). Held, as ATP is, within 1e-3 of phase b's pre-fault voltage, read 80 to 20 ms before the fault clears. Kept in {@code
     * target/emtp/bergeron-coupled} and {@code -decoupled}.
     *
     * <p>Run 2026-11-15: B2 1.00556 pu before, the open end's Ferranti rise; with a ground mode phase b 1.2781 of its pre-fault (ATP
     * 1.2783), off the two-port by 3.5e-4; without, 0.9998, off by 3.3e-4 - the travelling wave the fault launches, the residual ATP's too.
     */
    @Test
    @Tag("emtp")
    void aDistributedLinesGroundModeRaisesTheHealthyPhaseAsItsTwoPortPredicts() {
        for (boolean groundMode : new boolean[] {false, true}) {
            Network network = ExampleNetworks.createModalBergeronLineBench(groundMode);
            EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", groundMode ? "bergeron-coupled" : "bergeron-decoupled"),
                    new EmtpSimulationSettings(TIME_STEP, RUN_END, FREQUENCY), List.of(new BusFault("FLT", "B2", 'A', FAULT, CLEARED))), EmtpSettings.defaults()).output();
            double[] aBefore = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'A'), FREQUENCY, FAULT - 0.06, FAULT - TIME_STEP));
            double[] bBefore = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'B'), FREQUENCY, FAULT - 0.06, FAULT - TIME_STEP));
            double[] bDuring = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'B'), FREQUENCY, CLEARED - 0.08, CLEARED - 0.02));
            double[] k = groundModeFaultFactor(network.getLine("L1_2"));
            double[] expected = {bBefore[0] - (k[0] * aBefore[0] - k[1] * aBefore[1]), bBefore[1] - (k[0] * aBefore[1] + k[1] * aBefore[0])};
            double preFault = Math.hypot(bBefore[0], bBefore[1]);
            double gap = Math.hypot(bDuring[0] - expected[0], bDuring[1] - expected[1]) / preFault;
            System.out.printf(Locale.ROOT, "%s: B2 %.5f pu before; phase b %.4f of its pre-fault through the fault, off the two-port by %.1e%n",
                    groundMode ? "coupled" : "decoupled", preFault / (400.0e3 * Math.sqrt(2.0 / 3.0)), Math.hypot(bDuring[0], bDuring[1]) / preFault, gap);
            assertThat(gap).as("phase b through the fault, %s a ground mode: off the two-port, of its pre-fault", groundMode ? "with" : "without").isLessThan(1.0e-3);
        }
    }

    /** {@code (w - u)/(2u + w)}, {@code u} and {@code w} the aerial and the ground modes' {@link #modeTransferImpedance}. */
    private static double[] groundModeFaultFactor(Line line) {
        ModalLineParameters modal = ModalLineParameters.of(line);
        BergeronLineModel model = line.getExtension(BergeronLineModel.class);
        double[] u = modal != null ? modeTransferImpedance(modal.aerialResistance(), modal.aerialSurgeImpedance(), modal.aerialTravelTime())
                : modeTransferImpedance(line.getR(), model.getSurgeImpedance(), model.getTravelTime());
        double[] w = modal != null ? modeTransferImpedance(modal.groundResistance(), modal.groundSurgeImpedance(), modal.groundTravelTime()) : u;
        return divide(new double[] {w[0] - u[0], w[1] - u[1]}, new double[] {2 * u[0] + w[0], 2 * u[1] + w[1]});
    }

    /** {@code B/A} of one mode's two-port at 50 Hz: {@code R/2}, a lossless line of {@code Zc} and {@code tau}, {@code R/2}. */
    private static double[] modeTransferImpedance(double resistanceOhm, double surgeImpedanceOhm, double travelTimeSeconds) {
        double theta = 2 * Math.PI * FREQUENCY * travelTimeSeconds;
        double[] half = {resistanceOhm / 2, 0.0};
        double[] cosine = {Math.cos(theta), 0.0};
        double[] a = add(cosine, multiply(half, new double[] {0.0, Math.sin(theta) / surgeImpedanceOhm}));
        double[] b = add(new double[] {0.0, surgeImpedanceOhm * Math.sin(theta)}, multiply(half, cosine));
        return divide(add(multiply(a, half), b), a);
    }

    /** A phasor as {@code {re, im}}: {@link EmtpOutput}'s angle is the load flow's negated (its sine's sign), conjugated back. */
    private static double[] complex(Phasor phasor) {
        return new double[] {phasor.magnitude() * Math.cos(phasor.angleRadians()), -phasor.magnitude() * Math.sin(phasor.angleRadians())};
    }

    private static double[] add(double[] x, double[] y) {
        return new double[] {x[0] + y[0], x[1] + y[1]};
    }

    private static double[] multiply(double[] x, double[] y) {
        return new double[] {x[0] * y[0] - x[1] * y[1], x[0] * y[1] + x[1] * y[0]};
    }

    private static double[] divide(double[] x, double[] y) {
        double d = y[0] * y[0] + y[1] * y[1];
        return new double[] {(x[0] * y[0] + x[1] * y[1]) / d, (x[1] * y[0] - x[0] * y[1]) / d};
    }
}
