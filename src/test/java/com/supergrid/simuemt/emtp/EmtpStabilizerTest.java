/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * The PSS2A and the discontinuous excitation controls in EMTP ({@link EmtpStabilizers}), held to Xyce's runs upstream's PSCAD tests
 * store ({@code PscadFileWriterTest}), which ATP and Kestrel are held to as well: B1's phase a, the speed and the controls' signals,
 * 20 ms means, at Xyce's instants.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpStabilizerTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final String SPEED = "control:GEN_DW";

    /**
     * {@code PscadFileWriterTest#writeRunsAMachineUnderItsPss2AAsXyceDoes}: the PSS2A on the simplified regulator through a phase-a fault
     * on B2 from 2.5 to 2.6 s, run 4 s: B1 on Xyce's within 0.2% in the fault, 5e-4 just after, 1e-4 from 0.5 s after; the speed within
     * 3e-5; the stabilizer's output within 3% 50 ms after the clearing, 1% from 0.4 s.
     */
    @Test
    @Tag("emtp")
    void aPss2ARunsAsXycesDoes() {
        EmtpOutput output = run(ExampleNetworks.createTwoBusNetworkWithPss2A(), "pss2a", 4.0, "fault-b2-a-2.5-2.6-at-order");
        double[][] xyce = {{2.52, 34.1615, 0.002}, {2.65, 304.8429, 5.0e-4}, {3.0, 330.8625, 1.0e-4}, {3.4, 329.8180, 1.0e-4}, {3.8, 328.2873, 1.0e-4}};
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : xyce) {
            bus(softly, output, window[0], window[1], window[2]);
        }
        check(softly, output, SPEED, 2.65, 0.004476, 3.0e-5);
        check(softly, output, SPEED, 3.0, 0.004233, 3.0e-5);
        check(softly, output, SPEED, 3.8, 0.002382, 3.0e-5);
        // 50 ms after the clearing, 2.5% under Xyce's - its power channel's washouts through the clearing's swing -, within 1% from 0.4 s on.
        check(softly, output, "control:GEN_P2VS", 2.65, 0.020863, 0.020863 * 0.03);
        check(softly, output, "control:GEN_P2VS", 3.0, 0.011063, 0.011063 * 0.01);
        check(softly, output, "control:GEN_P2VS", 3.8, 0.005793, 0.005793 * 0.01);
        softly.assertAll();
    }

    /**
     * {@code PscadFileWriterTest#writeRunsADec2AAndADec3AAsXyceDoes}: the DEC2A's pulse from its trigger at 3 s, run 6 s, B1 within 1e-4 (9.6e-5 at the end, the machine slowed under the boost),
     * the speed within 1e-5, the pulse within 1e-4 of Xyce's; the DEC3A's switch opening in a three-phase fault on B2 from 2.5 to 2.6 s and
     * closing again, B1 within 2e-3 in the fault, 1e-3 just after and 3e-4 from 0.4 s after, the speed within 4e-5, the stabilizer's output
     * within 5e-4.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dec2a", "dec3a"})
    @Tag("emtp")
    void aDec2AAndADec3ARunAsXycesDo(String bench) {
        boolean boost = "dec2a".equals(bench);
        EmtpOutput output = run(boost ? ExampleNetworks.createTwoBusNetworkWithDec2A() : ExampleNetworks.createTwoBusNetworkWithDec3A(), bench, 6.0,
                boost ? "excitation-boost-gen-3.0" : "fault-b2-abc-2.5-2.6");
        double[][] xyce = boost
                ? new double[][] {{3.02, 327.6185, 0.999995, 0.06143}, {3.1, 333.0844, 0.999856, 0.08967}, {3.2, 341.2448, 0.999321, 0.08347},
                    {3.4, 351.0495, 0.997034, 0.06842}, {3.8, 341.9453, 0.993233, 0.04586}, {4.5, 333.1402, 0.990133, 0.02277},
                    {5.0, 332.1955, 0.989403, 0.01381}, {5.9, 329.0815, 0.989514, 0.00562}}
                : new double[][] {{2.55, 24.7595, 1.002866, 0.0, 0.1}, {2.7, 282.3077, 1.010051, 0.0, 0.1}, {3.02, 329.4966, 1.011609, 0.0, 0.08993},
                    {3.2, 330.9206, 1.011001, 1.0, 0.08828}, {3.4, 346.4864, 1.009151, 1.0, 0.04181}, {3.8, 336.8808, 1.005896, 1.0, 0.02799},
                    {4.5, 329.6705, 1.003158, 1.0, 0.01068}, {5.9, 326.9307, 1.001637, 1.0, 0.00077}};
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : xyce) {
            double t = window[0];
            bus(softly, output, t, window[1], boost ? 1.0e-4 : t < 2.6 ? 0.002 : t < 2.9 ? 1.0e-3 : 3.0e-4);
            check(softly, output, SPEED, t, window[2] - 1.0, boost ? 1.0e-5 : 4.0e-5);
            if (boost) {
                check(softly, output, "control:GEN_DECV", t, window[3], 1.0e-4);
            } else {
                check(softly, output, "control:GEN_P1VS", t, window[4], 5.0e-4);
            }
        }
        softly.assertAll();
    }

    /**
     * {@code PscadFileWriterTest#writeRunsADec1AAsXyceDoes}: the DEC1A on the ST1A, the standard's sample data and latched, through a
     * three-phase fault on B2 from 2.5 to 2.6 s, run 6 s: the terminal voltage within 1.5e-3, VS, VAN and VO within 3e-3, S1 and the
     * bang-bang limiter within 0.01 of Xyce's, at instants clear of their switching.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sample", "latched"})
    @Tag("emtp")
    void aDec1ARunsAsXycesDoes(String bench) {
        boolean sample = "sample".equals(bench);
        EmtpOutput output = run(sample ? ExampleNetworks.createTwoBusNetworkWithDec1A(0.0015, 5.5) : ExampleNetworks.createTwoBusNetworkWithDec1A(0.02, 10.0),
                "dec1a-" + bench, 6.0, "fault-b2-abc-2.5-2.6");
        double[][] xyce = sample
                ? new double[][] {{2.4, 1.000001, -0.066000, 0.0, 0.0, 0.1, 0.0}, {2.505, 0.097811, -0.066000, 0.997956, 0.006652, 0.1, 0.0},
                    {2.55, 0.073232, -0.064299, 0.0, 0.035097, 0.1, 0.0}, {2.65, 0.730687, -0.066000, 0.0, 0.010054, 0.1, 0.0},
                    {2.8, 0.873890, -0.066000, 0.0, 0.001542, 0.1, 0.0}, {3.0, 1.002929, -0.066000, 0.0, 0.000129, 0.1, 0.0},
                    {3.2, 1.088770, -0.066000, 0.0, 0.000017, 0.1, 0.0}, {4.0, 1.082089, -0.066000, 0.0, 0.0, 0.192033, 0.0},
                    {4.7, 1.016280, -0.066000, 0.0, 0.0, 0.1, 0.0}, {5.9, 1.001853, -0.066000, 0.0, 0.0, 0.1, 0.0}}
                : new double[][] {{2.4, 1.000001, -0.066000, 0.0, 0.0, 0.1, 0.0}, {2.505, 0.097813, -0.066000, 0.999994, 0.006669, 0.1, 0.0},
                    {2.65, 0.730687, 0.100000, 0.999979, 0.2, 0.1, 0.0}, {2.8, 0.873890, 0.100000, 0.999955, 0.2, 0.1, 0.0},
                    {3.0, 1.005499, 0.100000, 0.999941, 0.2, 0.1, 0.0}, {3.2, 1.143999, -0.066000, 0.999975, 0.2, 0.187345, 0.999995},
                    {3.5, 1.199142, -0.066000, 0.999994, 0.116555, 0.3, 1.0}, {4.0, 1.133328, -0.066000, 0.999998, 0.0, 0.3, 0.999986},
                    {4.7, 1.034058, -0.066000, 0.999998, 0.0, 0.1, 0.0}, {5.9, 1.004976, -0.066000, 0.999998, 0.0, 0.1, 0.0}};
        String[] columns = {"control:GEN_DECVT", "control:GEN_DECV", "control:GEN_DECS", "control:GEN_DECVAN", "control:GEN_DECVO", "control:GEN_DECP"};
        double[] tolerances = {1.5e-3, 3.0e-3, 0.01, 3.0e-3, 3.0e-3, 0.01};
        SoftAssertions softly = new SoftAssertions();
        for (double[] reference : xyce) {
            for (int c = 0; c < columns.length; c++) {
                check(softly, output, columns[c], reference[0], reference[c + 1], tolerances[c]);
            }
        }
        softly.assertAll();
    }

    private static EmtpOutput run(Network bench, String name, double runEnd, String events) {
        Network network = ExampleNetworks.withLoadFlow(bench);
        return EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "stabilizer-" + name), new EmtpSimulationSettings(TIME_STEP, runEnd,
                FREQUENCY), EmtEventsXml.read(ExampleNetworks.resource("/events/" + events + ".xml"))), EmtpSettings.defaults()).output();
    }

    /** B1's phase a's crest, kV, over 20 ms from {@code t}, against Xyce's within {@code tolerance} of it. */
    private static void bus(SoftAssertions softly, EmtpOutput output, double t, double xyce, double tolerance) {
        double crest = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, t, t + 0.02).magnitude() / 1.0e3;
        System.out.printf(Locale.ROOT, "|B1A| at %.3f s: %.4f kV (Xyce %.4f, %.1e off)%n", t, crest, xyce, crest / xyce - 1.0);
        softly.assertThat(crest / xyce).as("|B1A| at %.3f s against Xyce", t).isCloseTo(1.0, within(tolerance));
    }

    /** {@code column}'s 20 ms mean from {@code t} against Xyce's within {@code tolerance}; printed. */
    private static void check(SoftAssertions softly, EmtpOutput output, String column, double t, double xyce, double tolerance) {
        double value = output.mean(column, t, t + 0.02);
        System.out.printf(Locale.ROOT, "%s at %.3f s: %.6f (Xyce %.6f, %.1e off)%n", column, t, value, xyce, value - xyce);
        softly.assertThat(value).as("%s at %.3f s", column, t).isCloseTo(xyce, within(tolerance));
    }
}
