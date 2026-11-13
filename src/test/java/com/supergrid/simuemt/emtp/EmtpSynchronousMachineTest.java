/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.excitation.SimplifiedAvrModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.extensions.output.Crest;
import com.powsybl.simuemt.extensions.pss.Pss1AModel;
import com.powsybl.simuemt.extensions.pss.PssModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's two-bus bench ({@code two-bus.xiidm}, {@code two-bus-extensions.xml}): its machine GEN - 100 MVA on 400 kV, Xd 2.0, Xq
 * 1.9, Xd' 0.28, T'd0 6.5 s, Xq' 0.35, T'q0 1.0 s, Xd'' = Xq'' 0.20, T''d0 0.035 s, T''q0 0.05 s, Xl 0.15, Ra 0.0025, H 5.5 s, D 1.8,
 * 1000 A on the air-gap line, 2 poles - through the 4 + j40 ohm line to B2's load, its voltage regulator and stabilizer disabled,
 * which holds its field. Written as EMTP's machine of the circuit its data describe under the exact definitions, and - tagged {@code
 * emtp} - run live: on the load flow, then shorted at the line's far end against the short-circuit current those data give.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpSynchronousMachineTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(TIME_STEP, 0.2, FREQUENCY);

    /**
     * Each axis's rotor as the exact definitions give it - the d axis's field leakage 0.1507 pu and resistance 3.222e-1 pu/s, its
     * damper's 0.0780 pu and 5.933 pu/s; the q axis's 0.3891 pu and 2.992 pu/s, 0.0593 pu and 5.399 pu/s -, every resistance over
     * 100 pi rad/s, every mutual reactance the axis's magnetizing one. EMTP's machine report of this machine reads back each winding
     * of both axes, in ohms on the stator side, within 1e-6 (run 2026-11-13).
     */
    @Test
    void writesTheExactCircuitOfTheMachinesData(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(loadedMachineWithFieldHeld(), tempDir, SETTINGS), StandardCharsets.ISO_8859_1);
        int start = lines.indexOf("_SM;GENa;4;2;B1a,,");

        assertThat(start).as("the machine's phase a").isNotNegative();
        assertThat(lines.subList(start + 1, start + 3)).containsExactly("_SM;GENb;4;2;B1b,,", "_SM;GENc;4;2;B1c,,");
        assertThat(lines.get(start + 3)).startsWith("V=400.0000000000000 ").endsWith(",1kVRMSLL,");
        assertThat(lines.get(start + 4)).isEqualTo("P=0.000000000000000 -120.0000000000000 120.0000000000000,1,");
        assertThat(lines.get(start + 5)).isEqualTo("f=50.00000000000000 2 0 0 50.00000000000000,");
        assertThat(lines.get(start + 6)).isEqualTo("R=400.0000000000000 100.0000000000000 1000.000000000000 0 0 0,");
        assertValues(lines.get(start + 8), "Ra=", 0.0025, 0.15, 0.20);
        assertValues(lines.get(start + 9), "ds=", 3, 2, 2.0);
        assertValues(lines.get(start + 10), "Rf=", 0.001025515304441671, 2.000714254224947, 1.85);
        assertValues(lines.get(start + 11), "RD1=", 0.01888409366771252, 1.927976436715163, 1.85, 1.85);
        assertValues(lines.get(start + 12), "qs=", 3, 2, 1.9);
        assertValues(lines.get(start + 13), "RQ1=", 0.009524291693139402, 2.139081399466541, 1.75);
        assertValues(lines.get(start + 14), "RQ2=", 0.01718657476390697, 1.809317556890195, 1.75, 1.75);
        // H 5.5 s; DSR = D S / wm^2, 1.8 * 100 MVA / (100 pi rad/s)^2.
        assertThat(lines.get(start + 15)).isEqualTo("MS=0 1 1 0,");
        assertValues(lines.get(start + 16), "", 100, 5.5, 1.8e8 / Math.pow(100.0 * Math.PI, 2), 0, 0, 0);
        assertThat(lines.get(start + 19)).isEqualTo("PR=100 1e-06 300 0 1e-06,");
        assertThat(lines.get(start + 20)).isEqualTo("CTRL=0 0 0 0 0,");
    }

    @Test
    void refusesTheMachineWhileItsRegulatorIsEnabled(@TempDir Path tempDir) {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));

        assertThatThrownBy(() -> EmtpNetlistWriter.write(network, tempDir, SETTINGS))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("generator 'GEN'")
                .hasMessageContaining("SimplifiedAvrModel is not written yet")
                .hasMessageContaining("Pss1AModel is not written yet");
    }

    /**
     * The loaded machine run live: both buses within 1e-4 pu and 0.01 degree of the load flow, in EMTP's steady state and its last
     * period. Kept in {@code target/emtp/machine-loaded}.
     *
     * <p>Run 2026-11-13: B1, the machine's terminal, within 2.8e-6 pu and 9.7e-4 degree; B2 within 1.2e-5 pu and 6.4e-4 degree.
     */
    @Test
    @Tag("emtp")
    void theLoadedMachineHoldsTheLoadFlow() {
        Network network = loadedMachineWithFieldHeld();
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "machine-loaded"), SETTINGS), EmtpSettings.defaults());

        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double steadyAngle = Math.IEEEremainder(phasor.angleDegrees() - bus.getAngle(), 360.0);
            double lastAngle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - bus.getAngle(), 360.0);
            System.out.printf(Locale.ROOT, "%s: steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", bus.getId(),
                    phasor.magnitude() / crest - 1.0, steadyAngle, fundamental.magnitude() / crest - 1.0, lastAngle);
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("%s's steady state, pu", bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(steadyAngle).as("%s's steady state, degrees", bus.getId()).isCloseTo(0.0, within(0.01));
            softly.assertThat(fundamental.magnitude() / crest - 1.0).as("%s's last period, pu", bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(lastAngle).as("%s's last period, degrees", bus.getId()).isCloseTo(0.0, within(0.01));
        }
        softly.assertAll();
    }

    /**
     * IEEE Std 115's sudden three-phase short, as upstream's backends are held to it: the unloaded machine, its field held, shorted
     * solidly at the far end of its line at 0.5 s ({@code faults-b2-a-b-c-0.5-2.5}). The AC current's envelope is the step response of
     * {@code 1/(X(s) + Xe)}, {@code X(s)} the d-axis operational reactance under the exact definitions and {@code Xe} the line's: read
     * off the machine's bus voltage, which the shorted line carries whole, crest by crest, each two consecutive crests averaged so that
     * the voltage the decaying DC current drops across the line's resistance cancels out - within 1% over the first 0.9 s, as upstream
     * holds ATP (0.41%) and Xyce (0.28%). Kept in {@code target/emtp/machine-short}.
     *
     * <p>Run 2026-11-13: the EMF 1.00000 pu, 88 pairs of crests, the worst 0.39% off.
     */
    @Test
    @Tag("emtp")
    void theSuddenShortFollowsTheMachinesData() {
        List<EmtEvent> shortCircuit = EmtEventsXml.read(ExampleNetworks.resource("/events/faults-b2-a-b-c-0.5-2.5.xml"));
        double faultStart = ((BusFault) shortCircuit.get(0)).closeTimeSeconds();
        Network network = unloadedMachineWithFieldHeld();
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "machine-short"),
                new EmtpSimulationSettings(TIME_STEP, 1.5, FREQUENCY), shortCircuit), EmtpSettings.defaults()).output();

        Generator generator = network.getGenerator("GEN");
        SubtransientMachineModel machine = (SubtransientMachineModel) generator.getExtension(GeneratorModel.class);
        double nominalV = generator.getTerminal().getVoltageLevel().getNominalV();
        double baseOhms = nominalV * nominalV / generator.getRatedS();
        double basePeakAmps = generator.getRatedS() * 1.0e6 / (Math.sqrt(3.0) * nominalV * 1.0e3) * Math.sqrt(2.0);
        double basePeakVolts = nominalV * 1.0e3 * Math.sqrt(2.0 / 3.0);
        Line line = network.getLine("L1_2");
        String b1a = EmtpNetlistWriter.voltageScope("B1", 'A');
        double[] before = SwitchingWindows.leadingUpTo(faultStart, 0.02, TIME_STEP);
        double emf = output.fundamentalPhasor(b1a, FREQUENCY, before[0], before[1]).magnitude() / basePeakVolts;
        List<Crest> crests = output.crests(b1a, faultStart + 0.002, faultStart + 0.9);
        double worst = 0.0;
        SoftAssertions softly = new SoftAssertions();
        for (int i = 0; i + 1 < crests.size(); i++) {
            double t = 0.5 * (crests.get(i).timeSeconds() + crests.get(i + 1).timeSeconds()) - faultStart;
            double measured = 0.5 * (crests.get(i).magnitude() + crests.get(i + 1).magnitude()) / Math.hypot(line.getR(), line.getX()) / basePeakAmps;
            double expected = emf * shortCircuitEnvelope(machine, line.getX() / baseOhms, t);
            worst = Math.max(worst, Math.abs(measured / expected - 1.0));
            softly.assertThat(measured / expected - 1.0).as("AC short-circuit current %.3f s after the short, %.4f pu against the data's %.4f pu",
                    t, measured, expected).isCloseTo(0.0, within(0.01));
        }
        System.out.printf(Locale.ROOT, "EMF %.5f pu; %d crest pairs, worst %.3e%n", emf, crests.size() - 1, worst);
        softly.assertThat(crests.size()).as("crests through the short").isGreaterThan(80);
        softly.assertAll();
    }

    /**
     * The AC envelope of a sudden three-phase short behind the external reactance {@code xe}, per unit of the EMF, {@code t} seconds
     * after it: the step response of {@code G(s) = D(s) / P(s)}, {@code D(s) = (1 + sT'd0)(1 + sT''d0)} and {@code P(s) = Xd (1 + sT'd)
     * (1 + sT''d) + xe D(s)}, the short-circuit time constants the exact definitions' {@code T'd = T'd0 Xd'/Xd}, {@code T''d = T''d0
     * Xd''/Xd'}: {@code G(0)} plus, at each root {@code s} of {@code P}, the residue {@code D(s) / (s P'(s)) e^(st)}.
     */
    static double shortCircuitEnvelope(SubtransientMachineModel machine, double xe, double t) {
        double xd = machine.getDirectSynchronousReactance();
        double t0 = machine.getOpenCircuitTransientTimeConstant();
        double t00 = machine.getDirectOpenCircuitSubtransientTimeConstant();
        double tShort = t0 * machine.getDirectTransientReactance() / xd;
        double ttShort = t00 * machine.getDirectSubtransientReactance() / machine.getDirectTransientReactance();
        double p2 = xd * tShort * ttShort + xe * t0 * t00;
        double p1 = xd * (tShort + ttShort) + xe * (t0 + t00);
        double p0 = xd + xe;
        double discriminant = Math.sqrt(p1 * p1 - 4.0 * p2 * p0);
        double response = 1.0 / p0;
        for (double s : new double[] {(-p1 + discriminant) / (2.0 * p2), (-p1 - discriminant) / (2.0 * p2)}) {
            response += (1.0 + s * t0) * (1.0 + s * t00) / (s * (2.0 * p2 * s + p1)) * Math.exp(s * t);
        }
        return response;
    }

    /** The bench, its load flow solved, its regulator and stabilizer disabled. */
    private static Network loadedMachineWithFieldHeld() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        holdField(network.getGenerator("GEN"));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The bench's machine with nothing to carry: no load, no active or reactive target, its field held. */
    private static Network unloadedMachineWithFieldHeld() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        network.getLoad("LOAD").remove();
        holdField(network.getGenerator("GEN").setTargetP(0.0).setTargetQ(0.0));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** The generator's voltage regulator and stabilizer, the same disabled. */
    private static void holdField(Generator generator) {
        SimplifiedAvrModel avr = (SimplifiedAvrModel) generator.getExtension(ExcitationSystemModel.class);
        generator.addExtension(ExcitationSystemModel.class, new SimplifiedAvrModel(false, avr.getGain(), avr.getTimeConstant(),
                avr.getVoltageSetpoint(), avr.getMinOutput(), avr.getMaxOutput()));
        Pss1AModel pss = (Pss1AModel) generator.getExtension(PssModel.class);
        generator.addExtension(PssModel.class, new Pss1AModel(false, pss.getGain(), pss.getWashoutTimeConstant(), pss.getLeadTimeConstant(),
                pss.getLagTimeConstant(), pss.getLeadTimeConstant2(), pss.getLagTimeConstant2(), pss.getOutputLimit()));
    }

    /** A data line's values after its key, each to 1e-12 relative. */
    private static void assertValues(String line, String key, double... expected) {
        assertThat(line).startsWith(key);
        double[] values = Arrays.stream(line.substring(key.length()).replace(",", "").trim().split("\\s+")).mapToDouble(Double::parseDouble).toArray();
        assertThat(values).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(values[i]).as("%s value %d", key, i).isCloseTo(expected[i], within(Math.abs(expected[i]) * 1.0e-12 + 1.0e-15));
        }
    }
}
