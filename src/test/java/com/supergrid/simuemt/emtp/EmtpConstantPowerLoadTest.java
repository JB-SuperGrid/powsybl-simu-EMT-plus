/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.load.ConstantPowerLoadModel;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.ExpectedLoadCurrents;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Constant-power loads in EMTP ({@link EmtpConstantPowerLoads}), held where upstream holds ATP's ({@code AtpSolverIntegrationTest}): to
 * the closed form of what each branch draws at the voltage it settles at ({@link ExpectedLoadCurrents}), and through a fault on its own
 * bus behind the two-bus bench's machine.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpConstantPowerLoadTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    /**
     * {@code staticLoadDrawsWhatItsModelSaysBranchByBranch}: fed from an infinite bus through the line - balanced, unbalanced by {@code
     * loadAsymmetrical} as an earthed star and as a delta -, run 0.3 s: each line current, {@code (V1 - V2) / (R + jX)}, within 0.5% of
     * what the branches draw at B2's voltage, {@code U P / Vrms^2} and the reactance's. And balanced, the load drawing 20% more than its
     * load flow's - B2 off the voltage the branches start from, their power held by the controlled current alone.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"infinite-bus-generator", "load-asymmetrical-y", "load-asymmetrical-delta", "infinite-bus-generator-raised"})
    @Tag("emtp")
    void aConstantPowerLoadDrawsWhatItSaysBranchByBranch(String bench) {
        boolean raised = bench.endsWith("-raised");
        String connection = raised ? bench.substring(0, bench.length() - "-raised".length()) : bench;
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"),
                ExampleNetworks.resource("/two-bus-extensions-" + connection + ".xml"));
        Load load = network.getLoad("LOAD");
        load.addExtension(LoadModel.class, new ConstantPowerLoadModel(0.7));
        network = ExampleNetworks.withLoadFlow(network);
        load = network.getLoad("LOAD");
        if (raised) {
            load.setP0(1.2 * load.getP0());
        }
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "constant-power-" + bench),
                new EmtpSimulationSettings(TIME_STEP, 0.3, FREQUENCY)), EmtpSettings.defaults()).output();
        Line line = network.getLine("L1_2");
        double[][] loadBus = new double[3][];
        double[][] measured = new double[3][];
        for (int k = 0; k < 3; k++) {
            double[] source = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", "ABC".charAt(k)), FREQUENCY, 0.2, 0.26));
            loadBus[k] = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", "ABC".charAt(k)), FREQUENCY, 0.2, 0.26));
            measured[k] = divide(new double[] {source[0] - loadBus[k][0], source[1] - loadBus[k][1]}, new double[] {line.getR(), line.getX()});
        }
        double[][] expected = ExpectedLoadCurrents.of(load, loadBus);
        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < 3; k++) {
            double gap = Math.hypot(measured[k][0] - expected[k][0], measured[k][1] - expected[k][1]) / Math.hypot(expected[k][0], expected[k][1]);
            System.out.printf(Locale.ROOT, "%s, phase %c: line current %.2e off the closed form%n", connection, "ABC".charAt(k), gap);
            softly.assertThat(gap).as("%s: phase %c's line current against the closed form", connection, "ABC".charAt(k)).isLessThan(0.005);
        }
        softly.assertAll();
    }

    /**
     * {@code constantPowerLoadRidesThroughAFaultOnItsOwnBus}: the two-bus bench, its load at constant power, through a phase-a fault on B2
     * from 1.5 to 1.6 s, run 6 s: the frequency within 1e-3 Hz before the fault; at the end B2's phase a within 0.5% of its pre-fault
     * voltage, the phases balanced within 0.5%.
     */
    @Test
    @Tag("emtp")
    void aConstantPowerLoadRidesThroughAFaultOnItsOwnBus() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithConstantPowerLoad());
        double runEnd = 6.0;
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "constant-power-fault"), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY), EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-a-1.5-1.6.xml"))), EmtpSettings.defaults()).output();
        Phasor early = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, 0.5, 0.6);
        Phasor beforeFault = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, 1.3, 1.4);
        double frequencyDeviation = Math.IEEEremainder(beforeFault.angleRadians() - early.angleRadians(), 2 * Math.PI) / (2 * Math.PI * 0.8);
        double preFault = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'A'), FREQUENCY, 1.3, 1.4).magnitude();
        double[] atEnd = new double[3];
        for (int k = 0; k < 3; k++) {
            atEnd[k] = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", "ABC".charAt(k)), FREQUENCY, runEnd - 0.1, runEnd).magnitude() / preFault;
        }
        System.out.printf(Locale.ROOT, "frequency %.2e Hz off before the fault; B2 at the end %.5f %.5f %.5f of its pre-fault voltage%n", frequencyDeviation,
                atEnd[0], atEnd[1], atEnd[2]);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(Math.abs(frequencyDeviation)).as("frequency deviation before the fault, Hz").isLessThan(0.001);
        softly.assertThat(Math.abs(atEnd[0] - 1.0)).as("|B2A| at the end, of its pre-fault value").isLessThan(0.005);
        softly.assertThat(Math.max(Math.abs(atEnd[0] - atEnd[1]), Math.abs(atEnd[0] - atEnd[2]))).as("phases balanced at the end").isLessThan(0.005);
        softly.assertAll();
    }

    /** A phasor as {@code {re, im}}: {@link EmtpOutput}'s angle is a lag, conjugated back. */
    private static double[] complex(Phasor phasor) {
        return new double[] {phasor.magnitude() * Math.cos(phasor.angleRadians()), -phasor.magnitude() * Math.sin(phasor.angleRadians())};
    }

    private static double[] divide(double[] x, double[] y) {
        double d = y[0] * y[0] + y[1] * y[1];
        return new double[] {(x[0] * y[0] + x[1] * y[1]) / d, (x[1] * y[0] - x[0] * y[1]) / d};
    }
}
