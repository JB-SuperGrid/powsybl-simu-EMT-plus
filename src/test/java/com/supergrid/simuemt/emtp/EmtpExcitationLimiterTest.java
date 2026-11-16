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
import com.powsybl.simuemt.extensions.excitation.limiter.PfVarType2Model;
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
 * V/Hz limiter's ratio and output within 9e-6 and 1e-6 of Xyce's, with its PI and with a derivative too; the UEL2C's output within
 * 2.6e-6, summing or taking over; the SCL1C's within 2.6e-6, on the reactive current or its logic; the PF/var controller's within
 * 1e-5 and the machine's reactive power within 2.3e-5, on the reactive power, the power factor or beside an SCL1C.
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

    /**
     * {@code PscadFileWriterTest#writeRunsAUel2CAsXyceDoes}: the UEL2C's bench, summing or taking over, through a 5% step down of the
     * voltage reference at 0.5 s, run 12 s: at rest before; its output, 20 ms means from each instant after the step, within 1e-4 of
     * Xyce's.
     */
    @ParameterizedTest(name = "takeover {0}")
    @ValueSource(booleans = {false, true})
    @Tag("emtp")
    void aUel2CHoldsTheMachineAsXycesDoes(boolean takeover) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithUel2C(takeover));
        EmtpOutput output = step(network, "uel-" + (takeover ? "takeover" : "summing"), -0.05);
        double[] after = {0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 11.46};
        double[] xyce = takeover ? new double[] {-0.003641, -0.001706, 0.001123, 0.006331, 0.010589, 0.010438, 0.007082}
                : new double[] {0.006473, 0.015629, 0.029748, 0.037048, 0.035564, 0.036573, 0.036995};
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:GEN_UELS", 0.1, 0.4)).as("the output before the step")
                .isCloseTo(takeover ? ExampleNetworks.UEL2C_TAKEOVER_OUTPUT_MIN : 0.0, within(1.0e-6));
        compare(softly, output, "control:GEN_UELS", after, xyce, 1.0e-4);
        softly.assertAll();
    }

    /**
     * {@code PscadFileWriterTest#writeRunsAnScl1CAsXyceDoes}: the SCL1C's bench, on the reactive current or on its logic, through a 5% step
     * up of the voltage reference at 0.5 s, run 12 s: at rest before; its output after the step within 7e-4 of Xyce's.
     */
    @ParameterizedTest(name = "logic {0}")
    @ValueSource(booleans = {false, true})
    @Tag("emtp")
    void anScl1CHoldsTheStatorCurrentAsXycesDoes(boolean logic) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithScl1C(logic));
        EmtpOutput output = step(network, "scl-" + (logic ? "logic" : "reactive-current"), 0.05);
        double[] after = {0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 11.0};
        double[] xyce = logic ? new double[] {0.0, -0.000146, -0.010459, -0.019044, -0.020766, -0.021751, -0.022633}
                : new double[] {-0.002645, -0.001498, -0.011850, -0.019491, -0.021028, -0.021901, -0.022692};
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:GEN_NS", 0.1, 0.4)).as("the output before the step").isCloseTo(0.0, within(1.0e-6));
        compare(softly, output, "control:GEN_NS", after, xyce, 7.0e-4);
        softly.assertAll();
    }

    /**
     * {@code PscadFileWriterTest#writeRunsAPfVarControllerAsXyceDoes}: the PF/var controller's bench, on the reactive power, on the
     * normalized power factor, or with an SCL1C, through a 5% step up of the voltage reference at 0.5 s, run 12 s: at rest before; the
     * machine's reactive power and the controller's output after the step on Xyce's.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"var", "pf", "scl"})
    @Tag("emtp")
    void aPfVarControllerHoldsTheMachineAsXycesDoes(String bench) {
        Network network = ExampleNetworks.withLoadFlow(switch (bench) {
            case "pf" -> ExampleNetworks.createTwoBusNetworkWithPfVarController(PfVarType2Model.Mode.POWER_FACTOR);
            case "scl" -> ExampleNetworks.createTwoBusNetworkWithPfVarControllerAndScl1C();
            default -> ExampleNetworks.createTwoBusNetworkWithPfVarController(PfVarType2Model.Mode.REACTIVE_POWER);
        });
        EmtpOutput output = step(network, "pfv-" + bench, 0.05);
        double[] after = {0.5, 1.0, 2.0, 4.0, 6.0, 8.0, 11.0};
        double[][] xyce = switch (bench) {
            case "pf" -> new double[][] {{0.195047, -0.017318}, {0.197080, -0.048013}, {0.117143, -0.057671}, {0.108399, -0.047092},
                {0.115807, -0.050439}, {0.112339, -0.049889}, {0.113246, -0.050026}};
            case "scl" -> new double[][] {{0.198105, -0.000896}, {0.220200, -0.039967}, {0.176784, -0.051575}, {0.108250, -0.048103},
                {0.115452, -0.049225}, {0.113505, -0.049933}, {0.113272, -0.049965}};
            default -> new double[][] {{0.190199, -0.022072}, {0.195960, -0.040500}, {0.139174, -0.051894}, {0.110130, -0.048357},
                {0.114900, -0.049633}, {0.113310, -0.049952}, {0.113252, -0.049977}};
        };
        double[] tolerances = switch (bench) {
            case "pf" -> new double[] {5.0e-4, 1.5e-4};
            case "scl" -> new double[] {3.0e-3, 3.0e-4};
            default -> new double[] {1.0e-4, 5.0e-5};
        };
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:GEN_CPV", 0.1, 0.4)).as("the output before the step").isCloseTo(0.0, within(5.0e-5));
        double[] reactive = new double[after.length];
        double[] controller = new double[after.length];
        for (int k = 0; k < after.length; k++) {
            reactive[k] = xyce[k][0];
            controller[k] = xyce[k][1];
        }
        compare(softly, output, "control:GEN_LQT", after, reactive, tolerances[0]);
        compare(softly, output, "control:GEN_CPV", after, controller, tolerances[1]);
        softly.assertAll();
    }

    /** {@code network} through a step of {@code delta} on its voltage reference at 0.5 s, run 12 s, kept in {@code target/emtp/<name>}. */
    private static EmtpOutput step(Network network, String name, double delta) {
        return EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP, 12.0, FREQUENCY),
                List.of(new VoltageReferenceStep("GEN", 0.5, delta))), EmtpSettings.defaults()).output();
    }

    /** {@code column}'s 20 ms means from each instant {@code after} the step at 0.5 s, against Xyce's within {@code tolerance}; printed. */
    private static void compare(SoftAssertions softly, EmtpOutput output, String column, double[] after, double[] xyce, double tolerance) {
        for (int k = 0; k < after.length; k++) {
            double t = 0.5 + after[k];
            double value = output.mean(column, t, t + 0.02);
            System.out.printf(Locale.ROOT, "%s %.2f s after: %.6f (Xyce %.6f, %.1e off)%n", column, after[k], value, xyce[k], value - xyce[k]);
            softly.assertThat(value).as("%s %.2f s after the step", column, after[k]).isCloseTo(xyce[k], within(tolerance));
        }
    }
}
