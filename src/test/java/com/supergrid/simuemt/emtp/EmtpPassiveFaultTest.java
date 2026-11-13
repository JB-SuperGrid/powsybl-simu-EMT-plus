/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.output.Crest;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.powsybl.simuemt.testresources.SwitchingWindows;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's passive comparison bench ({@code BackendComparisonTest#passiveNetwork}): the two-bus bench, its line charged with
 * 2.5e-4 S at each end (0.8 uF, about 125 km of 400 kV line), through faults at B2 from 0.2 to 0.3 s, at 50 Hz and 20 us - the bench
 * upstream holds ATP, Xyce and Kestrel to one another on. Written as ATP's deck of it (generated 2026-11-13) has it; and - tagged
 * {@code emtp} - run live, held to what upstream says of the circuit: every bus on the load flow before; the faulted phase on the
 * ground during a solid fault; each fault type's voltages, upstream's ATP values to the tenth of a kV it gives them; and after the
 * clearing, the faulted phase's recovery and every bus back on its steady state.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpPassiveFaultTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double LINE_END_SUSCEPTANCE_SIEMENS = 2.5e-4;
    private static final EmtpSimulationSettings SETTINGS = new EmtpSimulationSettings(TIME_STEP, 0.4, FREQUENCY);
    private static final BusFault SOLID_A = new BusFault("FLTA", "B2", 'A', 0.2, 0.3);
    private static final String[] BUS_PHASES = {"B1A", "B1B", "B1C", "B2A", "B2B", "B2C"};
    /** B2A's first crest after the clearing, phase a integrated on its own at 20 us (2026-11-13). */
    private static final double INTEGRATED_FIRST_CREST_VOLTS = 578.0e3;

    @Test
    void writesTheChargingAndTheFaultAtpsDeckHas(@TempDir Path tempDir) throws IOException {
        String netlist = Files.readString(EmtpNetlistWriter.write(passiveNetwork(), tempDir, SETTINGS, List.of(SOLID_A))).replace("\r\n", "\n");

        // ATP's deck: at each end, 1.5707963267949 ohm (DELTAT/16C) and .795774715459477 uF to earth.
        assertThat(netlist).contains("_RLC;L1_2_C1a;1;1;B1a,\n1.570796326794897,0,7.95774715459", "_RLC;L1_2_C2c;1;1;B2c,\n1.570796326794897,0,7.95774715459");
        // The fault: its switch from B2A to FLT1, closing and opening mid-step ATP's card's step after (.19997 and .29997 s there), at its
        // current's zero; its 0.01 ohm closed resistance to earth.
        assertThat(netlist).contains("_Sw0;FLTA_AG;2;2;B2a,FLT1,\n" + EmtpNetlistWriter.number(0.19999) + "," + EmtpNetlistWriter.number(0.29999) + ",0.000000000000000,",
                "_RLC;FLTA_AG_R;1;1;FLT1,\n0.01000000000000000,0.000000000000000,0,0,0,");
        // Parts in blocks: _RLC, _Sw0, _Vsine.
        assertThat(netlist.indexOf("_RLC;")).isLessThan(netlist.indexOf("_Sw0;"));
        assertThat(netlist.lastIndexOf("_RLC;")).isLessThan(netlist.indexOf("_Sw0;"));
        assertThat(netlist.lastIndexOf("_Sw0;")).isLessThan(netlist.indexOf("_Vsine;"));
    }

    /**
     * Phase a of B2 to earth, solid, cleared at its current's first zero after 0.3 s: every bus phase on the load flow in the 60 ms
     * before; B2A on the ground through the fault; its recovery's first crest, against an independent trapezoidal integration of
     * phase a of this very netlist (the phases uncoupled, phase a alone is the whole circuit's): the switch opening at 0.30966 s, B2A's
     * first crest 578.0 kV 1.06 ms later at 20 us, 577.9 kV at 2 us - held to upstream's 0.5% between solvers, at the same step; every
     * bus phase back on its steady state in the last 60 ms. The run is kept in {@code target/emtp/passive-fault}.
     *
     * <p>Run 2026-11-13: every bus phase within 1.5e-8 (B1) and 9.6e-6 pu (B2) of the load flow before; B2A at 0.1 kV through the
     * fault; the switch open at 0.30966 s, B2A's first crest 577.7 kV at 0.31072 s, 0.06% under the integration's; every phase within
     * 1.5e-4 pu of the load flow at the end. Upstream's comment on its bench puts the first crest at 692 kV, "an independent fine-step
     * integration": not this circuit's, which the integration above puts at 578 kV at 20 us and at 2 us alike.
     */
    @Test
    @Tag("emtp")
    void aSolidFaultOnPhaseAGroundsItAndItRecovers() {
        Network network = passiveNetwork();
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "passive-fault"), SETTINGS, List.of(SOLID_A)),
                EmtpSettings.defaults()).output();

        double[] before = SwitchingWindows.leadingUpTo(0.2, 0.06, TIME_STEP);
        double[] during = SwitchingWindows.leadingUpTo(0.3, 0.06, TIME_STEP);
        double[] after = {SETTINGS.stopTimeSeconds() - 0.06, SETTINGS.stopTimeSeconds()};
        SoftAssertions softly = new SoftAssertions();
        for (String busPhase : BUS_PHASES) {
            Bus bus = network.getBusBreakerView().getBus(busPhase.substring(0, 2));
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            String scope = EmtpNetlistWriter.voltageScope(bus.getId(), busPhase.charAt(2));
            double beforeError = magnitude(output, scope, before) / crest - 1.0;
            double afterError = magnitude(output, scope, after) / crest - 1.0;
            System.out.printf(Locale.ROOT, "%s: before %.3e pu, during %.1f kV, after %.3e pu%n", busPhase, beforeError,
                    magnitude(output, scope, during) / 1.0e3, afterError);
            softly.assertThat(beforeError).as("|%s| before the fault against the load flow, pu", busPhase).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(afterError).as("|%s| after the clearing against the load flow, pu", busPhase).isCloseTo(0.0, within(1.0e-3));
        }
        String b2a = EmtpNetlistWriter.voltageScope("B2", 'A');
        softly.assertThat(magnitude(output, b2a, during)).as("B2A on the ground during the fault")
                .isLessThan(0.05 * magnitude(output, EmtpNetlistWriter.voltageScope("B2", 'B'), during));
        List<Crest> recovery = output.crests(b2a, 0.3, 0.326).stream().filter(c -> c.magnitude() > 1000.0).toList();
        System.out.printf(Locale.ROOT, "B2A recovery crests: %s%n", recovery);
        softly.assertThat(recovery).as("B2A's crests after the clearing").isNotEmpty();
        if (!recovery.isEmpty()) {
            softly.assertThat(recovery.get(0).magnitude() / INTEGRATED_FIRST_CREST_VOLTS - 1.0).as("B2A's first recovery crest against the integration's")
                    .isCloseTo(0.0, within(0.005));
            softly.assertThat(recovery.get(0).timeSeconds()).as("B2A's first recovery crest's instant, s").isCloseTo(0.31072, within(TIME_STEP / 2));
        }
        softly.assertAll();
    }

    /** Each fault, its faulted phases and, for each, its crest through the fault's last 60 ms in kV, from the independent integration
     * of its netlist (2026-11-13) - in the order of the phases. */
    static Stream<Arguments> faultTypes() {
        return Stream.of(
                Arguments.of("AB solid", new BusFault("F", "B2", "AB", false, 0.0, 0.2, 0.3, FaultClearing.AT_CURRENT_ZERO), "AB",
                        new double[] {163.688, 163.631}),
                Arguments.of("CA to earth, 10 ohm", new BusFault("F", "B2", "AC", true, 10.0, 0.2, 0.3, FaultClearing.AT_CURRENT_ZERO), "AC",
                        new double[] {64.625, 64.709}),
                Arguments.of("ABC, 10 ohm", new BusFault("F", "B2", "ABC", false, 10.0, 0.2, 0.3, FaultClearing.AT_CURRENT_ZERO), "ABC",
                        new double[] {22.283, 22.197, 22.425}),
                Arguments.of("ABC to earth, 10 ohm", new BusFault("F", "B2", "ABC", true, 10.0, 0.2, 0.3, FaultClearing.AT_CURRENT_ZERO), "ABC",
                        new double[] {64.625, 64.595, 64.709}),
                Arguments.of("B to earth, 10 ohm", new BusFault("F", "B2", 'B', 0.2, 0.3).withResistance(10.0), "B", new double[] {64.595}));
    }

    /**
     * Each kind of fault upstream's bench applies, through 10 ohm but for the solid phase-to-phase one: B2's faulted phases through the
     * fault's last 60 ms, held within 5e-4 to an independent trapezoidal integration of the same netlist at the same step (a pure-Python
     * nodal solver: sources, R-L-C branches, switches opening at their current's zero; 2026-11-13). Upstream gives its ATP run's values
     * rounded, one per kind of fault - 163.7 kV for AB, 64.6 kV for each phase to earth through 10 ohm, 22.3 kV for the triangle -,
     * which the integration reads as 163.63 to 163.69, 64.60 to 64.71 and 22.20 to 22.43 kV: each phase's own transient from the fault's
     * closing, a different point on its wave, not yet gone 40 ms later. EMTP within 0.01 kV of the integration on every phase.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("faultTypes")
    @Tag("emtp")
    void eachKindOfFaultHoldsItsPhasesWhereTheIntegrationDoes(String name, BusFault fault, String faulted, double[] crestKv) {
        Path directory = Path.of("target", "emtp", "fault-types", name.replaceAll("[^A-Za-z0-9]+", "-"));
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(passiveNetwork(), directory, SETTINGS, List.of(fault)), EmtpSettings.defaults()).output();

        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < faulted.length(); k++) {
            char phase = faulted.charAt(k);
            double kv = magnitude(output, EmtpNetlistWriter.voltageScope("B2", phase), SwitchingWindows.leadingUpTo(0.3, 0.06, TIME_STEP)) / 1.0e3;
            System.out.printf(Locale.ROOT, "%s: B2%s %.3f kV during the fault (integration %.3f)%n", name, phase, kv, crestKv[k]);
            softly.assertThat(kv / crestKv[k] - 1.0).as("B2%s during the fault against the integration, %s", phase, name).isCloseTo(0.0, within(5.0e-4));
        }
        softly.assertAll();
    }

    /** The fundamental's crest over {@code window}, as upstream's bench reads it: one projection over the whole window. */
    private static double magnitude(EmtpOutput output, String scope, double[] window) {
        return output.fundamentalPhasor(scope, FREQUENCY, window[0], window[1]).magnitude();
    }

    private static Network passiveNetwork() {
        Network network = ExampleNetworks.createTwoBusNetworkWithInfiniteBusGenerator();
        network.getLine("L1_2").setB1(LINE_END_SUSCEPTANCE_SIEMENS).setB2(LINE_END_SUSCEPTANCE_SIEMENS);
        return ExampleNetworks.withLoadFlow(network);
    }
}
