/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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
import static org.assertj.core.api.Assertions.within;

/**
 * CIGRE B4's DC grid test system, whole ({@link CigreB4Network}), without PSCAD: its flat files, its load flow against DIgSILENT's,
 * the circuit PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class CigreB4NetworkTest {

    /** The flat file is what {@link CigreB4Network#create} builds: the same elements, the same converters' setpoints. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = CigreB4Network.create();
        Network read = CigreB4Network.load();

        assertThat(read.getBusBreakerView().getBusStream().map(b -> b.getId()).sorted().toList())
                .isEqualTo(built.getBusBreakerView().getBusStream().map(b -> b.getId()).sorted().toList());
        assertThat(read.getDcLineCount()).isEqualTo(built.getDcLineCount()).isEqualTo(15);
        assertThat(read.getLineCount()).isEqualTo(built.getLineCount()).isEqualTo(8);
        built.getVoltageSourceConverters().forEach(converter -> {
            assertThat(read.getVoltageSourceConverter(converter.getId()).getControlMode()).as(converter.getId()).isEqualTo(converter.getControlMode());
            assertThat(read.getVoltageSourceConverter(converter.getId()).getTargetP()).as(converter.getId()).isEqualTo(converter.getTargetP());
        });
    }

    /**
     * The load flow the pipeline runs: its offshore system C fed by two converters, CMC1 grid-forming, the island's slack, CBC2 on its
     * own 600 MW; DCS3's and DCS1's DC voltages within 0.0011 pu of DIgSILENT's load flow of the brochure's network - DCS2's further
     * off, its DC/DC converter Cd-E1 left out.
     */
    @Test
    void theLoadFlowIsDigsilentsOnDcs1AndDcs3() {
        Network network = CigreB4Network.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        // System C: its two wind parks' 1000 MW, 600 of them to CBC2, the rest less the cable's losses to CMC1.
        for (String park : List.of("WTC1", "WTC2")) {
            assertThat(network.getGenerator(park).getTerminal().getP()).isCloseTo(-500.0, within(1.0e-6));
        }
        assertThat(network.getVoltageSourceConverter("CBC2").getTerminal1().getP()).isCloseTo(600.0, within(1.0e-3));
        assertThat(network.getVoltageSourceConverter("CMC1").getTargetP()).isCloseTo(392.53, within(0.01));
        // Onshore, DCS3's droops on their curves, short of their references.
        assertThat(network.getVoltageSourceConverter("CBB1").getTerminal1().getP()).isCloseTo(-1510.03, within(0.01));
        assertThat(network.getVoltageSourceConverter("CBB2").getTerminal1().getP()).isCloseTo(-1545.44, within(0.01));
        // The DC voltages, pu of 400 kV, against DIgSILENT's: DCS3's A1 on its 1.01 pu, DCS1's C1.
        String[] nodes = {"BBA1", "BBB1", "BBB2", "BBB4", "BBC2", "BBD1", "BBE1", "BMC1"};
        double[] published = {1.0100, 1.0002, 0.9861, 0.9947, 1.0138, 1.0135, 1.0065, 1.0108};
        for (int i = 0; i < nodes.length; i++) {
            assertThat(network.getDcNode(nodes[i]).getV() / 400.0).as(nodes[i]).isCloseTo(published[i], within(1.1e-3));
        }
    }

    /**
     * On PSCAD Professional: its eleven stations, each its controls; DCS3's node BBE1 a junction of two cables, no station on it; its
     * double lines A1-B1 and B2-B4 each two lines; the DC nodes single-phase; its meshed AC lines and system C's cable written.
     */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(CigreB4Network.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

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
}
