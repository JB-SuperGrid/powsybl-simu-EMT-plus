/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's two-bus bench ({@code ExampleNetworks#createTwoBusNetworkWithInfiniteBusGenerator}: an infinite bus of 20 GVA, X/R 10,
 * on B1, a 4 + j40 ohm line to B2 and its 100 MW, 20 Mvar load) as an EMTP netlist, at 50 Hz and 20 us: the circuit upstream's ATP
 * deck of it has, value for value (its deck generated 2026-11-13); and - tagged {@code emtp} - run, every bus on the load flow, in
 * EMTP's own steady-state solution and in its first period as in its last.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpNetlistWriterTest {

    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(20.0e-6, 0.2, 50.0);
    private static final double FREQUENCY = 50.0;
    private static final double SQRT_TWO_THIRDS = Math.sqrt(2.0 / 3.0);

    @Test
    void writesTheCircuitAtpsDeckHas(@TempDir Path tempDir) throws IOException {
        Path netlist = EmtpNetlistWriter.write(twoBus(), tempDir, SETTINGS);

        assertThat(netlist.getFileName()).hasToString("two_bus.net");
        Map<String, double[]> devices = devices(netlist);
        // ATP's deck: L1_2 4 ohm and 127.323954473516 mH, DIVIDEDELTAT 2.0372 across it (16L/DELTAT).
        assertValues(devices.get("L1_2_a"), 4.0, 0.127323954473516);
        assertValues(devices.get("L1_2_Da"), 16.0 * 0.127323954473516 / 20.0e-6, 0.0);
        // The load: 1514.35853991255 ohm, then 964.070588962029 mH to earth, 16L/DELTAT across it.
        assertValues(devices.get("LOAD_Ra"), 1514.35853991255, 0.0);
        assertValues(devices.get("LOAD_La"), 0.0, 0.964070588962029);
        assertValues(devices.get("LOAD_Da"), 16.0 * 0.964070588962029 / 20.0e-6, 0.0);
        // The source: .796029752167991 ohm and 25.3384139811504 mH behind its EMF, 327134 V crest at .278852 degrees on phase a.
        assertValues(devices.get("GEN_Za"), 0.796029752167991, 0.0253384139811504);
        double[] emf = devices.get("GEN_a");
        assertThat(emf[0]).isCloseTo(327134.0, within(0.5));
        assertThat(emf[1]).isEqualTo(50.0);
        assertThat(emf[2]).isCloseTo(0.278852, within(1.0e-6));
        assertThat(devices.get("GEN_b")[2]).isCloseTo(0.278852 - 120.0, within(1.0e-6));
        assertThat(devices.get("GEN_c")[2]).isCloseTo(0.278852 + 120.0, within(1.0e-6));
        // Each part in one block, as EMTP reads a netlist: every _RLC, then every _Vsine.
        List<String> parts = Files.readAllLines(netlist, StandardCharsets.ISO_8859_1).stream().filter(l -> l.startsWith("_"))
                .map(l -> l.substring(0, l.indexOf(';'))).toList();
        assertThat(parts.stream().distinct()).containsExactly("_RLC", "_Vsine");
        assertThat(parts.subList(parts.indexOf("_Vsine"), parts.size())).containsOnly("_Vsine");
        // Every bus phase its 1 Gohm earth reference.
        assertThat(devices).containsKeys("REF_B1a", "REF_B1b", "REF_B1c", "REF_B2a", "REF_B2b", "REF_B2c");
        assertThat(Files.readString(netlist)).contains("Dt=2.000000000000000E-05;", "tmax=0.2000000000000000;",
                "_RLC;L1_2_a;2;2;B1a,B2a,", "_RLC;LOAD_La;1;1;ZLOADa,", "_Vsine;GEN_a;1;1;EGENa,", "_RLC;REF_B2a;1;1;B2a,");
    }

    @Test
    void refusesWhatItDoesNotWriteYet(@TempDir Path tempDir) {
        Network network = twoBus();
        network.getLine("L1_2").setB1(2.5e-4);

        assertThatThrownBy(() -> EmtpNetlistWriter.write(network, tempDir, SETTINGS)).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("line 'L1_2' has a shunt admittance");
    }

    /**
     * EMTP's steady state and its curves, in the first period as in the last, each bus on the load flow within 1e-4 pu and 0.01 degree.
     * The run is kept in {@code target/emtp/two-bus}.
     *
     * <p>Run 2026-11-13, 2 s: B1 2.4e-7 pu and 1.9e-5 degree off, B2 -8.1e-6 pu and 3.5e-4 degree, the same in EMTP's steady state, its
     * first period and its last - PSCAD Professional's own on this circuit (B2 -8.06e-6 pu and 3.3e-4 degree on the overlay's two-island
     * bench), the numerical aids' share. Its first run, the devices written element by element, came out with B1 on the source's EMF:
     * EMTP reads a netlist part by part ({@link EmtpNetlistWriter}).
     */
    @Test
    @Tag("emtp")
    void runsEveryBusOnTheLoadFlow() {
        Network network = twoBus();
        EmtpRunner.Run run = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "two-bus"), SETTINGS), EmtpSettings.defaults());

        EmtpOutput output = run.output();
        EmtpSteadyState steadyState = run.steadyState();
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * SQRT_TWO_THIRDS;
            String node = EmtpNetlistWriter.node(bus.getId(), 'a');
            EmtpSteadyState.Phasor phasor = steadyState.voltage(node).orElseThrow();
            System.out.printf(Locale.ROOT, "%s steady state: %.6e pu, %.3e degree%n", node, phasor.magnitude() / crest - 1.0,
                    phasor.angleDegrees() - bus.getAngle());
            softly.assertThat(phasor.magnitude() / crest - 1.0).as("EMTP's steady state at %s, pu", node).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(phasor.angleDegrees() - bus.getAngle()).as("EMTP's steady state at %s, degrees", node).isCloseTo(0.0, within(0.01));
            for (double start : new double[] {0.0, SETTINGS.stopTimeSeconds() - 1.0 / FREQUENCY}) {
                var fundamental = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, start, start + 1.0 / FREQUENCY);
                double angle = Math.IEEEremainder(-Math.toDegrees(fundamental.angleRadians()) - bus.getAngle(), 360.0);
                System.out.printf(Locale.ROOT, "%s from %.3f s: %.6e pu, %.3e degree%n", node, start, fundamental.magnitude() / crest - 1.0, angle);
                softly.assertThat(fundamental.magnitude() / crest - 1.0).as("|V(%s)| from %.3f s, pu", node, start).isCloseTo(0.0, within(1.0e-4));
                softly.assertThat(angle).as("V(%s)'s angle from %.3f s, degrees", node, start).isCloseTo(0.0, within(0.01));
            }
        }
        softly.assertAll();
    }

    private static Network twoBus() {
        return ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithInfiniteBusGenerator());
    }

    private static void assertValues(double[] data, double resistanceOhm, double inductanceH) {
        assertThat(data).isNotNull();
        assertThat(data[0]).isCloseTo(resistanceOhm, within(Math.abs(resistanceOhm) * 1.0e-12));
        assertThat(data[1]).isCloseTo(inductanceH, within(Math.abs(inductanceH) * 1.0e-12));
    }

    /** Each device's name and the numbers of its data line, in order - an empty field skipped. */
    private static Map<String, double[]> devices(Path netlist) throws IOException {
        List<String> lines = Files.readAllLines(netlist, StandardCharsets.ISO_8859_1);
        Map<String, double[]> devices = new LinkedHashMap<>();
        for (int n = 0; n < lines.size() - 1; n++) {
            if (lines.get(n).startsWith("_")) {
                String name = lines.get(n).split(";")[1];
                devices.put(name, java.util.Arrays.stream(lines.get(n + 1).split(",")).filter(f -> !f.isBlank() && !f.startsWith("?"))
                        .mapToDouble(Double::parseDouble).toArray());
            }
        }
        return devices;
    }
}
