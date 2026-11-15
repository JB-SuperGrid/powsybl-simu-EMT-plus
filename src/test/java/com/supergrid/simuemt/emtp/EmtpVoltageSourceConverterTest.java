/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.DcNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.BranchOperation;
import com.powsybl.simuemt.extensions.event.BreakerAction;
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.DcSwitchOperation;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's VSC monopole between infinite buses, its second variant ({@code vsc-monopole-infinite-buses-2}): B1 and B2 on 5000 MVA
 * infinite buses, VSC1 at B1 on its active power (200 MW into it), VSC2 at B2 on the DC voltage with an averaged chopper, each 250 MVA
 * behind its 0.002 + j0.15 pu reactor, an SRF PLL and a single-sequence current control; the 5 ohm, 0.3 H DC line between them, each
 * return solidly earthed.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpVoltageSourceConverterTest {

    private static final double FREQUENCY = 50.0;
    private static final double RATING_MVA = 250.0;

    /** Each converter written: its EMFs behind its reactor, their stand-ins, its probes, its DC current source and capacitor, the DC
     * pole's stand-in, its control. */
    @Test
    void writesEachConverterAndItsDcSide(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(bench(), tempDir, new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY)),
                StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_RLC;DCL;2;2;DC1P,DC2P,", "5.000000000000000,0.3000000000000000,0,0,0,", "_RLC;DCG1;1;1;DC1G,",
                "_cV;VSC1_CVa;6;6;VSC1_Ma,VSC1_N,VSC1_EMFa,VSC1_CVVa,VSC1_Ia,VSC1_CVPa,", "_Vsine;VSC1_STDa;2;2;VSC1_Ka,VSC1_Ma,",
                "_cI;VSC1_PRa;6;6;B1a,,VSC1_ZERO,VSC1_Va,VSC1_PRIa,VSC1_PRPa,", "_cI;VSC1_DC;6;6;VSC1_DCP,VSC1_DCR,VSC1_IDCS,VSC1_DCV,VSC1_DCI,VSC1_DCPW,",
                "_Sw0;VSC1_DCSW;2;2;VSC1_DCS,DC1P,", "_c_fu;VSC2_DCH;3;3;VSC2_DCH,VSC2_VDP,VSC2_VDR,");
        // The stand-in of VSC1's EMF, phase a: the load flow's, active in the steady state only.
        String standIn = lines.get(lines.indexOf("_Vsine;VSC1_STDa;2;2;VSC1_Ka,VSC1_Ma,") + 1);
        assertThat(standIn).endsWith(",-1,1E-15,");
        // No chopper on VSC1, P_PCC; VSC2's DC voltage loop.
        assertThat(lines).noneMatch(line -> line.startsWith("_c_fu;VSC1_DCH;"));
    }

    /** A DDSRF's control (example 1): each converter's negative frame, its sequences decoupled, its PLL on the positive sequence's
     * decoupled q-axis voltage, a current loop per sequence and the EMFs of both; every line within EMTP's 100 characters, under the
     * bipole's five-character ids as well. */
    @Test
    void writesTheSequencesOfADdsrf(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(bench("vsc-monopole-infinite-buses"), tempDir.resolve("monopole"),
                new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY)), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_c_fu;VSC1_BND;5;5;VSC1_BND,VSC1_Va,VSC1_Vc,VSC1_Vb,VSC1_TH,", "_c_fs;VSC1_BF3;2;2;VSC1_BF3,VSC1_BE3,",
                "_c_fs;VSC1_PLI;2;2;VSC1_PLI,VSC1_BE2,", "_c_fu;VSC1_EDN;4;4;VSC1_EDN,VSC1_BF3,VSC1_ZE4,VSC1_UDN,",
                "_c_fu;VSC1_EMFa;6;6;VSC1_EMFa,VSC1_ED,VSC1_EQ,VSC1_TH,VSC1_EDN,VSC1_EQN,");
        List<String> bipole = Files.readAllLines(EmtpNetlistWriter.write(bench("vsc-bipole-infinite-buses"), tempDir.resolve("bipole"),
                new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY)), StandardCharsets.ISO_8859_1);
        assertThat(bipole).filteredOn(line -> !line.startsWith("!")).allMatch(line -> line.length() <= EmtpNetlistWriter.MAX_LINE);
    }

    /** A hybrid DC breaker opened through a DC fault (example 4 with DCB): its main branch's switch closed from the steady state, opened
     * at the order whatever its current; its ZnO absorber across it, its first segment from 1e-3 pu; the fault's switch and resistance. */
    @Test
    void writesAHybridBreakerOpenedThroughADcFault(@TempDir Path tempDir) throws IOException {
        List<String> lines = Files.readAllLines(EmtpNetlistWriter.write(bench("vsc-three-terminal-dc-breaker"), tempDir,
                new EmtpSimulationSettings(20.0e-6, 0.2, FREQUENCY), List.of(new DcFault("FDC", "DC2L", 10.0, 0.1, 1.0),
                        DcSwitchOperation.opening("DCB", 0.103))), StandardCharsets.ISO_8859_1);

        assertThat(lines).contains("_Sw0;DCB;2;2;DCB_S,DC2L,", "_RLC;DCB_R;2;2;DC2P,DCB_S,", "_ZnO;DCB_ZNO;2;2;DC2P,DC2L,",
                "750000.0000000000,,,1e-06,15,5,1,?i,", "10000.00000000000 26.00000000000000 0.001000000000000000", "_Sw0;FDC;2;2;DC2L,DFLT1,");
        String main = lines.get(lines.indexOf("_Sw0;DCB;2;2;DCB_S,DC2L,") + 1);
        assertThat(main).startsWith("-1," + EmtpNetlistWriter.number(NumericalAids.switchingInstant(0.103, 20.0e-6)) + ",1000000000.000000,");
    }

    /**
     * Run live from EMTP's steady state over 0.5 s: every bus within 1e-4 pu and 0.01 degree of the load flow, every DC node within 1e-4
     * pu, VSC1's powers within 1e-3 pu; VSC2, on the DC voltage, carries the converters' reactor losses besides the load flow's, its power
     * within 5e-3 pu - as upstream's backends and PSCAD Professional ({@code PscadProExampleCoverageTest}). Kept in {@code
     * target/emtp/vsc-monopole-2}.
     *
     * <p>Run 2026-11-14: the buses within 2.0e-5 pu and 5.2e-3 degree, the DC nodes within 8 V of 500 kV, VSC1 within 1e-4 pu, VSC2 2.6e-3
     * pu from the load flow's. The EMFs a step ahead of the PLL's angle: a step behind, as EMTP's network takes them, the run started
     * 0.09 degree off and took 0.3 s to settle.
     */
    @Test
    @Tag("emtp")
    void theMonopoleRunsOnItsLoadFlow() {
        Network network = bench();
        EmtpSimulationSettings settings = new EmtpSimulationSettings(20.0e-6, 0.5, FREQUENCY);
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", "vsc-monopole-2"), settings), EmtpSettings.defaults()).output();
        double[] last = {0.48, 0.5};
        SoftAssertions softly = new SoftAssertions();
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            double crest = bus.getV() * 1.0e3 * Math.sqrt(2.0 / 3.0);
            var phasor = output.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus.getId(), 'A'), FREQUENCY, last[0], last[1]);
            double pu = phasor.magnitude() / crest - 1.0;
            double degrees = Math.IEEEremainder(-Math.toDegrees(phasor.angleRadians()) - bus.getAngle(), 360.0);
            System.out.printf(Locale.ROOT, "%s: %.2e pu, %.2e degree%n", bus.getId(), pu, degrees);
            softly.assertThat(pu).as("%s, pu", bus.getId()).isCloseTo(0.0, within(1.0e-4));
            softly.assertThat(degrees).as("%s, degrees", bus.getId()).isCloseTo(0.0, within(0.01));
        }
        for (DcNode node : network.getDcNodes()) {
            double kv = output.mean(EmtpVoltageSourceConverters.dcVoltageScope(node.getId()), last[0], last[1]) / 1.0e3;
            System.out.printf(Locale.ROOT, "%s: %.4f kV, load flow %.4f%n", node.getId(), kv, node.getV());
            softly.assertThat((kv - node.getV()) / node.getNominalV()).as("%s, pu", node.getId()).isCloseTo(0.0, within(1.0e-4));
        }
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            String id = converter.getId();
            double p = output.mean("control:" + id + "_P", last[0], last[1]) - converter.getTerminal1().getP() / RATING_MVA;
            double q = output.mean("control:" + id + "_Q", last[0], last[1]) - converter.getTerminal1().getQ() / RATING_MVA;
            System.out.printf(Locale.ROOT, "%s: P %.2e pu, Q %.2e pu off the load flow%n", id, p, q);
            double tolerance = "VSC2".equals(id) ? 5.0e-3 : 1.0e-3;
            softly.assertThat(p).as("%s's power, pu", id).isCloseTo(0.0, within(tolerance));
            softly.assertThat(q).as("%s's reactive power, pu", id).isCloseTo(0.0, within(1.0e-3));
        }
        softly.assertAll();
    }

    /**
     * A symmetric monopole - each converter's DC capacitor split, its midpoint earthed - through a solid pole-to-earth fault on DC2P from
     * 0.3 s to the end, run 1 s: its DC side shifted to earth, DC2P at earth and the negative poles at some -500 kV from 0.5 s on - what
     * upstream holds ATP, Xyce and Kestrel to alike ({@code BackendComparisonTest#allThreeBackendsCarryASymmetricMonopoleThroughAPoleToEarthFault}:
     * DC2P within 10 V of earth, DC1N 490 to 502 kV below it). PSCAD refuses a converter's own DC earthing. Kept in {@code
     * target/emtp/<bench>-dc-fault}.
     *
     * <p>Run 2026-11-15: DC2P 0.75 V, DC1N 497.81 kV below earth from 0.5 s, on the two-level converters and the MMCs alike - the DC
     * voltage station holding 500 kV across its terminals either way.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vsc-symmetric-monopole-infinite-buses", "vsc-symmetric-monopole-mmc"})
    @Tag("emtp")
    void aSymmetricMonopoleShiftsToEarthThroughAPoleToEarthFault(String name) {
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(bench(name), Path.of("target", "emtp", name + "-dc-fault"),
                new EmtpSimulationSettings(20.0e-6, 1.0, FREQUENCY), List.of(new DcFault("F", "DC2P", 0.0, 0.3, 10.0))), EmtpSettings.defaults()).output();
        double dc2p = output.mean(EmtpVoltageSourceConverters.dcVoltageScope("DC2P"), 0.5, 1.0);
        double dc1n = output.mean(EmtpVoltageSourceConverters.dcVoltageScope("DC1N"), 0.5, 1.0);
        System.out.printf(Locale.ROOT, "%s: DC2P %.2f V, DC1N %.2f kV after the fault%n", name, dc2p, dc1n / 1.0e3);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(dc2p).as("%s, DC2P after the fault, V", name).isCloseTo(0.0, within(10.0));
        softly.assertThat(-dc1n / 1.0e3).as("%s, DC1N after the fault, kV below earth", name).isBetween(490.0, 502.0);
        softly.assertAll();
    }

    /**
     * Upstream's {@code dc-fault-protected}: example 4 on its droops, each station with a 100 mH DC reactor and protected against its arms'
     * overcurrent at 2 pu, no delay, through a solid fault on DC3P from 0.3 s to the end, no order to block, run 1.4 s - held to the checks
     * upstream holds ATP and Kestrel to, which the overlay holds PSCAD Professional to ({@code
     * PscadProConvertersTest#theStationsProtectionsBlockThemOnASolidDcFault}): the protections blocking VSC3, VSC2 and VSC1 in turn - ATP's
     * 0.85, 4.67 and 9.93 ms after the fault -; then every 0.2 s VSC3's AC side a bolted short behind its phase reactor, {@code Q / V^2} its
     * {@code 1 / X} within 0.07, its DC side at earth; VSC1 and VSC2 rectifying into the fault through the DC lines, VSC2's DC voltage within
     * [0.03, 0.15], VSC1's 0.01 above it, each drawing more than 0.4 pu. Kept in {@code target/emtp/dc-fault-protected}.
     *
     * <p>Run 2026-11-15: VSC3, VSC2 and VSC1 blocked 0.85, 4.67 and 9.93 ms after the fault - ATP's to the hundredth of a millisecond -;
     * VSC3's {@code Q / V^2} 6.650 to 6.702 against 6.667, its DC voltage within 2.6e-3 pu of earth, its capacitor ringing behind its bridge
     * inductance; VSC2's DC voltage 0.070 to 0.080 pu, VSC1's 0.103 to 0.119; their powers 0.50 to 0.95 pu, rising as their DC currents do.
     */
    @Test
    @Tag("emtp")
    void theStationsProtectionsBlockThemOnASolidDcFault() {
        blockedInTurnAndFedThroughTheBridges(protectedWithDcReactors(), "dc-fault-protected", new double[] {9.93e-3, 4.67e-3, 0.85e-3}, 0.07);
    }

    /**
     * Upstream's {@code mmc-protected}: example 4's MMC stations protected at 2 pu through the same solid fault on DC3P - each equivalent
     * capacitor behind its diode once blocked, charging, never discharging -, held to the same checks, ATP's instants 0.25, 3.09 and 6.93
     * ms after the fault and VSC3's {@code Q / V^2} within 0.6 of {@code 1 / X} ({@code PscadProConvertersTest#theMmcsProtectionsBlockThemOnASolidDcFault}).
     * Kept in {@code target/emtp/mmc-protected}.
     *
     * <p>Run 2026-11-15: VSC3, VSC2 and VSC1 blocked 0.25, 3.09 and 6.91 ms after the fault - ATP's 0.25, 3.09 and 6.93 -; VSC3's {@code Q /
     * V^2} 13.087 to 13.295 against 13.333 (PSCAD's 13.08 to 13.26), its DC voltage within 4e-4 pu of earth; VSC2's DC voltage 0.086 to
     * 0.100 pu, VSC1's 0.126 to 0.146.
     */
    @Test
    @Tag("emtp")
    void theMmcsProtectionsBlockThemOnASolidDcFault() {
        Network network = ExampleNetworks.readVscBench("vsc-three-terminal-all-droop-mmc", EmtExtensionsXmlReader::readNetwork);
        network.getVoltageSourceConverters().forEach(c -> c.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        blockedInTurnAndFedThroughTheBridges(network, "mmc-protected", new double[] {6.93e-3, 3.09e-3, 0.25e-3}, 0.6);
    }

    /**
     * Upstream's {@code dc-fault-stations-open}: the protected two-level stations with their DC reactors through the solid fault on DC3P
     * at 0.3 s, their transformers' station ends opened at 0.36 s at their currents' zeros - held to ATP's checks ({@code
     * AtpSolverIntegrationTest#theStationsAcBreakersCutTheBridgesOffASolidDcFault}, which {@code PscadProConvertersTest} holds PSCAD
     * Professional to): every station blocked by its protection before its breaker opens, nothing drawn from the AC side once it has;
     * VSC1's and VSC2's DC currents died out, their DC voltages at earth; VSC3's freewheeling through its bridge's diodes at 0.9193 of its
     * 0.4-0.6 s mean 0.6 s on, within 0.0015. Run 1.2 s, kept in {@code target/emtp/dc-fault-stations-open}.
     *
     * <p>Run 2026-11-15: the stations blocked 0.85, 4.67 and 9.93 ms after the fault, nothing drawn once cut off (1e-7 pu); VSC3's DC
     * current 20.51 pu, 0.9193 of it 0.6 s on - ATP's -, its capacitor ringing to 0.38 pu behind its bridge inductance (without it, left out
     * at first, 0.8516 and no ringing).
     */
    @Test
    @Tag("emtp")
    void theStationsAcBreakersCutTheBridgesOffASolidDcFault() {
        EmtpOutput output = stationsOpen(protectedWithDcReactors(), "dc-fault-stations-open");
        SoftAssertions softly = new SoftAssertions();
        cutOffTheirAcSides(softly, output);
        double early = output.mean("control:VSC3_IDC", 0.4, 0.6);
        double decay = output.mean("control:VSC3_IDC", 1.0, 1.2) / early;
        System.out.printf(Locale.ROOT, "VSC3 DC current %.3f pu at 0.4-0.6 s, %.4f of it at 1.0-1.2 s (ATP 0.9193); its DC voltage's crest %.3f%n", early, decay,
                output.peakAbsolute("control:VSC3_VDC", 1.0, 1.2));
        softly.assertThat(early).as("VSC3 DC current at 0.4-0.6 s, pu").isGreaterThan(15.0);
        softly.assertThat(decay).as("VSC3 DC current at 1.0-1.2 s against 0.4-0.6 s: ATP's").isCloseTo(0.9193, within(0.0015));
        softly.assertThat(output.peakAbsolute("control:VSC3_VDC", 1.0, 1.2)).as("VSC3 DC voltage at the end, pu: its capacitor ringing").isGreaterThan(0.2);
        softly.assertAll();
    }

    /**
     * Upstream's {@code mmc-stations-open}: the same with the protected MMC stations, no DC reactor added - held to ATP's checks ({@code
     * AtpSolverIntegrationTest#theStationsAcBreakersLetAnMmcsDcCurrentDieOut}): VSC3's DC current round its arms' DC equivalent ATP's
     * 5.8125 pu at 0.4-0.5 s within 0.03, 0.387 of it the next 0.1 s within 0.01, under 0.2% of it at 1.1-1.2 s. Kept in {@code
     * target/emtp/mmc-stations-open}.
     *
     * <p>Run 2026-11-15: VSC3's DC current 5.8185 pu, 0.3867 of it the next 0.1 s, 1.4e-3 of it at the end - ATP's 5.8125 and 0.387.
     */
    @Test
    @Tag("emtp")
    void theStationsAcBreakersLetAnMmcsDcCurrentDieOut() {
        Network network = ExampleNetworks.readVscBench("vsc-three-terminal-all-droop-mmc", EmtExtensionsXmlReader::readNetwork);
        network.getVoltageSourceConverters().forEach(c -> c.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        EmtpOutput output = stationsOpen(network, "mmc-stations-open");
        SoftAssertions softly = new SoftAssertions();
        cutOffTheirAcSides(softly, output);
        double current = output.mean("control:VSC3_IDC", 0.4, 0.5);
        double decay = output.mean("control:VSC3_IDC", 0.5, 0.6) / current;
        double end = output.mean("control:VSC3_IDC", 1.1, 1.2) / current;
        System.out.printf(Locale.ROOT, "VSC3 DC current %.4f pu at 0.4-0.5 s (ATP 5.8125), %.4f of it the next 0.1 s (ATP 0.387), %.2e at 1.1-1.2 s%n", current, decay,
                end);
        softly.assertThat(current).as("VSC3 DC current at 0.4-0.5 s, pu: ATP's").isCloseTo(5.8125, within(0.03));
        softly.assertThat(decay).as("VSC3 DC current at 0.5-0.6 s against 0.4-0.5 s: ATP's").isCloseTo(0.387, within(0.01));
        softly.assertThat(end).as("VSC3 DC current at 1.1-1.2 s against 0.4-0.5 s: died out").isLessThan(0.002);
        softly.assertAll();
    }

    /** The solid fault on DC3P at 0.3 s, the transformers' station ends T1, T2, T3 opened at 0.36 s at their currents' zeros, run 1.2 s. */
    private static EmtpOutput stationsOpen(Network network, String name) {
        List<EmtEvent> events = new ArrayList<>(List.of(new DcFault("FDC", "DC3P", 0.0, 0.3, 10.0)));
        for (String transformer : List.of("T1", "T2", "T3")) {
            events.add(new BranchOperation(transformer, TwoSides.ONE, 0.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO));
        }
        return EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name), new EmtpSimulationSettings(20.0e-6, 1.2, FREQUENCY), events),
                EmtpSettings.defaults()).output();
    }

    /** Every station blocked by its protection within 20 ms of the fault, before its breaker opens, nothing drawn from the AC side once it
     * has; VSC1's and VSC2's DC currents died out and their DC voltages at earth over the last 0.2 s. */
    private static void cutOffTheirAcSides(SoftAssertions softly, EmtpOutput output) {
        double fault = NumericalAids.switchingInstant(0.3, 20.0e-6);
        for (String id : List.of("VSC1", "VSC2", "VSC3")) {
            double blocked = output.firstTimeBelow("control:" + id + "_RUN", 0.5, 0.2);
            double p = output.peakAbsolute("control:" + id + "_P", 0.4, 1.2);
            double q = output.peakAbsolute("control:" + id + "_Q", 0.4, 1.2);
            System.out.printf(Locale.ROOT, "%s blocked %.2f ms after the fault; P %.1e, Q %.1e once cut off; DC current %.1e, voltage %.1e at the end%n", id,
                    (blocked - fault) * 1.0e3, p, q, output.peakAbsolute("control:" + id + "_IDC", 1.0, 1.2), output.peakAbsolute("control:" + id + "_VDC", 1.0, 1.2));
            softly.assertThat(blocked).as("%s blocked at, s: by its protection, before its breaker opens", id).isBetween(fault, fault + 0.02);
            softly.assertThat(p).as("%s power once its breaker has opened, pu", id).isLessThan(1.0e-4);
            softly.assertThat(q).as("%s reactive power once its breaker has opened, pu", id).isLessThan(1.0e-4);
        }
        for (String id : List.of("VSC1", "VSC2")) {
            softly.assertThat(output.peakAbsolute("control:" + id + "_IDC", 1.0, 1.2)).as("%s DC current at the end, pu: died out", id).isLessThan(0.01);
            softly.assertThat(output.peakAbsolute("control:" + id + "_VDC", 1.0, 1.2)).as("%s DC voltage at the end, pu: at earth", id).isLessThan(0.005);
        }
    }

    /** Example 4 through a solid fault on DC3P from 0.3 s to the end, run 1.4 s: its stations blocked in turn by their protections, each
     * {@code atp} seconds after the fault within 0.5 ms; then upstream's checks every 0.2 s. */
    private static void blockedInTurnAndFedThroughTheBridges(Network network, String name, double[] atp, double shortCircuitTolerance) {
        double fault = 0.3;
        double runEnd = 1.4;
        EmtpOutput output = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name),
                new EmtpSimulationSettings(20.0e-6, runEnd, FREQUENCY), List.of(new DcFault("FDC", "DC3P", 0.0, fault, 10.0))), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        String[] ids = {"VSC1", "VSC2", "VSC3"};
        for (int k = 0; k < 3; k++) {
            double blocked = output.firstTimeBelow("control:" + ids[k] + "_RUN", 0.5, 0.2) - NumericalAids.switchingInstant(fault, 20.0e-6);
            System.out.printf(Locale.ROOT, "%s blocked %.2f ms after the fault (ATP %.2f)%n", ids[k], blocked * 1.0e3, atp[k] * 1.0e3);
            softly.assertThat(blocked).as("%s blocked after the fault, s, against ATP's", ids[k]).isCloseTo(atp[k], within(0.5e-3));
        }
        double reactance = ((AveragedConverterModel) network.getVoltageSourceConverter("VSC3").getExtension(VoltageSourceConverterModel.class))
                .getPhaseReactorReactance();
        for (double start = 0.4; start < runEnd - 1.0e-9; start += 0.2) {
            double end = start + 0.2;
            double v3 = output.mean("control:VSC3_VAC", start, end);
            double shortCircuit = output.mean("control:VSC3_Q", start, end) / (v3 * v3);
            double vdc1 = output.mean("control:VSC1_VDC", start, end);
            double vdc2 = output.mean("control:VSC2_VDC", start, end);
            double vdc3 = output.mean("control:VSC3_VDC", start, end);
            double p1 = output.mean("control:VSC1_P", start, end);
            double p2 = output.mean("control:VSC2_P", start, end);
            System.out.printf(Locale.ROOT, "from %.1f s: VSC3 Q/V^2 %.3f (1/X %.3f), DC %.4f; VSC2 DC %.4f, VSC1 DC %.4f; P %.3f and %.3f%n", start, shortCircuit,
                    1.0 / reactance, vdc3, vdc2, vdc1, p1, p2);
            softly.assertThat(shortCircuit).as("VSC3 Q / V^2 from %.1f s", start).isCloseTo(1.0 / reactance, within(shortCircuitTolerance));
            softly.assertThat(vdc3).as("VSC3 DC voltage from %.1f s, pu", start).isCloseTo(0.0, within(0.005));
            softly.assertThat(vdc2).as("VSC2 DC voltage from %.1f s, pu", start).isBetween(0.03, 0.15);
            softly.assertThat(vdc1).as("VSC1 DC voltage from %.1f s, pu", start).isGreaterThan(vdc2 + 0.01);
            softly.assertThat(p1).as("VSC1 power from %.1f s, pu", start).isGreaterThan(0.4);
            softly.assertThat(p2).as("VSC2 power from %.1f s, pu", start).isGreaterThan(0.4);
        }
        softly.assertAll();
    }

    /** Example 4 on its droops, each station with a 100 mH DC reactor and protected against its arms' overcurrent at 2 pu, no delay:
     * upstream's {@code dc-fault-protected} bench. */
    static Network protectedWithDcReactors() {
        Network network = ExampleNetworks.readVscBench("vsc-three-terminal-all-droop", EmtExtensionsXmlReader::readNetwork);
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            AveragedVscModel model = (AveragedVscModel) converter.getExtension(VoltageSourceConverterModel.class);
            converter.addExtension(VoltageSourceConverterModel.class, new AveragedVscModel(model.getRatedApparentPower(), model.getPhaseReactorResistance(),
                    model.getPhaseReactorReactance(), model.getDcCapacitorEnergyConstant(), 0.1, model.getBridgeInductance()));
            converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0));
        }
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    static Network bench() {
        return bench("vsc-monopole-infinite-buses-2");
    }

    /** Upstream's VSC bench {@code name}, preprocessed. */
    static Network bench(String name) {
        Network network = ExampleNetworks.readVscBench(name, EmtExtensionsXmlReader::readNetwork);
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }
}
