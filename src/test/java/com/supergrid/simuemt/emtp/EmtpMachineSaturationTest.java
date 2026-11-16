/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.machine.MachineSaturation;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.testresources.ExampleNetworks;
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
 * A saturated machine in EMTP: its open-circuit characteristic ({@link MachineSaturation}) as the {@code _SM}'s total saturation, on the
 * resultant air-gap flux as ATP and Xyce saturate. Held to the open-circuit test upstream's backends are held to, and to Xyce's run of
 * the two-bus bench through a fault that upstream's PSCAD tests store ({@code PscadFileWriterTest}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpMachineSaturationTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    /** The {@code S=} line asking for total saturation, the curve's points through 1.0 and 1.2 per unit on {@link MachineSaturation}'s. */
    @Test
    void writesTheOpenCircuitCharacteristic(@TempDir Path tempDir) throws IOException {
        Network network = ExampleNetworks.createTwoBusNetworkWithSaturatedMachine();
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(network, tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)),
                StandardCharsets.ISO_8859_1);
        assertThat(lines).contains("S=0 1 1 1 1 0 16 0,");
        MachineSaturation saturation = new MachineSaturation(ExampleNetworks.SATURATION_FACTOR_10, ExampleNetworks.SATURATION_FACTOR_12);
        int at = lines.indexOf("S=0 1 1 1 1 0 16 0,");
        double agline = Double.parseDouble(lines.get(at - 1).split(" ")[2]);
        assertThat(lines.get(at + 10)).isEqualTo(EmtpNetlistWriter.number(saturation.fieldCurrentAt(1.0, agline)) + " " + EmtpNetlistWriter.number(1.0));
        assertThat(lines.get(at + 17)).isEqualTo(";");
    }

    /**
     * The open-circuit test: the machine alone, its regulator holding 1.0 then 1.2 per unit, run 3 s - the field it settles at, per unit - the
     * air-gap line's at 1.0 -, is {@code v (1 + S(v))}, 1.1 and 1.62, within the 0.002 ATP, Xyce, Kestrel and PSCAD are held to.
     */
    @Test
    @Tag("emtp")
    void theOpenCircuitTestDrawsTheCharacteristic() {
        MachineSaturation saturation = new MachineSaturation(ExampleNetworks.SATURATION_FACTOR_10, ExampleNetworks.SATURATION_FACTOR_12);
        SoftAssertions softly = new SoftAssertions();
        for (double v : new double[] {1.0, 1.2}) {
            double ratio = field(ExampleNetworks.createOpenCircuitMachineBench(true, v), "oc-" + v);
            System.out.printf(Locale.ROOT, "field at %.1f pu: %.5f pu (sheet %.5f)%n", v, ratio,
                    saturation.fieldCurrentAt(v, 1.0));
            softly.assertThat(ratio).as("field at %s pu", v).isCloseTo(saturation.fieldCurrentAt(v, 1.0), within(0.002));
        }
        softly.assertAll();
    }

    /**
     * The two-bus bench through a phase-a fault on B2 from 2.5 to 2.6 s, run 4 s: B1's phase a on the load flow within 1e-4 before the
     * fault, on Xyce's within 7e-3 in the fault (6.1e-3 off, the exponential curve read piecewise-linearly and Xyce's saturation on the
     * subtransient flux - a curve twice as fine does not close it), 1.2% 50 ms after, 1.5e-3 at 0.4 s and 5e-4 at 1.2 s, as PSCAD's is held.
     */
    @Test
    @Tag("emtp")
    void aSaturatedMachineRunsAsXycesDoes() {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithSaturatedMachine());
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "saturation-fault"), new EmtpSimulationSettings(TIME_STEP,
                4.0, FREQUENCY), EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-a-2.5-2.6-at-order.xml"))), EmtpSettings.defaults()).output();
        double[][] xyce = {{2.4, network.getBusBreakerView().getBus("B1").getV() * Math.sqrt(2.0 / 3.0), 1.0e-4}, {2.52, 34.2499, 0.007},
            {2.65, 307.3670, 0.012}, {3.0, 327.3870, 1.5e-3}, {3.8, 326.1114, 5.0e-4}};
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : xyce) {
            double crest = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope("B1", 'A'), FREQUENCY, window[0], window[0] + 0.02).magnitude() / 1.0e3;
            System.out.printf(Locale.ROOT, "|B1A| at %.2f s: %.4f kV (Xyce %.4f, %.1e off)%n", window[0], crest, window[1], crest / window[1] - 1.0);
            softly.assertThat(crest / window[1]).as("|B1A| at %s s", window[0]).isCloseTo(1.0, within(window[2]));
        }
        softly.assertAll();
    }

    /** The field voltage's mean over the bench's last 20 ms, run 3 s. */
    private static double field(Network bench, String name) {
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(ExampleNetworks.withLoadFlow(bench), Path.of("target", "emtp", "saturation-" + name),
                new EmtpSimulationSettings(TIME_STEP, 3.0, FREQUENCY)), EmtpSettings.defaults()).output();
        return output.mean("control:GEN_EFV", 2.9, 2.92);
    }
}
