/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerPhaseAngleClockAdder;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's transformer bench ({@link EmtpTransformerTest}) with winding connections - powsybl's {@code TwoWindingsTransformerFortescue}
 * and its clock number, free fluxes (a bank of single-phase units): YNd11 written as ATP's deck of it has it (generated 2026-11-13);
 * and - tagged {@code emtp} - YNd11, Dyn11, Yyn0 (side 1's star point isolated), YNyn0 (side 2's earthed through 5 + j10 ohm) and Dd0
 * run live, every bus on the load flow, its angle displaced by its vector group's ({@link VectorGroupOffsets}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpWindingConnectionTest {

    private static final double FREQUENCY = 50.0;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY);

    @Test
    void writesAYnd11BankAsAtpsDeckHasIt(@TempDir Path tempDir) throws IOException {
        Map<String, String[]> devices = devices(EmtpNetlistWriter.write(bench("YNd11"), tempDir, SETTINGS));

        // ATP, unit A: winding 1 from B2A to earth, 4 ohm and 127.323954473516 mH at 230.94010767585 kV; winding 2 from B3A to B3C,
        // 3 ohm and 95.4929658551372 mH at 200 kV (three times its star-equivalent share across a delta).
        assertThat(devices.get("T2_3_W1a")[0]).isEqualTo("_RLC;T2_3_W1a;2;2;B2a,TT2_3_1a,");
        assertRl(devices.get("T2_3_W1a"), 4.0, 0.127323954473516);
        assertThat(devices.get("T2_3_a")[0]).isEqualTo("_Tr0;T2_3_a;4;4;TT2_3_1a,,TT2_3_2a,B3c,");
        assertThat(Double.parseDouble(devices.get("T2_3_a")[1].split(",")[0])).isCloseTo(200.0 / 230.94010767585, within(1.0e-12));
        assertThat(devices.get("T2_3_W2a")[0]).isEqualTo("_RLC;T2_3_W2a;2;2;TT2_3_2a,B3a,");
        assertRl(devices.get("T2_3_W2a"), 3.0, 0.0954929658551372);
        // Units B and C: winding 2 from B3B to B3A, from B3C to B3B.
        assertThat(devices.get("T2_3_b")[0]).endsWith(",TT2_3_2b,B3a,");
        assertThat(devices.get("T2_3_W2b")[0]).endsWith(",B3b,");
        assertThat(devices.get("T2_3_c")[0]).endsWith(",TT2_3_2c,B3b,");
        // 0.003 uF from each phase of the delta side to earth.
        assertThat(devices.get("T2_3_2_CDa")[0]).isEqualTo("_RLC;T2_3_2_CDa;1;1;B3a,");
        assertThat(Double.parseDouble(devices.get("T2_3_2_CDa")[1].split(",")[2])).isEqualTo(3.0e-9);
        // The source on side 1: its EMF at ATP's 1.6958 degrees, no vector group's displacement.
        assertThat(Double.parseDouble(devices.get("GEN_a")[1].split(",")[2])).isCloseTo(1.6958, within(1.0e-4));
    }

    @Test
    void refusesAZeroSequenceFluxPath(@TempDir Path tempDir) {
        Network network = withInfiniteBus(ExampleNetworks.createThreeBusNetworkWithTransformer());
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2_3");
        transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withConnectionType1(WindingConnectionType.Y_GROUNDED)
                .withConnectionType2(WindingConnectionType.Y_GROUNDED).withFreeFluxes(false).withRz(2.0).withXz(20.0).add();
        Network loaded = ExampleNetworks.withLoadFlow(network);

        assertThatThrownBy(() -> EmtpNetlistWriter.write(loaded, tempDir, SETTINGS)).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("has a zero-sequence flux path");
    }

    static Stream<Arguments> vectorGroups() {
        return Stream.of(Arguments.of("YNd11"), Arguments.of("Dyn11"), Arguments.of("Yyn0"), Arguments.of("YNyn0 through 5 + j10 ohm"),
                Arguments.of("Dd0"));
    }

    /**
     * Each vector group run live: every bus within 1e-4 pu and 0.01 degree of the load flow, in EMTP's steady state and its last
     * period, the angle displaced by the bus's vector group. The runs are kept in {@code target/emtp/vector-group-*}.
     *
     * <p>Run 2026-11-13: YNd11 within 2.3e-5 pu and 2.7e-4 degree (B3, 30 degrees ahead), Dyn11 3.9e-5 pu and 2.4e-4 degree, Yyn0 and
     * YNyn0 through 5 + j10 ohm 7.9e-6 pu and 5.8e-4 degree - the plain bench's own -, Dd0 6.8e-5 pu and 1.0e-4 degree: a delta side a
     * little further off, its phases' 0.003 uF to earth drawing what the load flow does not know of.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("vectorGroups")
    @Tag("emtp")
    void everyBusOnTheLoadFlowDisplacedByItsVectorGroup(String group) {
        Network network = bench(group);
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "vector-group-" + group.replaceAll("[^A-Za-z0-9]+", "-")),
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
            System.out.printf(Locale.ROOT, "%s %s (offset %.0f degrees): steady state %.3e pu, %.3e degree; last period %.3e pu, %.3e degree%n", group,
                    bus.getId(), offsets.degrees(bus), phasor.magnitude() / crest - 1.0, steadyAngle, fundamental.magnitude() / crest - 1.0, lastAngle);
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("%s, %s's steady state, pu", group, bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(steadyAngle).as("%s, %s's steady state, degrees", group, bus.getId()).isCloseTo(0.0, within(0.01));
            softly.assertThat(fundamental.magnitude() / crest - 1.0).as("%s, %s's last period, pu", group, bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(lastAngle).as("%s, %s's last period, degrees", group, bus.getId()).isCloseTo(0.0, within(0.01));
        }
        softly.assertAll();
    }

    /** The bench, its transformer wired as {@code group} says, its load flow solved. */
    private static Network bench(String group) {
        Network network = withInfiniteBus(ExampleNetworks.createThreeBusNetworkWithTransformer());
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2_3");
        TwoWindingsTransformerFortescueAdder adder = transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withFreeFluxes(true);
        int clock = switch (group) {
            case "YNd11" -> {
                adder.withConnectionType1(WindingConnectionType.Y_GROUNDED).withConnectionType2(WindingConnectionType.DELTA);
                yield 11;
            }
            case "Dyn11" -> {
                adder.withConnectionType1(WindingConnectionType.DELTA).withConnectionType2(WindingConnectionType.Y_GROUNDED);
                yield 11;
            }
            case "Yyn0" -> {
                adder.withConnectionType1(WindingConnectionType.Y).withConnectionType2(WindingConnectionType.Y_GROUNDED);
                yield 0;
            }
            case "YNyn0 through 5 + j10 ohm" -> {
                adder.withConnectionType1(WindingConnectionType.Y_GROUNDED).withConnectionType2(WindingConnectionType.Y_GROUNDED)
                        .withGroundingR2(5.0).withGroundingX2(10.0);
                yield 0;
            }
            case "Dd0" -> {
                adder.withConnectionType1(WindingConnectionType.DELTA).withConnectionType2(WindingConnectionType.DELTA);
                yield 0;
            }
            default -> throw new IllegalArgumentException(group);
        };
        adder.add();
        transformer.newExtension(TwoWindingsTransformerPhaseAngleClockAdder.class).withPhaseAngleClock(clock).add();
        return ExampleNetworks.withLoadFlow(network);
    }

    private static Network withInfiniteBus(Network network) {
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(20000.0, 10.0));
        return network;
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
                devices.put(lines.get(n).split(";")[1], new String[] {lines.get(n), lines.get(n + 1)});
            }
        }
        return devices;
    }
}
