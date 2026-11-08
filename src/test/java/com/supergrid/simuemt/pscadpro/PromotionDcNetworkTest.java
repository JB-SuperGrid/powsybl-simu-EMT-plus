/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.hvdc.AveragedMmcModel;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
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
 * PROMOTioN's five-terminal benchmark network ({@link PromotionDcNetwork}), without PSCAD: its flat files, its load flow, the circuit
 * PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PromotionDcNetworkTest {

    /** The flat file is what {@link PromotionDcNetwork#create} builds. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = PromotionDcNetwork.create();
        Network read = PromotionDcNetwork.load();

        assertThat(ids(read)).isEqualTo(ids(built));
        assertThat(read.getDcLineCount()).isEqualTo(5);
        assertThat(read.getDcSwitchCount()).isEqualTo(10);
        assertThat(read.getVoltageSourceConverterCount()).isEqualTo(5);
        assertThat(read.getGeneratorCount()).isEqualTo(5);
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /**
     * Its load flow, as the pipeline runs it: the wind parks' 2700 MW, D5.1's; B1 on its droop, 1608 MW at its 644.3 kV - D5.1's
     * 1600 -; A1 holding 640 kV, the rest, 1065 MW; 27 MW lost in the cables.
     */
    @Test
    void itsLoadFlowSolves() {
        Network network = PromotionDcNetwork.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        assertThat(network.getVoltageSourceConverter("CC2").getTerminal1().getP()).isCloseTo(600.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CD1").getTerminal1().getP()).isCloseTo(1200.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CD2").getTerminal1().getP()).isCloseTo(900.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CB1").getTerminal1().getP()).isCloseTo(-1608.50, within(0.01));
        assertThat(network.getVoltageSourceConverter("CA1").getTerminal1().getP()).isCloseTo(-1064.61, within(0.01));
        assertThat(network.getDcNode("BA1").getV()).isCloseTo(640.0, within(1.0e-6));
        assertThat(network.getDcNode("BB1").getV()).isCloseTo(644.34, within(0.01));
        assertThat(network.getDcNode("BD1").getV()).isCloseTo(649.75, within(0.01));
    }

    /** On PSCAD Professional: its five MMCs and their controls, its five cables. */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(PromotionDcNetwork.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

        for (String station : List.of("A1", "B1", "C2", "D1", "D2")) {
            Object model = network.getVoltageSourceConverter("C" + station).getExtension(VoltageSourceConverterModel.class);
            assertThat(model).as("C%s", station).isInstanceOf(AveragedMmcModel.class);
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CA1_CTRL", "CB1_CTRL", "CC2_CTRL", "CD1_CTRL", "CD2_CTRL");
        for (PromotionDcNetwork.Cable cable : PromotionDcNetwork.CABLES) {
            assertThat(circuit.components().stream().filter(c -> cable.id().equals(c.parameters().get("Name")))).as(cable.id()).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BA1", "BB1", "BC2", "BD1", "BD2");
    }
}
