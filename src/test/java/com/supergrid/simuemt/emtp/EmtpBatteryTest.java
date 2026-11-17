/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.output.Phasor;
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
 * {@link EmtpBatteries}: a REEC_C battery written and run live, held to upstream's checks and to its model's limits.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpBatteryTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double WINDOW = 0.02;
    private static final double TRANSIENT_TOLERANCE = 5.0e-3;

    /** The battery on the two-bus bench: its probes, its coupling reactors, its PLL, its regulator. */
    @Test
    void writesAReecCBattery(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(bench(), tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY), List.of()),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).contains("_RLC;BAT_LCa;2;2;BAT_Ka,B2a,", "_c_fslim;BAT_PF;4;4;BAT_PF,BAT_PREF,0,0,");
    }

    /**
     * Upstream's {@code BackendComparisonTest#bothBackendsHoldTheSvcAndTheBatteryWhereTheLoadFlowPutsThem}, battery: the battery on the
     * machine's bench, delivering the 18.2 MW its share of the slack left it, nothing disturbing it - held to its checks: B2 within 1e-3
     * pu of the load flow from the first period on, at 0.3 s and at the end; the frequency within 2e-3 Hz. And its currents on the load
     * flow's within 1e-3 pu. Run 1.5 s, kept in {@code target/emtp/battery-held}.
     *
     * <p>Run 2026-11-15: B2 0.99269, 0.99270 and 0.99270 pu against the load flow's 0.99270; the frequency 4e-5 Hz off; Id 0.18332 pu,
     * the load flow's, Iq 1.4e-4.
     */
    @Test
    @Tag("emtp")
    void theBatteryHoldsItsBusWhereTheLoadFlowPutsIt() {
        double runEnd = 1.5;
        Network network = bench();
        Bus b2 = network.getBusBreakerView().getBus("B2");
        double loadFlow = b2.getV() / b2.getVoltageLevel().getNominalV();
        double crest = b2.getVoltageLevel().getNominalV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
        double id0 = -network.getBattery("BAT").getTerminal().getP() / 100.0 / loadFlow;
        double iq0 = -network.getBattery("BAT").getTerminal().getQ() / 100.0 / loadFlow;
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "battery-held"), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY), List.of()), EmtpSettings.defaults()).output();
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
        double id = output.mean("control:BAT_ID", runEnd - 0.1, runEnd);
        double iq = output.mean("control:BAT_IQ", runEnd - 0.1, runEnd);
        System.out.printf(Locale.ROOT, "frequency deviation %.2e Hz; Id %.5f (load flow %.5f), Iq %.5f (%.5f)%n", deviation, id, id0, iq, iq0);
        softly.assertThat(Math.abs(deviation)).as("frequency deviation, Hz").isLessThan(2.0e-3);
        softly.assertThat(id).as("the battery's active current, pu").isCloseTo(id0, within(1.0e-3));
        softly.assertThat(iq).as("the battery's reactive current, pu").isCloseTo(iq0, within(1.0e-3));
        softly.assertAll();
    }

    /**
     * The battery on the machine's bench through a three-phase fault on B2, through 5 ohm for 0.1 s - held to what its model gives: its
     * voltage collapsing, its reactive current on its limit Iqh1, 1 pu, through the fault, its active current on what Imax leaves it,
     * {@code sqrt(1.2^2 - 1)}, the power over the floored voltage asking more. Once cleared, its power back on its reference within 1e-3
     * pu from 0.5 s on; its reactive current coming down as its voltage regulator's integral, wound up through the fault - REEC_C's
     * integral is not held at the limit in upstream's model, nor here -, unwinds: slowly, at Kvi times a voltage the machine's own
     * regulator holds near its reference - swinging with that regulator at first, falling over each of the run's last two seconds.
     * Against PSCAD on the infinite bus's bench ({@link #aFaultAtTheBatterysBusRunsInEmtpAsInPscadProfessional}): on this one upstream's
     * PSCAD backend refuses the battery's fault from rest (its coupling reactance's offset), and from a snapshot its template finds the
     * machine 0.34 rad off the load flow at 4 s. Kept in {@code target/emtp/battery-fault}.
     *
     * <p>Run 2026-11-15: through the fault its voltage 0.037 pu, Iq 1.0000 and Id 0.6633 pu; Iq 0.744, 0.755, 0.536 and 0.384 pu at 1, 2,
     * 3 and 4 s, its power on its reference throughout.
     */
    @Test
    @Tag("emtp")
    void theBatterysCurrentsGoToTheirLimitsThroughAFaultAndBack() {
        double fault = 0.3;
        double runEnd = 4.0;
        Network network = bench();
        double p0 = -network.getBattery("BAT").getTerminal().getP() / 100.0;
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "battery-fault"), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY), List.of(EmtpVscFaultAgainstPscadTest.fault("ABC", fault))), EmtpSettings.defaults()).output();
        double[] iq = new double[4];
        for (int k = 0; k < 4; k++) {
            iq[k] = output.mean("control:BAT_IQ", k + 0.9, k + 1.0);
        }
        System.out.printf(Locale.ROOT, "through the fault: V %.4f, Iq %.4f, Id %.4f pu; Iq %.4f, %.4f, %.4f, %.4f at 1, 2, 3, 4 s%n",
                output.mean("control:BAT_VT", fault + 0.05, fault + 0.1), output.mean("control:BAT_IQ", fault + 0.05, fault + 0.1),
                output.mean("control:BAT_ID", fault + 0.05, fault + 0.1), iq[0], iq[1], iq[2], iq[3]);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:BAT_VT", fault + 0.05, fault + 0.1)).as("the battery's voltage through the fault, pu").isLessThan(0.3);
        softly.assertThat(output.mean("control:BAT_IQ", fault + 0.05, fault + 0.1)).as("its reactive current through the fault, pu: Iqh1")
                .isCloseTo(1.0, within(1.0e-3));
        softly.assertThat(output.mean("control:BAT_ID", fault + 0.05, fault + 0.1)).as("its active current through the fault, pu: what Imax leaves")
                .isCloseTo(Math.sqrt(1.2 * 1.2 - 1.0), within(1.0e-3));
        for (double t = 0.5; t < runEnd - 1.0e-9; t += 0.5) {
            double power = 0.0;
            int n = 0;
            for (double u = t; u < t + 0.1 - 1.0e-9; u += 0.01, n++) {
                power += output.mean("control:BAT_ID", u, u + 0.01) * output.mean("control:BAT_VT", u, u + 0.01);
            }
            softly.assertThat(power / n).as("its power from %.1f s, pu: its reference", t).isCloseTo(p0, within(1.0e-3));
        }
        for (int k = 2; k < 4; k++) {
            softly.assertThat(iq[k]).as("its reactive current at %d s against a second before, pu: unwinding", k + 1).isLessThan(iq[k - 1]).isPositive();
        }
        softly.assertAll();
    }

    /**
     * The battery behind upstream's ideal infinite bus through the same fault, against PSCAD Professional - upstream's route for it
     * ({@code PscadFileWriterTest#writeRestartRunsAReecCBatteryThroughAFaultAsKestrelDoes}): from its steady state, its template's
     * snapshot, the battery held on the load flow until 2 s, the fault after. EMTP starts from its steady state: the same fault, at
     * the same instant. Its voltage, reactive and active currents on 5 ms means, within 1e-3 pu of PSCAD's before the fault and in
     * the last 100 ms, within 5e-3 through the fault and the recovery. Kept in {@code
     * target/emtp/battery-fault-pscad} and {@code target/pscad/battery-fault-pscad}.
     *
     * <p>Run 2026-11-17, 72 s the two: settled within 2.4e-5 pu (VT), 1.1e-4 (Iq), 4.5e-6 (Id); through the fault 1.4e-3, 2.0e-3 and
     * 1.4e-3 - its voltage 0.125 pu, Iq on Iqh1, Id 0.6633 in both. Cleared, the bus back at 1.017 pu with Iq still on its limit in
     * both: behind the infinite bus nothing pulls the voltage down to the reference the wound-up integral chases.
     */
    @Test
    @Tag("emtp")
    @Tag("pscad")
    void aFaultAtTheBatterysBusRunsInEmtpAsInPscadProfessional() {
        double fault = 2.3;
        double runEnd = 3.0;
        double window = 5.0e-3;
        String name = "battery-fault-pscad";
        List<EmtEvent> events = List.of(EmtpVscFaultAgainstPscadTest.fault("ABC", fault));
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(infiniteBusBench(), Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY), events), EmtpSettings.defaults()).output();
        PscadOutput pscad = PscadProSimulation.runFromSteadyState(infiniteBusBench(), Path.of("target", "pscad", name), new PscadSimulationSettings(TIME_STEP,
                runEnd, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.INTERPOLATED), events, PscadProSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (String quantity : List.of("VT", "IQ", "ID")) {
            String ours = "control:BAT_" + quantity;
            String theirs = "BAT_" + quantity;
            double settled = 0.0;
            double through = 0.0;
            for (double t = 1.5; t + window <= runEnd + 1.0e-9; t += window) {
                double gap = Math.abs(emtp.mean(ours, t, t + window) - pscad.mean(theirs, t, t + window));
                if (t + window <= fault || t >= runEnd - 0.1) {
                    settled = Math.max(settled, gap);
                } else {
                    through = Math.max(through, gap);
                }
            }
            System.out.printf(Locale.ROOT, "%s: %s settled within %.2e pu, through the fault %.2e pu of PSCAD's; EMTP %.4f before, %.4f through,"
                    + " %.4f at the end; PSCAD %.4f, %.4f, %.4f%n", name, quantity, settled, through, emtp.mean(ours, fault - 0.05, fault),
                    emtp.mean(ours, fault + 0.05, fault + 0.1), emtp.mean(ours, runEnd - 0.05, runEnd), pscad.mean(theirs, fault - 0.05, fault),
                    pscad.mean(theirs, fault + 0.05, fault + 0.1), pscad.mean(theirs, runEnd - 0.05, runEnd));
            softly.assertThat(settled).as("%s settled against PSCAD's, pu", quantity).isLessThan(1.0e-3);
            softly.assertThat(through).as("%s through the fault against PSCAD's, pu", quantity).isLessThan(TRANSIENT_TOLERANCE);
        }
        softly.assertAll();
    }

    /** Upstream's two-bus bench behind its ideal infinite bus, its REEC_C battery on B2, the load flow run. */
    private static Network infiniteBusBench() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        ExampleNetworks.addReecC(network, "B2");
        ExampleNetworks.runLoadFlow(network);
        return network;
    }

    /** Upstream's two-bus bench with its REEC_C battery on B2, the load flow run. */
    private static Network bench() {
        Network network = ExampleNetworks.createTwoBusNetworkWithReecC();
        ExampleNetworks.runLoadFlow(network);
        return network;
    }
}
