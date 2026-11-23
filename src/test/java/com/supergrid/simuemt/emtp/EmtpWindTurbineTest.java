/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusEmf;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.extensions.wind.Wt4bModel;
import com.powsybl.simuemt.pscad.PscadSwitching;
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
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link EmtpWindTurbines}: a WT4B wind park written and run live, held to upstream's ATP checks ({@code AtpSolverIntegrationTest}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpWindTurbineTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    /** The park on upstream's bench: its meter, its coupling, its PLL, REGC_A's currents recorded; blocked, its breaker at its current's zero
     * and its bus's RC to earth, upstream's, from the start. */
    @Test
    void writesAWt4bWindPark(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(bench(), tempDir, new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY),
                List.of(new ConverterBlocking("WT1", 0.05))), StandardCharsets.ISO_8859_1);
        assertThat(lines).contains("_Sw0;WT1_BRKa;2;2;B3a,WT1_Ba,", "_cV;WT1_METa;6;6;WT1_Xa,WT1_Ba,WT1_ZERO,WT1_MVa,WT1_IMa,WT1_MPa,",
                "_RLC;WT1_LCa;2;2;WT1_Ka,WT1_Xa,");
        int snubber = lines.indexOf("_RLC;WT1_SNBa;1;1;B3a,");
        assertThat(snubber).as("phase a's RC to earth").isNotNegative();
        String[] data = lines.get(snubber + 1).split(",");
        assertThat(Double.parseDouble(data[0])).isCloseTo(NumericalAids.WIND_TURBINE_BUS_SNUBBER_OHM, within(1e-9));
        assertThat(Double.parseDouble(data[2])).isCloseTo(NumericalAids.WIND_TURBINE_BUS_SNUBBER_F, within(1e-18));
        assertThat(Files.readAllLines(EmtpNetlistWriter.write(bench(), tempDir.resolve("unblocked"), new EmtpSimulationSettings(TIME_STEP, 0.1, FREQUENCY)),
                StandardCharsets.ISO_8859_1)).as("unblocked, none").noneMatch(line -> line.contains("_SNB"));
    }

    /**
     * Upstream's {@code windTurbineRidesThroughAVoltageDip}: Dynawo's example's park ({@code ExampleNetworks#createWindTurbineNetwork})
     * through {@code WIND_TURBINE_DIP}, a 20 ohm three-phase fault at B2 from 0.2 to 0.35 s - held to its checks: before the dip its
     * powers and voltage on the load flow's within 1e-3 pu; through it its fault ride-through on, its reactive current {@code q0 / u0 + 2
     * (u0 - u - 0.05)} within 0.01 pu, its active current {@code min(p0 / u, sqrt(1.3^2 - iq^2))} within 0.01; after it, its reactive
     * current {@code q0 / u + 0.1} - IqFrz's hold - within 0.015; at the end its powers on the load flow's within 1e-3. Run 1 s, kept in
     * {@code target/emtp/wt4b-dip}.
     *
     * <p>Run 2026-11-15: before the dip U 1.0144, P 0.8005, Q 0.1000 pu; through it U 0.532 pu, the ride-through on, iq 0.959 and id 0.854
     * pu; cleared, the voltage 1.098 pu its first 40 ms, the ride-through on half of them, then iq 0.223 pu at 0.40-0.45 s, IqFrz's hold;
     * at the end P 0.8005 and Q 0.0999 pu.
     */
    @Test
    @Tag("emtp")
    void theWindParkRidesThroughAVoltageDip() {
        Network network = bench();
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "wt4b-dip"), new EmtpSimulationSettings(TIME_STEP, 1.0,
                FREQUENCY), List.of(ExampleNetworks.WIND_TURBINE_DIP)), EmtpSettings.defaults()).output();
        for (double[] w : new double[][] {{0.0, 0.01}, {0.15, 0.2}, {0.25, 0.35}, {0.36, 0.4}, {0.4, 0.45}, {0.45, 0.5}, {0.5, 0.6}, {0.9, 1.0}}) {
            System.out.printf(Locale.ROOT, "WT4B EMTP %.2f-%.2f: U %.4f P %.4f Q %.4f id %.4f iq %.4f frt %.3f%n", w[0], w[1], output.mean("control:WT1_U", w[0], w[1]),
                    output.mean("control:WT1_P", w[0], w[1]), output.mean("control:WT1_Q", w[0], w[1]), output.mean("control:WT1_ID", w[0], w[1]),
                    output.mean("control:WT1_IQ", w[0], w[1]), output.mean("control:WT1_FRT", w[0], w[1]));
        }
        Generator wind = network.getGenerator("WT1");
        double u0 = wind.getTerminal().getBusView().getBus().getV() / 33.0;
        double p0 = -wind.getTerminal().getP() / 100.0;
        double q0 = -wind.getTerminal().getQ() / 100.0;
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:WT1_P", 0.1, 0.2)).as("active power before the dip").isCloseTo(p0, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_Q", 0.1, 0.2)).as("reactive power before the dip").isCloseTo(q0, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_U", 0.1, 0.2)).as("terminal voltage before the dip").isCloseTo(u0, within(1.0e-3));
        double uDip = output.mean("control:WT1_U", 0.25, 0.35);
        double iqDip = output.mean("control:WT1_IQ", 0.25, 0.35);
        softly.assertThat(output.mean("control:WT1_FRT", 0.25, 0.35)).as("fault ride-through through the dip").isCloseTo(1.0, within(1.0e-3));
        softly.assertThat(iqDip).as("reactive current through the dip").isCloseTo(q0 / u0 + 2.0 * (u0 - uDip - 0.05), within(0.01));
        softly.assertThat(output.mean("control:WT1_ID", 0.30, 0.35)).as("active current through the dip")
                .isCloseTo(Math.min(p0 / uDip, Math.sqrt(1.3 * 1.3 - iqDip * iqDip)), within(0.01));
        double uAfter = output.mean("control:WT1_U", 0.43, 0.47);
        softly.assertThat(output.mean("control:WT1_IQ", 0.43, 0.47)).as("reactive current held after the dip").isCloseTo(q0 / uAfter + 0.1, within(0.015));
        softly.assertThat(output.mean("control:WT1_P", 0.9, 1.0)).as("active power at the end").isCloseTo(p0, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_Q", 0.9, 1.0)).as("reactive power at the end").isCloseTo(q0, within(1.0e-3));
        softly.assertAll();
    }

    /**
     * Upstream's {@code windTurbineBlockedLetsGoOfItsCurrent}: the park blocked at 0.3 s - held to its checks: before, its power and its
     * voltage on the load flow's within 1e-3 pu; from 10 ms on, its powers zero within 1e-6 pu; its terminal at the grid's EMF within 1e-3
     * pu 60 ms on and at the end. Run 0.8 s, kept in {@code target/emtp/wt4b-blocked}.
     *
     * <p>Run 2026-11-15: P 0.8004 pu before; nothing drawn from 0.30 s on (under 1e-6 pu); the terminal at 0.99812 pu from 0.33 s on.
     */
    @Test
    @Tag("emtp")
    void theWindParkBlockedLetsGoOfItsCurrent() {
        Network network = bench();
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "wt4b-blocked"), new EmtpSimulationSettings(TIME_STEP, 0.8,
                FREQUENCY), List.of(new ConverterBlocking("WT1", 0.3))), EmtpSettings.defaults()).output();
        for (double[] w : new double[][] {{0.2, 0.3}, {0.3, 0.31}, {0.31, 0.33}, {0.33, 0.36}, {0.36, 0.4}, {0.7, 0.8}}) {
            System.out.printf(Locale.ROOT, "WT4B blocked EMTP %.2f-%.2f: U %.6f P %.6f Q %.6f frt %.3f%n", w[0], w[1], output.mean("control:WT1_U", w[0], w[1]),
                    output.mean("control:WT1_P", w[0], w[1]), output.mean("control:WT1_Q", w[0], w[1]), output.mean("control:WT1_FRT", w[0], w[1]));
        }
        Generator grid = network.getGenerator("GRID");
        double gridEmf = InfiniteBusEmf.fromLoadFlow(grid, (InfiniteBusGeneratorModel) grid.getExtension(GeneratorModel.class), FREQUENCY, TIME_STEP).magnitudeKv()
                / 225.0;
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(output.mean("control:WT1_P", 0.2, 0.3)).as("active power before the blocking")
                .isCloseTo(-network.getGenerator("WT1").getTerminal().getP() / 100.0, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_U", 0.2, 0.3)).as("terminal voltage before the blocking")
                .isCloseTo(network.getGenerator("WT1").getTerminal().getBusView().getBus().getV() / 33.0, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_P", 0.31, 0.8)).as("active power once blocked").isCloseTo(0.0, within(1.0e-6));
        softly.assertThat(output.mean("control:WT1_Q", 0.31, 0.8)).as("reactive power once blocked").isCloseTo(0.0, within(1.0e-6));
        softly.assertThat(output.mean("control:WT1_U", 0.36, 0.4)).as("terminal voltage 60 ms on, the grid's EMF").isCloseTo(gridEmf, within(1.0e-3));
        softly.assertThat(output.mean("control:WT1_U", 0.7, 0.8)).as("terminal voltage at the end, the grid's EMF").isCloseTo(gridEmf, within(1.0e-3));
        softly.assertAll();
    }

    /**
     * The park through the same dip as {@link #theWindParkRidesThroughAVoltageDip} - 20 ohm at B2 for 0.15 s - against PSCAD Professional
     * run live from rest, the park let go at 2 s, the dip 2 s on from EMTP's: its terminal voltage and its powers within 1e-3 pu of PSCAD's
     * settled, before the dip and in its last 100 ms, and 2e-2 through it and the recovery, on 5 ms means. Kept in {@code
     * target/emtp/wt4b-dip-pscad} and {@code target/pscad/wt4b-dip-pscad}.
     *
     * <p>Run 2026-11-15: settled within 4e-6 pu of PSCAD's; through the dip and the recovery its voltage within 4.0e-3 pu, its active power
     * 2.9e-3, its reactive power 5.1e-3.
     */
    @Test
    @Tag("emtp")
    @Tag("pscad")
    void theWindParksDipRunsInEmtpAsInPscadProfessional() throws IOException {
        List<EmtpVscFaultAgainstPscadTest.Channel> channels = List.of(new EmtpVscFaultAgainstPscadTest.Channel("WT1_U", "control:WT1_U", "WT1_WU", 1.0),
                new EmtpVscFaultAgainstPscadTest.Channel("WT1_P", "control:WT1_P", "WT1_WP", 1.0),
                new EmtpVscFaultAgainstPscadTest.Channel("WT1_Q", "control:WT1_Q", "WT1_WQ", 1.0));
        BusFault dip = ExampleNetworks.WIND_TURBINE_DIP;
        EmtpVscFaultAgainstPscadTest.compare(EmtpWindTurbineTest::bench, start -> List.of(new BusFault(dip.id(), dip.busId(), "ABC", true, dip.onResistanceOhm(),
                start, start + dip.openTimeSeconds() - dip.closeTimeSeconds(), dip.clearing())), channels, "wt4b-dip-pscad", 5.0e-3, 1.0e-3, 0.02, true,
                EmtpVscFaultAgainstPscadTest.PSCAD_SHIFT, PscadSwitching.INTERPOLATED);
    }

    /** REEC_A's three controls written (the park with {@code PfFlag}, {@code VFlag} and {@code QFlag}): its active power filtered, its
     * reactive power's and its voltage's PIs - each integral frozen through a dip -, no longer {@code tIq}'s lag; every line within EMTP's
     * 100 characters. */
    @Test
    void writesAWt4bsReactiveControls(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(flagged(true, true, true), tempDir, new EmtpSimulationSettings(20.0e-6, 0.1,
                FREQUENCY)), StandardCharsets.ISO_8859_1);

        assertThat(lines).anyMatch(line -> line.startsWith("_c_fs;WT1_PF;")).anyMatch(line -> line.startsWith("_c_fu;WT1_QPIN;"))
                .anyMatch(line -> line.startsWith("_c_fu;WT1_VPIN;")).noneMatch(line -> line.startsWith("_c_fu;WT1_BN;"));
        assertThat(lines).filteredOn(line -> !line.startsWith("!")).allMatch(line -> line.length() <= EmtpNetlistWriter.MAX_LINE);
    }

    /**
     * REEC_A's power factor, reactive power and voltage controls against PSCAD Professional, the park through the same dip as {@link
     * #theWindParksDipRunsInEmtpAsInPscadProfessional}: with {@code PfFlag} alone, the reactive power reference the active power's through
     * {@code tpREEC} on the load flow's power factor; with all three, that reference through a PI on the reactive power to the voltage
     * reference, and a PI on the voltage to the reactive current. No dataset sets them: Dynawo's example's park with its gains. Its terminal
     * voltage and its powers within 1e-3 pu of PSCAD's settled, before the dip and in its last 100 ms, and 2e-2 through it and the
     * recovery, on 5 ms means. Kept in {@code target/emtp/wt4b-<flags>-pscad} and {@code target/pscad/wt4b-<flags>-pscad}.
     *
     * <p>Run 2026-11-17, 61 s the four: {@code PfFlag} alone settled within 8.4e-6 pu of PSCAD's, through the dip 4.0e-3 (voltage), 2.9e-3
     * (active power), 5.1e-3 (reactive power); all three within 3.8e-6, and 3.5e-3, 3.0e-3, 3.9e-3.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pf", "pf-v-q"})
    @Tag("emtp")
    @Tag("pscad")
    void theWindParksReactiveControlsRunInEmtpAsInPscadProfessional(String flags) throws IOException {
        List<EmtpVscFaultAgainstPscadTest.Channel> channels = List.of(new EmtpVscFaultAgainstPscadTest.Channel("WT1_U", "control:WT1_U", "WT1_WU", 1.0),
                new EmtpVscFaultAgainstPscadTest.Channel("WT1_P", "control:WT1_P", "WT1_WP", 1.0),
                new EmtpVscFaultAgainstPscadTest.Channel("WT1_Q", "control:WT1_Q", "WT1_WQ", 1.0));
        BusFault dip = ExampleNetworks.WIND_TURBINE_DIP;
        boolean all = "pf-v-q".equals(flags);
        EmtpVscFaultAgainstPscadTest.compare(() -> flagged(true, all, all), start -> List.of(new BusFault(dip.id(), dip.busId(), "ABC", true,
                dip.onResistanceOhm(), start, start + dip.openTimeSeconds() - dip.closeTimeSeconds(), dip.clearing())), channels, "wt4b-" + flags + "-pscad",
                5.0e-3, 1.0e-3, 0.02, true, EmtpVscFaultAgainstPscadTest.PSCAD_SHIFT, PscadSwitching.INTERPOLATED);
    }

    /** Upstream's WT4B bench, its REEC_A's {@code PfFlag}, {@code VFlag} and {@code QFlag} as given, the load flow run. */
    static Network flagged(boolean powerFactor, boolean reactivePower, boolean voltage) {
        Network network = ExampleNetworks.createWindTurbineNetwork();
        Generator turbine = network.getGenerator("WT1");
        Wt4bModel model = (Wt4bModel) turbine.getExtension(GeneratorModel.class);
        Wt4bModel.ElectricalControl r = model.getElectricalControl();
        turbine.addExtension(GeneratorModel.class, new Wt4bModel(new Wt4bModel.ElectricalControl(powerFactor, reactivePower, voltage, r.activePowerPriority(),
                r.voltageFilterTimeConstant(), r.activePowerFilterTimeConstant(), r.reactivePowerMax(), r.reactivePowerMin(), r.voltageMax(), r.voltageMin(),
                r.reactivePowerProportionalGain(), r.reactivePowerIntegralGain(), r.voltageProportionalGain(), r.voltageIntegralGain(),
                r.reactiveCurrentTimeConstant(), r.voltageBias(), r.voltageDipThreshold(), r.voltageUpThreshold(), r.injectionVoltageReference(),
                r.injectionDeadbandLow(), r.injectionDeadbandHigh(), r.injectionGain(), r.injectionMax(), r.injectionMin(), r.injectionHoldTime(),
                r.frozenInjection(), r.activePowerRateMax(), r.activePowerRateMin(), r.activePowerOrderTimeConstant(), r.activePowerMax(), r.activePowerMin(),
                r.currentMax(), r.activeCurrentLimitHoldTime(), r.activeCurrentLimit(), r.reactiveCurrentLimit()), model.getGeneratorConverter(), model.getPll()));
        return ExampleNetworks.withLoadFlow(network);
    }

    /** Upstream's WT4B bench, the load flow run. */
    private static Network bench() {
        return ExampleNetworks.withLoadFlow(ExampleNetworks.createWindTurbineNetwork());
    }
}
