/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's transformer bench ({@code ExampleNetworks#createThreeBusNetworkWithTransformer}: B1, a 4 + j40 ohm line to B2, the
 * 400/200 kV transformer T2_3 of 2 + j20 ohm on side 2 to B3 and its load), on an infinite bus of 20 GVA, X/R 10, at 50 Hz and
 * 20 us: as it is, magnetized (g 1e-6 S, b -2e-5 S at side 2) and tapped (a ratio tap changer's step at rho 1.1, r +20%, x -10%).
 * Written as ATP's decks of the three have it (generated 2026-11-13), and - tagged {@code emtp} - run live, every bus on the load
 * flow.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpTransformerTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    @Test
    void writesEachPhaseAsAtpsSingleUnit(@TempDir Path tempDir) throws IOException {
        Map<String, String[]> devices = devices(EmtpNetlistWriter.write(bench("plain"), tempDir, SETTINGS));

        // ATP: winding 1 from B2A, 4 ohm and 127.323954473516 mH at 400 kV; winding 2 to B3A, 1 ohm and 31.8309886183791 mH at 200 kV.
        assertThat(devices.get("T2_3_W1a")[0]).isEqualTo("_RLC;T2_3_W1a;2;2;B2a,TT2_3_1a,");
        assertRl(devices.get("T2_3_W1a"), 4.0, 0.127323954473516);
        assertThat(devices.get("T2_3_a")[0]).isEqualTo("_Tr0;T2_3_a;4;4;TT2_3_1a,,TT2_3_2a,,");
        assertThat(Double.parseDouble(devices.get("T2_3_a")[1].split(",")[0])).isEqualTo(0.5);
        assertThat(devices.get("T2_3_W2a")[0]).isEqualTo("_RLC;T2_3_W2a;2;2;TT2_3_2a,B3a,");
        assertRl(devices.get("T2_3_W2a"), 1.0, 0.0318309886183791);
        assertThat(devices).doesNotContainKeys("T2_3_RMa", "T2_3_LMa");
    }

    @Test
    void writesTheMagnetizingBranchAndTheTapAtpsDecksHave(@TempDir Path tempDir) throws IOException {
        Map<String, String[]> magnetized = devices(EmtpNetlistWriter.write(bench("magnetized"), tempDir.resolve("magnetized"), SETTINGS));
        // ATP: 4 Mohm core loss from T1A to earth; 1.63299 A at 1039.6 Wb-turn, 636.6 H, at winding 1's rated phase voltage.
        assertThat(magnetized.get("T2_3_RMa")[0]).isEqualTo("_RLC;T2_3_RMa;1;1;TT2_3_1a,");
        assertRl(magnetized.get("T2_3_RMa"), 4.0e6, 0.0);
        assertRl(magnetized.get("T2_3_LMa"), 0.0, 1039.6 / 1.63299, 1.0e-5);

        Map<String, String[]> tapped = devices(EmtpNetlistWriter.write(bench("tapped"), tempDir.resolve("tapped"), SETTINGS));
        // ATP: winding 1 3.96694214876033 ohm and 94.7037677902187 mH at 400 kV; winding 2 1.2 ohm and 28.6478897565412 mH at 220 kV.
        assertRl(tapped.get("T2_3_W1a"), 3.96694214876033, 0.0947037677902187);
        assertRl(tapped.get("T2_3_W2a"), 1.2, 0.0286478897565412);
        assertThat(Double.parseDouble(tapped.get("T2_3_a")[1].split(",")[0])).isCloseTo(220.0 / 400.0, within(1.0e-15));
    }

    /**
     * Each bench run live: every bus on the load flow, in EMTP's steady state and its last period, within 1e-4 pu and 0.01 degree. But
     * magnetized: the load flow's admittance is a pi, ATP's and EMTP's in the T, so every bus within {@code |y| |z| / 2} of the load flow
     * - the magnetizing current across half the leakage - and the farthest beyond 80% of it, the T's own drop: what the overlay holds
     * PSCAD Professional to on upstream's phase-shifter benches, ATP, Xyce and Kestrel at 87 to 92% there.
     *
     * <p>Run 2026-11-13: plain and tapped, every bus within 8e-6 pu and 6e-4 degree; magnetized, B3 1.99e-4 pu off, 99% of its 2.01e-4
     * bound, B1 and B2 within 2.4e-6 pu.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plain", "magnetized", "tapped"})
    @Tag("emtp")
    void everyBusOnTheLoadFlow(String variant) {
        Network network = bench(variant);
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "transformer-" + variant), SETTINGS),
                EmtpSettings.defaults());

        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        var transformer = network.getTwoWindingsTransformer("T2_3");
        double bound = Math.hypot(transformer.getG(), transformer.getB()) * Math.hypot(transformer.getR(), transformer.getX()) / 2.0;
        double tolerance = "magnetized".equals(variant) ? bound : 1.0e-4;
        double angleTolerance = "magnetized".equals(variant) ? Math.toDegrees(bound) : 0.01;
        double largest = 0.0;
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double angle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - bus.getAngle(), 360.0);
            System.out.printf(Locale.ROOT, "%s %s: steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", variant, bus.getId(),
                    phasor.magnitude() / crest - 1.0, phasor.angleDegrees() - bus.getAngle(), fundamental.magnitude() / crest - 1.0, angle);
            largest = Math.max(largest, Math.abs(fundamental.magnitude() / crest - 1.0));
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("%s, %s's steady state, pu", variant, bus.getId()).isCloseTo(0.0, within(tolerance));
            softly.assertThat(phasor.angleDegrees() - bus.getAngle()).as("%s, %s's steady state, degrees", variant, bus.getId())
                    .isCloseTo(0.0, within(angleTolerance));
            softly.assertThat(fundamental.magnitude() / crest - 1.0).as("%s, %s's last period, pu", variant, bus.getId()).isCloseTo(0.0, within(tolerance));
            softly.assertThat(angle).as("%s, %s's last period, degrees", variant, bus.getId()).isCloseTo(0.0, within(angleTolerance));
        }
        if ("magnetized".equals(variant)) {
            System.out.printf(Locale.ROOT, "magnetized: the farthest bus %.3e pu off, %.0f%% of its %.3e bound%n", largest, 100.0 * largest / bound, bound);
            softly.assertThat(largest / bound).as("the farthest bus off the load flow, in its bound: the T's drop").isGreaterThan(0.8);
        }
        softly.assertAll();
    }

    /** The bench, its load flow solved: {@code plain}, {@code magnetized} or {@code tapped}. */
    private static Network bench(String variant) {
        Network network = withInfiniteBus(ExampleNetworks.createThreeBusNetworkWithTransformer());
        if ("magnetized".equals(variant)) {
            network.getTwoWindingsTransformer("T2_3").setG(1.0e-6).setB(-2.0e-5);
        } else if ("tapped".equals(variant)) {
            network.getTwoWindingsTransformer("T2_3").newRatioTapChanger().setLowTapPosition(0).setTapPosition(0).setLoadTapChangingCapabilities(false)
                    .beginStep().setRho(1.1).setR(20.0).setX(-10.0).setG(0.0).setB(0.0).endStep().add();
        }
        return ExampleNetworks.withLoadFlow(network);
    }

    private static Network withInfiniteBus(Network network) {
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(20000.0, 10.0));
        return network;
    }

    private static void assertRl(String[] device, double resistanceOhm, double inductanceH) {
        assertRl(device, resistanceOhm, inductanceH, 1.0e-12);
    }

    private static void assertRl(String[] device, double resistanceOhm, double inductanceH, double relative) {
        double[] values = Arrays.stream(device[1].split(",")).limit(2).mapToDouble(Double::parseDouble).toArray();
        assertThat(values[0]).isCloseTo(resistanceOhm, within(Math.abs(resistanceOhm) * relative));
        assertThat(values[1]).isCloseTo(inductanceH, within(Math.abs(inductanceH) * relative));
    }

    /** Each device's header and data lines, by name. */
    private static Map<String, String[]> devices(Path netlist) throws IOException {
        List<String> lines = Files.readAllLines(netlist, StandardCharsets.ISO_8859_1);
        Map<String, String[]> devices = new LinkedHashMap<>();
        for (int n = 0; n < lines.size() - 1; n++) {
            if (lines.get(n).startsWith("_")) {
                devices.put(lines.get(n).split(";")[1], new String[] {lines.get(n), lines.get(n + 1)});
            }
        }
        return devices;
    }
}
