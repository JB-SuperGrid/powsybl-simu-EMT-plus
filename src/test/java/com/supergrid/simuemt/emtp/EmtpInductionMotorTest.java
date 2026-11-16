/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.motor.InductionMotorModel;
import com.powsybl.simuemt.extensions.motor.InductionMotorOperatingPoint;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Induction motors ({@link InductionMotorModel}) in EMTP: an asynchronous machine started at the load flow's slip, its load torque
 * driven from its speed.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpInductionMotorTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double FAULT = 0.5;
    private static final double CLEARED = 0.6;
    private static final double RUN_END = 1.5;

    /** The fan motor: its machine on B2 at the operating point's slip, in percent; the load torque {@code T0 (w / w0)^2} on its mass. */
    @Test
    void writesAnInductionMotorAndItsLoadTorque(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithInfiniteBusAndFanMotor());
        InductionMotorOperatingPoint point = operatingPoint(network);
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).contains("_ASM;LOADa;8;4;B2a,LOAD_N,LOAD_Tm_1,LOAD_Omega_1,", "I=2," + EmtpNetlistWriter.number(100.0 * point.slip()) + ",0,",
                "_c_fu;LOAD_TLF;2;2;LOAD_Tm_1,LOAD_Omega_1,").noneMatch(l -> l.startsWith("_RLC;LOAD_Ra;"));
        // The speed in mechanical rad/s, as EMTP observes it: 4 poles at 50 Hz.
        String w0 = EmtpNetlistWriter.number(2.0 * Math.PI * FREQUENCY * 2.0 / 4.0 * (1.0 - point.slip()));
        String ratio = "*(0.5*(u[1]+ABS(u[1]))/" + w0 + ")";
        assertThat(String.join("", lines)).contains(EmtpNetlistWriter.number(point.loadTorque()) + ratio + ratio + "+");
    }

    /**
     * Upstream's {@code PscadFileWriterTest#writeRunsAnInductionMotorThroughAFaultAsAtpDoes}, its ATP half: the fan motor ({@code n} = 2)
     * and the damped one ({@code D} = 2) on the infinite-bus bench, a phase-a fault on B2 from 0.5 to 0.6 s, run 1.5 s - held to ATP's
     * Type-56: the speed's lowest point (ATP 0.955847 and 0.956051 of the synchronous speed) and its mean 40 to 50 ms after the clearing
     * (0.960293 and 0.960585) within 1e-3; before the fault and at the end, the load flow's speed within 1e-5 and B2 its
     * voltage within 1e-4. Kept in {@code target/emtp/<bench>}.
     *
     * <p>Run 2026-11-16: on the load flow's speed, 0.976593, before and at the end, B2 7.6e-6 under its load flow; the lowest speed
     * 0.956378 and 0.956590, 40 to 50 ms after the clearing 0.959790 and 0.960074: the dip 2.6% shallower than Type-56's, both motors
     * alike, through this unbalanced fault only - through a three-phase one, Type-56's within 3e-5 -; neither the inertia given as J
     * nor H, the machine's reference frame nor its voltage tolerance moves it by 1e-6: EMTP's machine's own, as upstream holds its
     * third-order machine 0.57% off Type-56.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"motor-fan", "motor-damped"})
    @Tag("emtp")
    void aMotorRidesThroughAFaultAsAtpsDoes(String bench) {
        boolean fan = "motor-fan".equals(bench);
        Network network = ExampleNetworks.withLoadFlow(fan ? ExampleNetworks.createTwoBusNetworkWithInfiniteBusAndFanMotor()
                : ExampleNetworks.createTwoBusNetworkWithInfiniteBusAndDampedMotor());
        double[] atp = fan ? new double[] {0.955847, 0.960293} : new double[] {0.956051, 0.960585};
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", bench), new EmtpSimulationSettings(TIME_STEP, RUN_END,
                FREQUENCY), List.of(new BusFault("F", "B2", 'A', FAULT, CLEARED))), EmtpSettings.defaults()).output();
        InductionMotorModel model = (InductionMotorModel) network.getLoad("LOAD").getExtension(LoadModel.class);
        double synchronous = 2.0 * Math.PI * FREQUENCY * 2.0 / model.getNumberOfPoles();
        double speed0 = 1.0 - operatingPoint(network).slip();
        String speed = "machine:Speed_LOAD";
        double before = output.mean(speed, FAULT - 0.1, FAULT) / synchronous;
        double lowest = Double.MAX_VALUE;
        for (double t = FAULT; t < CLEARED + 0.1; t += 5.0e-4) {
            lowest = Math.min(lowest, output.mean(speed, t, t + 1.0e-4) / synchronous);
        }
        double recovering = output.mean(speed, CLEARED + 0.04, CLEARED + 0.05) / synchronous;
        double end = output.mean(speed, RUN_END - 0.02, RUN_END) / synchronous;
        double crest = network.getBusBreakerView().getBus("B2").getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
        double b2 = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'A'), FREQUENCY, FAULT - 0.02, FAULT).magnitude() / crest;
        System.out.printf(Locale.ROOT, "%s: speed %.6f before (load flow %.6f), lowest %.6f (ATP %.6f), %.6f recovering (ATP %.6f), %.6f at the end; B2 %.6f of its"
                + " load flow%n", bench, before, speed0, lowest, atp[0], recovering, atp[1], end, b2);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(before).as("%s's speed before the fault", bench).isCloseTo(speed0, within(1.0e-5));
        softly.assertThat(lowest).as("%s's lowest speed, against ATP's", bench).isCloseTo(atp[0], within(1.0e-3));
        softly.assertThat(recovering).as("%s's speed recovering, against ATP's", bench).isCloseTo(atp[1], within(1.0e-3));
        softly.assertThat(end).as("%s's speed at the end", bench).isCloseTo(speed0, within(1.0e-5));
        softly.assertThat(b2).as("B2 before the fault, of its load flow").isCloseTo(1.0, within(1.0e-4));
        softly.assertAll();
    }

    /**
     * Upstream's {@code AtpSolverIntegrationTest#fanMotorLoadTorqueFollowsTheSquareOfItsSpeed}: the fan motor through a bolted
     * three-phase fault on B2 from 0.3 to 0.4 s, run 0.8 s - its lowest speed, of its load flow's, ATP's 0.9235 within 0.002; its load
     * torque {@code T0 (w / w0)^2} every 10 ms from the fault on, within 2e-4 of it.
     *
     * <p>Run 2026-11-16: the lowest speed 0.92353 (ATP 0.9235); the load torque off its law by 1.6e-7. Kept in {@code target/emtp/motor-fan-three-phase}.
     */
    @Test
    @Tag("emtp")
    void aFanMotorsLoadTorqueFollowsTheSquareOfItsSpeedThroughAThreePhaseFault() {
        double fault = 0.3;
        double runEnd = 0.8;
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithInfiniteBusAndFanMotor());
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "motor-fan-three-phase"), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY), List.of(new BusFault("FA", "B2", 'A', fault, 0.4), new BusFault("FB", "B2", 'B', fault, 0.4), new BusFault("FC", "B2", 'C', fault, 0.4))),
                EmtpSettings.defaults()).output();
        InductionMotorOperatingPoint point = operatingPoint(network);
        double w0 = output.mean("machine:Speed_LOAD", 0.0, 1.0e-3);
        double lowest = Double.MAX_VALUE;
        double worstLaw = 0.0;
        for (double t = fault; t <= runEnd - 1.0e-3; t += 0.01) {
            double speed = output.mean("machine:Speed_LOAD", t, t + 1.0e-3);
            double torque = output.mean("control:LOAD_TLF", t, t + 1.0e-3);
            worstLaw = Math.max(worstLaw, Math.abs(torque / point.loadTorque() - Math.pow(speed / w0, 2)));
            lowest = Math.min(lowest, speed / w0);
        }
        System.out.printf(Locale.ROOT, "three-phase fault: lowest speed %.5f of its load flow's (ATP 0.9235); the load torque off its law by %.1e%n", lowest, worstLaw);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(lowest).as("the lowest speed, of the load flow's, against ATP's").isCloseTo(0.9235, within(0.002));
        softly.assertThat(worstLaw).as("the load torque against T0 (w / w0)^2").isLessThan(2.0e-4);
        softly.assertAll();
    }

    private static InductionMotorOperatingPoint operatingPoint(Network network) {
        return InductionMotorOperatingPoint.of(network.getLoad("LOAD"), (InductionMotorModel) network.getLoad("LOAD").getExtension(LoadModel.class));
    }
}
