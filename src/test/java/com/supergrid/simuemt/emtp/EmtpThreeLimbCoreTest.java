/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.output.Phasor;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
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

/**
 * A three-limb core's zero-sequence flux path ({@code ZeroSequenceFluxPath}): what the flux meets once it leaves the iron and closes
 * through the oil and the tank, written as ATP writes it - a closed delta winding added to each unit, three times {@code rz}/{@code xz}
 * across it. Upstream's fixtures, its {@code AtpSolverIntegrationTest}'s: its transformer bench wired YNy0 ({@code
 * three-bus-extensions-yny[-three-limb].xml}, an ideal source, side 2's load taken away) and its three-winding bench YNyn0yn0 ({@code
 * four-bus-extensions-ynyn0yn0[-three-limb].xml}, legs 2's and 3's loads taken away), each as a bank of single-phase units and on a
 * three-limb core of 2 + j20 ohm, through a solid phase-a earth fault on B2 from 0.3 to 0.6 s. Nothing but the tank path can carry
 * zero-sequence current through either transformer: as a bank the healthy phase b does not move; on the three-limb core it moves by
 * the closed form upstream holds ATP to - {@code Vb = Vb_pre - Va_pre (Z0 - Z1) / (2 Z1 + Z0)}, {@code Z1} the line, {@code Z0} the
 * line in parallel with the transformer's side-1 leakage (half the two-winding one, leg 1's) in series with the tank path, referred
 * to side 1 - within ATP's tolerances, 2e-3 and 1e-4 of the phase voltage.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpThreeLimbCoreTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(TIME_STEP, 0.62, FREQUENCY);
    private static final List<EmtEvent> EARTH_FAULT = EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-a-0.3-0.6.xml"));

    @Test
    void writesTheTankPathAsAClosedDeltaWindingOnEachUnit(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(twoWinding("yny-three-limb"), tempDir, SETTINGS), StandardCharsets.ISO_8859_1);

        // Unit a: winding 1 from B2a to earth, winding 2 from B3a to side 2's isolated star point, and the tank winding from corner 0 to
        // corner 1 - at 230.9, 115.5 and 200 kV, side 2's line voltage, as ATP rates it.
        int unit = lines.indexOf("_Tr0;T2_3_a;6;6;TT2_3_1a,,TT2_3_2a,NT2_3_2,TT2_3_Za,TANKT2_3_1,");
        assertThat(unit).isNotNegative();
        assertThat(Double.parseDouble(lines.get(unit + 1).split(",")[0])).isCloseTo(400.0 / Math.sqrt(3.0), org.assertj.core.api.Assertions.within(1.0e-9));
        assertThat(lines.get(unit + 1)).endsWith(",2,1,0,0,0,0");
        assertThat(Double.parseDouble(lines.get(unit + 3))).isEqualTo(200.0);
        // Three times rz/xz across it, from corner 0; 0.003 uF from each corner to earth.
        int tank = lines.indexOf("_RLC;T2_3_WZa;2;2;TANKT2_3_0,TT2_3_Za,");
        assertThat(tank).isNotNegative();
        String[] values = lines.get(tank + 1).split(",");
        assertThat(Double.parseDouble(values[0])).isEqualTo(6.0);
        assertThat(Double.parseDouble(values[1])).isCloseTo(60.0 / (2.0 * Math.PI * FREQUENCY), org.assertj.core.api.Assertions.within(1.0e-12));
        assertThat(lines).contains("_RLC;T2_3_CZ0;1;1;TANKT2_3_0,", "_RLC;T2_3_CZ2;1;1;TANKT2_3_2,");
    }

    /**
     * The two-winding pair. Run 2026-11-13: as a bank, phase b at 1.00000 of its pre-fault voltage, 1e-11 off; on the three-limb core
     * 0.95779, 7.2e-6 off the closed form - ATP's 0.958 upstream. The three-winding pair: 1.00000, 1e-14 off; 0.90556, 1.9e-5 off - ATP's
     * 0.906, 9e-6 off.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"yny", "yny-three-limb"})
    @Tag("emtp")
    void aTwoWindingThreeLimbCoreCarriesZeroSequenceCurrentWhereABankCarriesNone(String core) {
        Network network = twoWinding(core);
        double[] k = core.endsWith("three-limb") ? earthFaultFactor(network.getLine("L1_2"), twoWindingTankPath(network)) : new double[] {0.0, 0.0};
        assertHealthyPhaseFollows(network, "three-limb-" + core, k, 2.0e-3);
    }

    /** The three-winding pair. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ynyn0yn0", "ynyn0yn0-three-limb"})
    @Tag("emtp")
    void aThreeWindingThreeLimbCoreCarriesZeroSequenceCurrentWhereABankCarriesNone(String core) {
        Network network = ExampleNetworks.createFourBusNetworkWithThreeWindingsTransformer();
        EmtExtensionsXmlReader.graftExtensionsFromXml(network, ExampleNetworks.resource("/four-bus-extensions-" + core + ".xml"));
        network.getLoad("LOAD3").remove();
        network.getLoad("LOAD4").remove();
        ExampleNetworks.withLoadFlow(network);
        double[] k = core.endsWith("three-limb") ? earthFaultFactor(network.getLine("L1_2"), threeWindingTankPath(network)) : new double[] {0.0, 0.0};
        assertHealthyPhaseFollows(network, "three-limb-" + core, k, 1.0e-4);
    }

    /** B2b through the fault's last 60 ms against {@code Vb_pre - k Va_pre}, within {@code tolerance} of its pre-fault voltage. */
    private static void assertHealthyPhaseFollows(Network network, String directory, double[] k, double tolerance) {
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", directory), SETTINGS, EARTH_FAULT),
                EmtpSettings.defaults()).output();
        BusFault fault = (BusFault) EARTH_FAULT.get(0);
        double[] before = SwitchingWindows.leadingUpTo(fault.closeTimeSeconds(), 0.06, TIME_STEP);
        double[] aBefore = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'A'), FREQUENCY, before[0], before[1]));
        double[] bBefore = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'B'), FREQUENCY, before[0], before[1]));
        double[] bDuring = complex(output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B2", 'B'), FREQUENCY, fault.openTimeSeconds() - 0.08,
                fault.openTimeSeconds() - 0.02));
        double[] expected = {bBefore[0] - (k[0] * aBefore[0] - k[1] * aBefore[1]), bBefore[1] - (k[0] * aBefore[1] + k[1] * aBefore[0])};
        double preFault = Math.hypot(bBefore[0], bBefore[1]);
        double offset = Math.hypot(bDuring[0] - expected[0], bDuring[1] - expected[1]) / preFault;
        System.out.printf(Locale.ROOT, "%s: |Vb| during the fault %.5f of its pre-fault value, %.2e of it off the closed form%n", directory,
                Math.hypot(bDuring[0], bDuring[1]) / preFault, offset);
        assertThat(offset).as("healthy phase b during the fault, %s: |measured - predicted| / |Vb_pre|", directory).isLessThan(tolerance);
    }

    /** The transformer bench wired as {@code three-bus-extensions-<core>.xml} says, side 2's load taken away. */
    private static Network twoWinding(String core) {
        Network network = ExampleNetworks.createThreeBusNetworkWithTransformer();
        EmtExtensionsXmlReader.graftExtensionsFromXml(network, ExampleNetworks.resource("/three-bus-extensions-" + core + ".xml"));
        network.getLoad("LOAD").remove();
        return ExampleNetworks.withLoadFlow(network);
    }

    /** Side 1's half of the leakage in series with the tank path, referred to side 1. */
    private static double[] twoWindingTankPath(Network network) {
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2_3");
        TwoWindingsTransformerFortescue fortescue = transformer.getExtension(TwoWindingsTransformerFortescue.class);
        double scale = Math.pow(transformer.getRatedU1() / transformer.getRatedU2(), 2);
        return new double[] {(0.5 * transformer.getR() + fortescue.getRz()) * scale, (0.5 * transformer.getX() + fortescue.getXz()) * scale};
    }

    /** Leg 1's impedance in series with the tank path, both on the star bus's base, referred to leg 1's. */
    private static double[] threeWindingTankPath(Network network) {
        ThreeWindingsTransformer transformer = network.getThreeWindingsTransformer("T2_3_4");
        ThreeWindingsTransformerFortescue fortescue = transformer.getExtension(ThreeWindingsTransformerFortescue.class);
        double scale = Math.pow(transformer.getLeg1().getRatedU() / transformer.getRatedU0(), 2);
        return new double[] {(transformer.getLeg1().getR() + fortescue.getLeg1().getRz()) * scale,
            (transformer.getLeg1().getX() + fortescue.getLeg1().getXz()) * scale};
    }

    /** {@code (Z0 - Z1) / (2 Z1 + Z0)}, {@code Z1} the line, {@code Z0} the line in parallel with {@code zt}, as {@code {re, im}}. */
    private static double[] earthFaultFactor(Line line, double[] zt) {
        double[] z1 = {line.getR(), line.getX()};
        double[] z0 = divide(multiply(z1, zt), new double[] {z1[0] + zt[0], z1[1] + zt[1]});
        return divide(new double[] {z0[0] - z1[0], z0[1] - z1[1]}, new double[] {2 * z1[0] + z0[0], 2 * z1[1] + z0[1]});
    }

    private static double[] multiply(double[] a, double[] b) {
        return new double[] {a[0] * b[0] - a[1] * b[1], a[0] * b[1] + a[1] * b[0]};
    }

    private static double[] divide(double[] a, double[] b) {
        double d = b[0] * b[0] + b[1] * b[1];
        return new double[] {(a[0] * b[0] + a[1] * b[1]) / d, (a[1] * b[0] - a[0] * b[1]) / d};
    }

    /** {@code phasor} as the complex number {@code m e^(-j theta)}: the phasor of {@code m cos(wt - theta)}, as the closed form wants it. */
    private static double[] complex(Phasor phasor) {
        return new double[] {phasor.magnitude() * Math.cos(phasor.angleRadians()), -phasor.magnitude() * Math.sin(phasor.angleRadians())};
    }
}
