/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.DcNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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
                "_cI;VSC1_PRa;6;6;B1a,,VSC1_ZERO,VSC1_Va,VSC1_PRIa,VSC1_PRPa,", "_cI;VSC1_DC;6;6;VSC1_DCP,VSC1_DCR,VSC1_IDC,VSC1_DCV,VSC1_DCI,VSC1_DCPW,",
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
