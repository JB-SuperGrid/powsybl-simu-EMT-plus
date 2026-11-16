/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.MachineInitialState;
import com.powsybl.simuemt.extensions.event.VoltageReferenceStep;
import com.powsybl.simuemt.extensions.excitation.limiter.KundurVoltsPerHertzLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.Oel1BModel;
import com.powsybl.simuemt.extensions.excitation.limiter.OverexcitationLimiterModel;
import com.powsybl.simuemt.extensions.excitation.limiter.VoltsPerHertzLimiterModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * Excitation limiters in EMTP ({@link EmtpExcitationLimiters}) on upstream's DC1A machine, held to Xyce's runs upstream's PSCAD
 * tests store ({@code PscadFileWriterTest}), which ATP and Kestrel are held to as well.
 *
 * <p>Run 2026-11-16: the OEL1B's B1 within 5.9e-5 of Xyce's summing, 1.1e-4 taking over, {@code VOEL} -0.506 through the fault; the
 * V/Hz limiter's ratio and output within 9e-6 and 1e-6 of Xyce's, with its PI and with a derivative too.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpExcitationLimiterTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    /**
     * {@code PscadFileWriterTest#writeRunsAMachineUnderItsOel1BAsXyceDoes}: the DC1A machine with an OEL1B 0.1 pu over the load flow's
     * field current, its delay 0.1 s, summing into the error or taking over the regulator's output, through a phase-a fault on B2 from
     * 2.5 to 2.6 s, run 4 s: at rest before; picked up through the fault, {@code VOEL} under -0.4; B1's phase a on Xyce's 2.65 to 3.8 s on
     * within 5e-4 - the two modes 1.7e-3 apart at 2.65 s.
     */
    @ParameterizedTest(name = "takeover {0}")
    @ValueSource(booleans = {false, true})
    @Tag("emtp")
    void anOel1BActsThroughAFaultAsXycesDoes(boolean takeover) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithDc1A());
        Generator generator = network.getGenerator("GEN");
        double ifd0 = MachineInitialState.subtransient(generator, (SubtransientMachineModel) generator.getExtension(GeneratorModel.class), 0.0).efd();
        generator.addExtension(OverexcitationLimiterModel.class, new Oel1BModel(true, takeover, ifd0 + 0.1, 0.1, 0.05, 12.0, ifd0, -5.0));
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "oel-" + (takeover ? "takeover" : "summing")),
                new EmtpSimulationSettings(TIME_STEP, 4.0, FREQUENCY), EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-a-2.5-2.6-at-order.xml"))),
                EmtpSettings.defaults()).output();
        double[][] xyce = {{293.9422, 310.1115, 322.3052, 333.2370, 330.1104}, {294.4517, 312.4565, 325.6556, 334.6196, 330.0989}};
        double[] windows = {2.65, 2.8, 3.0, 3.4, 3.8};
        String voel = "control:GEN_OELV";
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean(voel, 2.0, 2.4)).as("VOEL at rest").isCloseTo(0.0, within(1.0e-6));
        double fault = output.mean(voel, 2.58, 2.6);
        System.out.printf(Locale.ROOT, "takeover %s: VOEL %.3f through the fault%n", takeover, fault);
        softly.assertThat(fault).as("VOEL through the fault").isLessThan(-0.4);
        for (int i = 0; i < windows.length; i++) {
            double crest = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, windows[i], windows[i] + 0.02).magnitude() / 1.0e3;
            double ratio = crest / xyce[takeover ? 1 : 0][i];
            System.out.printf(Locale.ROOT, "takeover %s: |B1A| at %.2f s %.4f kV (Xyce %.4f, %.1e off)%n", takeover, windows[i], crest, xyce[takeover ? 1 : 0][i],
                    ratio - 1.0);
            softly.assertThat(ratio).as("|B1A| at %.2f s against Xyce", windows[i]).isCloseTo(1.0, within(5.0e-4));
        }
        softly.assertAll();
    }

    /**
     * {@code PscadFileWriterTest#writeRunsAVoltsPerHertzLimiterAsXyceDoes}: the V/Hz limiter's bench, its PI with or without a
     * derivative, through a 5% step up of the voltage reference at 0.5 s, run 12 s: at rest before; its ratio and its output, 20 ms means
     * from each instant after the step, within 1e-4 and 3e-5 of Xyce's; the ratio near its limit at the end.
     */
    @ParameterizedTest(name = "derivative {0}")
    @ValueSource(booleans = {false, true})
    @Tag("emtp")
    void aVoltsPerHertzLimiterHoldsItsRatioAsXycesDoes(boolean derivative) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithVoltsPerHertzLimiter(derivative));
        double limit = ((KundurVoltsPerHertzLimiterModel) network.getGenerator("GEN").getExtension(VoltsPerHertzLimiterModel.class)).getLimit();
        double stepAt = 0.5;
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "vhz-" + (derivative ? "pid" : "pi")),
                new EmtpSimulationSettings(TIME_STEP, 12.0, FREQUENCY), List.of(new VoltageReferenceStep("GEN", stepAt, 0.05))), EmtpSettings.defaults()).output();
        String error = "control:GEN_VHZE";
        String out = "control:GEN_VHZO";
        double[] after = {0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 11.0};
        double[][] xyce = derivative
                ? new double[][] {{1.021888, 0.0}, {1.036772, 0.004529}, {1.044573, 0.011470}, {1.031666, 0.020524}, {1.024773, 0.024206},
                    {1.022886, 0.026177}, {1.021415, 0.027855}}
                : new double[][] {{1.021888, 0.0}, {1.037560, 0.003694}, {1.044941, 0.011886}, {1.031163, 0.020822}, {1.024717, 0.024191},
                    {1.022949, 0.026148}, {1.021430, 0.027839}};
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean(error, 0.1, 0.4) + limit).as("the ratio before the step").isCloseTo(limit - ExampleNetworks.VHZ_MARGIN, within(1.0e-4));
        softly.assertThat(output.mean(out, 0.1, 0.4)).as("the output before the step").isCloseTo(0.0, within(1.0e-6));
        for (int k = 0; k < after.length; k++) {
            double t = stepAt + after[k];
            double ratio = output.mean(error, t, t + 0.02) + limit;
            double value = output.mean(out, t, t + 0.02);
            System.out.printf(Locale.ROOT, "derivative %s, %.1f s after: ratio %.6f (Xyce %.6f), output %.6f (Xyce %.6f)%n", derivative, after[k], ratio, xyce[k][0],
                    value, xyce[k][1]);
            softly.assertThat(ratio).as("the ratio %.1f s after the step", after[k]).isCloseTo(xyce[k][0], within(1.0e-4));
            softly.assertThat(value).as("the output %.1f s after the step", after[k]).isCloseTo(xyce[k][1], within(3.0e-5));
        }
        softly.assertThat(output.mean(error, 11.5, 12.0)).as("the ratio at the end, near its limit").isCloseTo(0.0, within(0.002));
        softly.assertAll();
    }
}
