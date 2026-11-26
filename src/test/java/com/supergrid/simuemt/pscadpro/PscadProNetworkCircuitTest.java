/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedMmcModel;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.extensions.pss.PssModel;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadComponent;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The network-scale study benches of powsybl-simu-emt's test resources (CIGRE B4, IEEE 14, 39 and 68 with their DC grids, Kundur's
 * two areas with theirs, PROMOTioN), written for PSCAD Professional, past PSCAD Free's limits, without running PSCAD: every machine,
 * station and DC line in the circuit. Their files, builders and load flows are upstream's, and tested there.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProNetworkCircuitTest {

    private static PscadCircuit circuit(Network network, Path tempDir) {
        return PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL)).circuit();
    }

    private static Network bench(String bench) {
        return ExampleNetworks.withLoadFlow(PscadProCases.bench(bench));
    }

    /** Every DC line of {@code network} written once, under its own name. */
    private static void assertDcLinesWritten(Network network, PscadCircuit circuit) {
        assertThat(network.getDcLineCount()).isPositive();
        network.getDcLines().forEach(line -> assertThat(circuit.components().stream()
                .filter(c -> line.getId().equals(c.parameters().get("Name")))).as(line.getId()).hasSize(1));
    }

    /** {@code generator}'s machine subtransient, its governor a TGOV1. */
    private static void assertSubtransientWithTgov1(Generator generator) {
        Object model = generator.getExtension(GeneratorModel.class);
        Object governor = generator.getExtension(TurbineGovernorModel.class);
        assertThat(model).as(generator.getId()).isInstanceOf(SubtransientMachineModel.class);
        assertThat(governor).as("%s governor", generator.getId()).isInstanceOf(Tgov1Model.class);
    }

    /**
     * CIGRE B4: its eleven stations, each its controls; DCS3's node BBE1 a junction of two cables, no station on it; its double lines
     * A1-B1 and B2-B4 each two lines; the DC nodes single-phase; its meshed AC lines and system C's cable written.
     */
    @Test
    void cigreB4(@TempDir Path tempDir) {
        PscadCircuit circuit = circuit(bench("cigre-b4"), tempDir);

        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL") && d.startsWith("C")))
                .containsExactlyInAnyOrder("CMA1_CTRL", "CBA1_CTRL", "CBB1_CTRL", "CMB2_CTRL", "CBB2_CTRL", "CMB3_CTRL", "CMC1_CTRL",
                        "CBC2_CTRL", "CBD1_CTRL", "CME1_CTRL", "CMF1_CTRL");
        List<String> onJunction = circuit.components().stream().filter(c -> c.connections().containsValue("BBE1"))
                .map(c -> c.parameters().getOrDefault("Name", c.definition())).toList();
        assertThat(onJunction).contains("DBB1E1", "DBD1E1").noneMatch(name -> name.startsWith("CB") || name.startsWith("CM"));
        for (String line : List.of("DBA1B1A", "DBA1B1B", "DBB2B4A", "DBB2B4B")) {
            assertThat(circuit.components().stream().filter(c -> line.equals(c.parameters().get("Name")))).as(line).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BBA1", "BBB1", "BBB2", "BBB4", "BBC2", "BBD1", "BBE1", "BMA1", "BMC1", "BMB5");
        for (String line : List.of("LB0B1", "LB0B2", "LB0B3", "LB1B3", "LB2B3", "LC1C2")) {
            assertThat(circuit.components().stream().anyMatch(c -> c.parameters().getOrDefault("Name", "").startsWith(line))).as(line).isTrue();
        }
    }

    /**
     * IEEE 14: its five machines, Dynawo's, each its controls - G1 and G2 a governor, the synchronous condensers none; its four
     * stations' controls and its wind park's; the DC grid's triangle and the wind park's two cables.
     */
    @Test
    void ieee14Dc(@TempDir Path tempDir) {
        Network network = bench("ieee14-dc");
        PscadCircuit circuit = circuit(network, tempDir);

        for (String machine : List.of("G1", "G2", "G3", "G6", "G8")) {
            Object model = network.getGenerator(machine).getExtension(GeneratorModel.class);
            assertThat(model).as(machine).isInstanceOf(SubtransientMachineModel.class);
        }
        assertThat(network.getGeneratorStream().filter(g -> g.getExtension(TurbineGovernorModel.class) != null).map(Generator::getId).toList())
                .containsExactlyInAnyOrder("G1", "G2");
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS2_CTRL", "CS3_CTRL", "CS9_CTRL", "CSW_CTRL", "WTW_CTRL");
        assertThat(network.getDcLineStream().map(l -> l.getId()).toList()).containsExactlyInAnyOrder("D2_3", "D3_9", "D2_9", "DW_2", "DW_9");
        assertDcLinesWritten(network, circuit);
        assertThat(circuit.singlePhaseNodes()).contains("BD2", "BD3", "BD9", "BDW");
    }

    /** Kundur's two areas: its four machines, each with its exciter, its PSS and a TGOV1; its three stations' controls and its wind
     * park's; the DC grid's three lines. */
    @Test
    void kundurDc(@TempDir Path tempDir) {
        Network network = bench("kundur-dc");
        PscadCircuit circuit = circuit(network, tempDir);

        for (int machine = 1; machine <= 4; machine++) {
            Generator generator = network.getGenerator("G" + machine);
            assertSubtransientWithTgov1(generator);
            assertThat(generator.getExtension(PssModel.class).isEnabled()).as("G%d PSS", machine).isTrue();
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS7_CTRL", "CS9_CTRL", "CSW_CTRL", "WTW_CTRL");
        assertThat(network.getDcLineStream().map(l -> l.getId()).toList()).containsExactlyInAnyOrder("D7_9", "DW_7", "DW_9");
        assertDcLinesWritten(network, circuit);
        assertThat(circuit.singlePhaseNodes()).contains("BD7", "BD9", "BDW");
    }

    /** IEEE 39: its ten machines, Athay's, each with its exciter, its PSS and a TGOV1; its four stations' controls and its wind park's;
     * the DC grid's five lines. */
    @Test
    void ieee39Dc(@TempDir Path tempDir) {
        Network network = bench("ieee39-dc");
        PscadCircuit circuit = circuit(network, tempDir);

        for (int bus = 30; bus <= 39; bus++) {
            Generator generator = network.getGenerator("G" + bus);
            assertSubtransientWithTgov1(generator);
            assertThat(generator.getExtension(PssModel.class).isEnabled()).as("G%d PSS", bus).isTrue();
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS4_CTRL", "CS16_CTRL", "CS26_CTRL", "CSW_CTRL", "WTW_CTRL");
        assertThat(network.getDcLineStream().map(l -> l.getId()).toList()).containsExactlyInAnyOrder("D4_16", "D16_26", "D26_4", "DW_26", "DW_16");
        assertDcLinesWritten(network, circuit);
        assertThat(circuit.singlePhaseNodes()).contains("BD4", "BD16", "BD26", "BDW");
    }

    /** IEEE 68: its sixteen machines, PST's, each with its exciter and a TGOV1, G53 to G64 with their PSSs; its four stations'
     * controls; the DC grid's lines. */
    @Test
    void ieee68Dc(@TempDir Path tempDir) {
        Network network = bench("ieee68-dc");
        PscadCircuit circuit = circuit(network, tempDir);

        for (int bus = 53; bus <= 68; bus++) {
            Generator generator = network.getGenerator("G" + bus);
            assertSubtransientWithTgov1(generator);
            if (bus <= 64) {
                assertThat(generator.getExtension(PssModel.class).isEnabled()).as("G%d PSS", bus).isTrue();
            }
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS16_CTRL", "CS33_CTRL", "CS41_CTRL", "CS52_CTRL");
        assertDcLinesWritten(network, circuit);
        assertThat(circuit.singlePhaseNodes()).contains("BD16", "BD33", "BD41", "BD52");
    }

    /** PROMOTioN: its five MMCs and their controls, its cables. */
    @Test
    void promotionDc(@TempDir Path tempDir) {
        Network network = bench("promotion-dc");
        PscadCircuit circuit = circuit(network, tempDir);

        for (String station : List.of("A1", "B1", "C2", "D1", "D2")) {
            Object model = network.getVoltageSourceConverter("C" + station).getExtension(VoltageSourceConverterModel.class);
            assertThat(model).as("C%s", station).isInstanceOf(AveragedMmcModel.class);
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CA1_CTRL", "CB1_CTRL", "CC2_CTRL", "CD1_CTRL", "CD2_CTRL");
        assertDcLinesWritten(network, circuit);
        assertThat(circuit.singlePhaseNodes()).contains("BA1", "BB1", "BC2", "BD1", "BD2");
    }
}
