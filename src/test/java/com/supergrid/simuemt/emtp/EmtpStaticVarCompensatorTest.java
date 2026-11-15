/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.extensions.svc.StaticVarCompensatorModel;
import com.powsybl.simuemt.extensions.svc.Svc1InitialState;
import com.powsybl.simuemt.extensions.svc.Svc1Model;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.supergrid.simuemt.pscadpro.PscadProSettings;
import com.supergrid.simuemt.pscadpro.PscadProSimulation;
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
import static org.assertj.core.api.Assertions.within;

/**
 * {@link EmtpStaticVarCompensators}: an SVC1 written and run live, held to upstream's checks of its ATP and Xyce backends.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpStaticVarCompensatorTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double WINDOW = 0.02;
    private static final double TRANSIENT_TOLERANCE = 0.02;

    /** The SVC on the two-bus bench: its probes, its EMFs a step ahead, its coupling reactors, its fixed capacitor, its regulator. */
    @Test
    void writesAnSvc1(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.createTwoBusNetworkWithSvc1();
        ExampleNetworks.runLoadFlow(network);
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY), List.of()),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).contains("_RLC;SVC_LCa;2;2;SVC_Ka,B2a,", "_c_fs;SVC_Ha;2;2;SVC_Ha,SVC_Va,", "_c_fslim;SVC_B;4;4;SVC_B,SVC_VE,0,0,");
        assertThat(lines.stream().filter(l -> l.startsWith("_RLC;SVC_Ca;"))).hasSize(1);
    }

    /**
     * Upstream's {@code BackendComparisonTest#bothBackendsHoldTheSvcAndTheBatteryWhereTheLoadFlowPutsThem}, SVC: the SVC on the machine's
     * bench, at its capacitive limit in the load flow, nothing disturbing it - held to its checks: B2 within 1e-3 pu of the load flow from
     * the first period on, at 0.3 s and at the end; the frequency within 2e-3 Hz. Run 1.5 s, kept in {@code target/emtp/svc-held}.
     *
     * <p>Run 2026-11-15: B2 0.99461, 0.99463 and 0.99463 pu against the load flow's 0.99464; the frequency 2e-5 Hz off; B on its limit,
     * 0.1 pu, throughout.
     */
    @Test
    @Tag("emtp")
    void theSvcHoldsItsBusWhereTheLoadFlowPutsIt() {
        double runEnd = 1.5;
        Network network = ExampleNetworks.createTwoBusNetworkWithSvc1();
        ExampleNetworks.runLoadFlow(network);
        Bus b2 = network.getBusBreakerView().getBus("B2");
        double loadFlow = b2.getV() / b2.getVoltageLevel().getNominalV();
        double crest = b2.getVoltageLevel().getNominalV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "svc-held"), new EmtpSimulationSettings(TIME_STEP, runEnd,
                FREQUENCY), List.of()), EmtpSettings.defaults()).output();
        String b2a = EmtpNetlistWriter.voltageScope("B2", 'A');
        SoftAssertions softly = new SoftAssertions();
        for (double start : new double[] {0.0, 0.3, runEnd - WINDOW}) {
            double voltage = output.fundamentalPhasor(b2a, FREQUENCY, start, start + WINDOW).magnitude() / crest;
            System.out.printf(Locale.ROOT, "B2 from %.2f s: %.5f pu, the load flow's %.5f%n", start, voltage, loadFlow);
            softly.assertThat(voltage).as("B2 from %.2f s, pu", start).isCloseTo(loadFlow, within(1.0e-3));
        }
        Phasor early = output.fundamentalPhasor(b2a, FREQUENCY, 0.3, 0.3 + WINDOW);
        Phasor late = output.fundamentalPhasor(b2a, FREQUENCY, runEnd - WINDOW, runEnd);
        double deviation = -Math.IEEEremainder(late.angleRadians() - early.angleRadians(), 2 * Math.PI) / (2 * Math.PI * (runEnd - WINDOW - 0.3));
        System.out.printf(Locale.ROOT, "frequency deviation %.2e Hz; B %.5f pu at the end, %.5f at the start%n", deviation, output.mean("control:SVC_B", runEnd - WINDOW,
                runEnd), output.mean("control:SVC_B", 0.0, WINDOW));
        softly.assertThat(Math.abs(deviation)).as("frequency deviation, Hz").isLessThan(2.0e-3);
        softly.assertAll();
    }

    /**
     * The SVC on the machine's bench through a three-phase fault on B2, through 5 ohm for 0.1 s, against PSCAD Professional run live from
     * rest - the SVC let go at 2 s, as the machines, the fault 2 s on from EMTP's: its voltage as it measures it, {@code control:SVC_VM}
     * against PSCAD's {@code SVC_VT}, and its susceptance {@code B}; within 1e-3 pu settled, before the fault and in the last 100 ms, and
     * {@code TRANSIENT_TOLERANCE} through the fault and the recovery. Kept in {@code target/emtp/svc-fault-pscad} and {@code
     * target/pscad/svc-fault-pscad}.
     *
     * <p>Run 2026-11-15: its voltage within 6.5e-4 pu of PSCAD's settled, 1.4e-3 through the fault; its susceptance on its capacitive
     * limit throughout in both - the regulator never leaving it, which {@link #aFaultAtARegulatingSvcsBusRunsInEmtpAsInPscadProfessional}
     * does.
     */
    @Test
    @Tag("emtp")
    @Tag("pscad")
    void aFaultAtTheSvcsBusRunsInEmtpAsInPscadProfessional() throws IOException {
        List<EmtpVscFaultAgainstPscadTest.Channel> channels = List.of(new EmtpVscFaultAgainstPscadTest.Channel("SVC_V", "control:SVC_VM", "SVC_VT", 1.0),
                new EmtpVscFaultAgainstPscadTest.Channel("SVC_B", "control:SVC_B", "SVC_B", 1.0));
        EmtpVscFaultAgainstPscadTest.compare(() -> {
            Network network = ExampleNetworks.createTwoBusNetworkWithSvc1();
            ExampleNetworks.runLoadFlow(network);
            return network;
        }, start -> List.of(EmtpVscFaultAgainstPscadTest.fault("ABC", start)), channels, "svc-fault-pscad", 5.0e-3, 1.0e-3, TRANSIENT_TOLERANCE, true,
                EmtpVscFaultAgainstPscadTest.PSCAD_SHIFT, PscadSwitching.INTERPOLATED);
    }

    /**
     * The same with an SVC of +/-1 pu on 100 MVA - {@link #regulatingSvc} -, regulating B2 to 400 kV within its range in the load flow: its
     * regulator swung to its capacitive limit through the fault and back, its controlled reactor carrying {@code B - Bfc} all along.
     * PSCAD from its steady state ({@code PscadProSimulation#runFromSteadyState}): from rest, its reactor's DC offset would have nothing to
     * decay through. Its voltage and its susceptance within 1e-3 pu of PSCAD's before the fault and in the last 100 ms, {@code
     * TRANSIENT_TOLERANCE} through the fault and the recovery, on 5 ms means. Kept in {@code target/emtp/svc-regulating-pscad} and {@code
     * target/pscad/svc-regulating-pscad}.
     *
     * <p>Run 2026-11-15: B 0.313 pu before the fault, on its 1 pu limit through it and 50 ms on, back by the end - within 1.95e-4 pu of
     * PSCAD's settled, 4.8e-3 through the fault; its voltage 0.009 pu through the fault, within 7e-5 pu of PSCAD's settled, 2.0e-3
     * through it.
     */
    @Test
    @Tag("emtp")
    @Tag("pscad")
    void aFaultAtARegulatingSvcsBusRunsInEmtpAsInPscadProfessional() {
        double fault = 0.3;
        double runEnd = 0.75;
        double window = 5.0e-3;
        String name = "svc-regulating-pscad";
        List<EmtEvent> events = List.of(EmtpVscFaultAgainstPscadTest.fault("ABC", fault));
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(regulatingSvc(), Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP, runEnd,
                FREQUENCY), events), EmtpSettings.defaults()).output();
        PscadOutput pscad = PscadProSimulation.runFromSteadyState(regulatingSvc(), Path.of("target", "pscad", name), new PscadSimulationSettings(TIME_STEP, runEnd,
                FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.INTERPOLATED), events, PscadProSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (String[] channel : new String[][] {{"control:SVC_VM", "SVC_VT"}, {"control:SVC_B", "SVC_B"}}) {
            double settled = 0.0;
            double through = 0.0;
            for (double t = 0.1; t + window <= runEnd + 1.0e-9; t += window) {
                double gap = Math.abs(emtp.mean(channel[0], t, t + window) - pscad.mean(channel[1], t, t + window));
                if (t + window <= fault || t >= runEnd - 0.1) {
                    settled = Math.max(settled, gap);
                } else {
                    through = Math.max(through, gap);
                }
            }
            System.out.printf(Locale.ROOT, "%s: %s settled within %.2e pu, through the fault %.2e pu of PSCAD's; EMTP's %.4f before, %.4f through, %.4f 50 ms on%n", name,
                    channel[0], settled, through, emtp.mean(channel[0], 0.25, fault), emtp.mean(channel[0], fault + 0.05, fault + 0.1), emtp.mean(channel[0], 0.45, 0.5));
            softly.assertThat(settled).as("%s settled against PSCAD's, pu", channel[0]).isLessThan(1.0e-3);
            softly.assertThat(through).as("%s through the fault against PSCAD's, pu", channel[0]).isLessThan(TRANSIENT_TOLERANCE);
        }
        softly.assertAll();
    }

    /** The machine's two-bus bench with an SVC1 of +/-1 pu on 100 MVA on B2, regulating it to 400 kV - within its range -, the load flow
     * run. */
    static Network regulatingSvc() {
        Network network = ExampleNetworks.createTwoBusNetworkWithEmtExtensions();
        Bus bus = network.getBusBreakerView().getBus("B2");
        double nominalV = bus.getVoltageLevel().getNominalV();
        double siemens = 1.0 * Svc1InitialState.BASE_APPARENT_POWER_MVA / (nominalV * nominalV);
        bus.getVoltageLevel().newStaticVarCompensator().setId("SVC").setBus("B2").setConnectableBus("B2").setBmin(-siemens).setBmax(siemens)
                .setLocalTargetV(nominalV).newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add().add();
        network.getStaticVarCompensator("SVC").addExtension(StaticVarCompensatorModel.class, new Svc1Model(1.0, 0.03, 0.02, 40.0, 0.05, -1.0, 1.0));
        ExampleNetworks.runLoadFlow(network);
        return network;
    }

    /**
     * Upstream's {@code AtpSolverIntegrationTest#svcAtItsCapacitiveLimitLeavesTheHealthyPhasesOfAFaultedBusAlone}: the SVC on B2 behind
     * an ideal infinite bus, at its capacitive limit, through a fault to earth on B2's phase a from 0.5 to 0.6 s: its fixed capacitor
     * carries all of it, its controlled reactor nothing, its EMFs on each phase's voltage - the healthy phases within 5 V of theirs before
     * the fault. Kept in {@code target/emtp/svc-fault}.
     *
     * <p>Run 2026-11-15: B2B and B2C within 0.01 V of their 324.8441 kV through the fault (ATP's within 0.1 V).
     */
    @Test
    @Tag("emtp")
    void theSvcAtItsCapacitiveLimitLeavesTheHealthyPhasesOfAFaultedBusAlone() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        ExampleNetworks.addSvc1(network, "B2");
        ExampleNetworks.runLoadFlow(network);
        Svc1InitialState initial = Svc1InitialState.of(network.getStaticVarCompensator("SVC"), (Svc1Model) network.getStaticVarCompensator("SVC")
                .getExtension(StaticVarCompensatorModel.class));
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "svc-fault"), new EmtpSimulationSettings(TIME_STEP, 0.7,
                FREQUENCY), List.of(new BusFault("FLT", "B2", 'A', 0.5, 0.6))), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(initial.susceptance()).as("the SVC's susceptance in the load flow, pu: its capacitive limit").isCloseTo(0.1, within(1.0e-6));
        for (char phase : new char[] {'B', 'C'}) {
            String scope = EmtpNetlistWriter.voltageScope("B2", phase);
            double before = output.fundamentalPhasor(scope, FREQUENCY, 0.48, 0.5).magnitude();
            double during = output.fundamentalPhasor(scope, FREQUENCY, 0.56, 0.58).magnitude();
            System.out.printf(Locale.ROOT, "B2%c %.4f kV before, %+.2f V through the fault%n", phase, before / 1.0e3, during - before);
            softly.assertThat(during - before).as("|B2%c| through the fault, V", phase).isCloseTo(0.0, within(5.0));
        }
        softly.assertAll();
    }
}
