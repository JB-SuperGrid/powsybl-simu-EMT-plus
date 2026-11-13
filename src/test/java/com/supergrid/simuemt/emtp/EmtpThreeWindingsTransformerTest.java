/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerPhaseAngleClockAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's three-winding bench ({@code ExampleNetworks#createFourBusNetworkWithThreeWindingsTransformer}: B1, a 4 + j40 ohm line to
 * B2, the 400/115/20 kV T2_3_4 on a 400 kV star bus - legs of 1 + j10, 0.5 + j5 and 0.2 + j2 ohm there - to B3 and B4 and their loads),
 * on an infinite bus of 20 GVA, X/R 10, at 50 Hz and 20 us: as it is, as upstream's magnetized and tapped variant (leg 2's g 2e-6 S, b
 * -6e-6 S, a ratio tap at rho 1.1), and wired YNyn0d11. Written as ATP's decks of the first two have it (generated 2026-11-13), and -
 * tagged {@code emtp} - run live, every bus on the load flow, displaced by its vector group.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpThreeWindingsTransformerTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    @Test
    void writesEachPhaseAsAtpsThreeWindingUnit(@TempDir Path tempDir) throws IOException {
        Map<String, String[]> devices = devices(EmtpNetlistWriter.write(bench("plain"), tempDir, SETTINGS));

        // ATP, phase A: winding 1 from B2A, 1 ohm and 31.8309886183791 mH at 400 kV; winding 2 to B3A, .041328125 ohm and
        // 1.31551507649395 mH at 115 kV; winding 3 to B4A, .0005 ohm and .01591549430919 mH at 20 kV.
        assertThat(devices.get("T2_3_4_W1a")[0]).isEqualTo("_RLC;T2_3_4_W1a;2;2;B2a,TT2_3_4_1a,");
        assertRl(devices.get("T2_3_4_W1a"), 1.0, 0.0318309886183791);
        assertThat(devices.get("T2_3_4_W2a")[0]).isEqualTo("_RLC;T2_3_4_W2a;2;2;B3a,TT2_3_4_2a,");
        assertRl(devices.get("T2_3_4_W2a"), 0.041328125, 0.00131551507649395);
        assertRl(devices.get("T2_3_4_W3a"), 0.0005, 0.00001591549430919);
        // One ideal unit of one primary and two secondaries, each to earth, each at its own rated voltage.
        String[] unit = devices.get("T2_3_4_a");
        assertThat(unit[0]).isEqualTo("_Tr0;T2_3_4_a;6;6;TT2_3_4_1a,,TT2_3_4_2a,,TT2_3_4_3a,,");
        assertThat(unit[1]).startsWith("400.0000000000000,2,1,0,0,0,0");
        assertThat(Double.parseDouble(unit[2])).isEqualTo(115.0);
        assertThat(Double.parseDouble(unit[3])).isEqualTo(20.0);
    }

    @Test
    void writesTheMagnetizingBranchAndTheTapAtpsDeckHas(@TempDir Path tempDir) throws IOException {
        Map<String, String[]> devices = devices(EmtpNetlistWriter.write(bench("magnetized and tapped"), tempDir, SETTINGS));

        // ATP: winding 2 .034155475206612 ohm and 1.08720254255698 mH at 104.545454545455 kV; 500 kohm of core loss and 1.95959 A at
        // 1039.6 Wb-turn on winding 1.
        assertRl(devices.get("T2_3_4_W2a"), 0.034155475206612, 0.00108720254255698);
        assertThat(Double.parseDouble(devices.get("T2_3_4_a")[2])).isCloseTo(104.545454545455, within(1.0e-9));
        assertRl(devices.get("T2_3_4_RMa"), 500000.0, 0.0);
        assertThat(Double.parseDouble(devices.get("T2_3_4_LMa")[1].split(",")[1])).isCloseTo(1039.6 / 1.95959, within(1.0e-5 * 530.0));
    }

    @Test
    void refusesALegWithAPhaseTapChanger(@TempDir Path tempDir) {
        Network network = ExampleNetworks.createFourBusNetworkWithPurePhaseShiftingLeg();
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(20000.0, 10.0));
        Network loaded = ExampleNetworks.withLoadFlow(network);

        assertThatThrownBy(() -> EmtpNetlistWriter.write(loaded, tempDir, SETTINGS)).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("has a leg with a phase tap changer");
    }

    /**
     * Each variant run live: every bus within 1e-4 pu and 0.01 degree of the load flow, displaced by its vector group, in EMTP's steady
     * state and its last period. The runs are kept in {@code target/emtp/three-winding-*}.
     *
     * <p>Run 2026-11-13: plain, every bus within 8.1e-6 pu and 3.9e-4 degree; magnetized and tapped, 2.3e-5 pu (B3, the tapped leg's)
     * and 7.1e-4 degree; YNyn0d11, 7.9e-6 pu and 3.9e-4 degree, B4 30 degrees ahead.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plain", "magnetized and tapped", "YNyn0d11"})
    @Tag("emtp")
    void everyBusOnTheLoadFlow(String variant) {
        Network network = bench(variant);
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "three-winding-" + variant.replace(' ', '-')),
                SETTINGS), EmtpSettings.defaults());

        VectorGroupOffsets offsets = VectorGroupOffsets.of(network);
        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        double[] last = {SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY, SETTINGS.stopTimeSeconds()};
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            double angle = bus.getAngle() + offsets.degrees(bus);
            EmtpSteadyState.Phasor phasor = steadyState.voltage(EmtpNetlistWriter.node(bus.getId(), 'a')).orElseThrow();
            var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double steadyAngle = Math.IEEEremainder(phasor.angleDegrees() - angle, 360.0);
            double lastAngle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - angle, 360.0);
            System.out.printf(Locale.ROOT, "%s %s (offset %.0f degrees): steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", variant,
                    bus.getId(), offsets.degrees(bus), phasor.magnitude() / crest - 1.0, steadyAngle, fundamental.magnitude() / crest - 1.0, lastAngle);
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("%s, %s's steady state, pu", variant, bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(steadyAngle).as("%s, %s's steady state, degrees", variant, bus.getId()).isCloseTo(0.0, within(0.01));
            softly.assertThat(fundamental.magnitude() / crest - 1.0).as("%s, %s's last period, pu", variant, bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(lastAngle).as("%s, %s's last period, degrees", variant, bus.getId()).isCloseTo(0.0, within(0.01));
        }
        softly.assertAll();
    }

    /** The bench, its load flow solved. */
    private static Network bench(String variant) {
        Network network = "magnetized and tapped".equals(variant) ? ExampleNetworks.createFourBusNetworkWithThreeWindingsTransformerMagnetizingBranchAndTap()
                : ExampleNetworks.createFourBusNetworkWithThreeWindingsTransformer();
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(20000.0, 10.0));
        if ("YNyn0d11".equals(variant)) {
            ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T2_3_4");
            ThreeWindingsTransformerFortescueAdder fortescue = transformer.newExtension(ThreeWindingsTransformerFortescueAdder.class);
            fortescue.leg1().withConnectionType(WindingConnectionType.Y_GROUNDED).withFreeFluxes(true).add();
            fortescue.leg2().withConnectionType(WindingConnectionType.Y_GROUNDED).withFreeFluxes(true).add();
            fortescue.leg3().withConnectionType(WindingConnectionType.DELTA).withFreeFluxes(true).add();
            fortescue.add();
            transformer.newExtension(ThreeWindingsTransformerPhaseAngleClockAdder.class).withPhaseAngleClockLeg2(0).withPhaseAngleClockLeg3(11).add();
        }
        return ExampleNetworks.withLoadFlow(network);
    }

    private static void assertRl(String[] device, double resistanceOhm, double inductanceH) {
        double[] values = Arrays.stream(device[1].split(",")).limit(2).mapToDouble(Double::parseDouble).toArray();
        assertThat(values[0]).isCloseTo(resistanceOhm, within(Math.abs(resistanceOhm) * 1.0e-12));
        assertThat(values[1]).isCloseTo(inductanceH, within(Math.abs(inductanceH) * 1.0e-12));
    }

    /** Each device's header and data lines, by name. */
    private static Map<String, String[]> devices(Path netlist) throws IOException {
        List<String> lines = Files.readAllLines(netlist, StandardCharsets.ISO_8859_1);
        Map<String, String[]> devices = new LinkedHashMap<>();
        for (int n = 0; n < lines.size() - 1; n++) {
            if (lines.get(n).startsWith("_")) {
                int end = n + 1;
                while (end < lines.size() && !lines.get(end).startsWith("_") && !lines.get(end).startsWith("!")) {
                    end++;
                }
                devices.put(lines.get(n).split(";")[1], lines.subList(n, end).toArray(new String[0]));
            }
        }
        return devices;
    }
}
