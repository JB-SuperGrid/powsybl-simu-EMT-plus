/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.extensions.pss.PssModel;
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
 * The IEEE 68-bus system with its DC grid ({@link Ieee68DcNetwork}), without PSCAD: its flat files, its AC network's load flow
 * against PST's, the whole network's, the circuit PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class Ieee68DcNetworkTest {

    /** The flat file is what {@link Ieee68DcNetwork#create} builds: the data's 86 branches, 3 of them second circuits. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = Ieee68DcNetwork.create();
        Network read = Ieee68DcNetwork.load();

        assertThat(ids(read)).isEqualTo(ids(built));
        assertThat(read.getLineCount()).isEqualTo(built.getLineCount()).isEqualTo(66);
        assertThat(read.getTwoWindingsTransformerCount()).isEqualTo(built.getTwoWindingsTransformerCount()).isEqualTo(20 + 4);
        assertThat(read.getGeneratorCount()).isEqualTo(16);
        assertThat(read.getDcLineCount()).isEqualTo(5);
        assertThat(read.getVoltageSourceConverterCount()).isEqualTo(4);
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /** {@code network}'s AC network alone: its DC grid and its stations taken off. */
    private static Network acAlone() {
        Network network = Ieee68DcNetwork.create();
        List.copyOf(network.getVoltageSourceConverterStream().toList()).forEach(c -> c.remove());
        List.copyOf(network.getDcLineStream().toList()).forEach(l -> l.remove());
        List.copyOf(network.getDcGroundStream().toList()).forEach(g -> g.remove());
        List.copyOf(network.getDcNodeStream().toList()).forEach(n -> n.remove());
        List.of("TC16", "TC33", "TC41", "TC52").forEach(t -> network.getTwoWindingsTransformer(t).remove());
        return network;
    }

    /**
     * Its AC network alone, the DC grid taken off, is PST's load flow: the slack G65 on the data's 3591 MW - the swing bus's
     * solved power PST's file carries - without the resistance its branches are given; 32 MW more with it.
     */
    @Test
    void theAcNetworkAloneIsPstsLoadFlow() {
        Network network = acAlone();
        network.getLines().forEach(line -> {
            if (Math.abs(line.getR() - line.getX() / Ieee68DcNetwork.WINDING_X_OVER_R) < 1.0e-9) {
                line.setR(0.0);
            }
        });
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            if (Math.abs(transformer.getR() - transformer.getX() / Ieee68DcNetwork.WINDING_X_OVER_R) < 1.0e-9) {
                transformer.setR(0.0);
            }
        }
        assertThat(LoadFlow.run(network).isFullyConverged()).isTrue();
        assertThat(-network.getGenerator("G65").getTerminal().getP()).isCloseTo(3591.0, within(0.01));

        Network withResistance = acAlone();
        assertThat(LoadFlow.run(withResistance).isFullyConverged()).isTrue();
        assertThat(-withResistance.getGenerator("G65").getTerminal().getP()).isCloseTo(3622.84, within(0.01));
    }

    /**
     * The whole network's load flow, as the pipeline runs it: CS33 on its droop, 335 MW into New York at its 396.7 kV; CS41 307 MW
     * out of area 3 at 399.5 kV; CS52 252 MW into area 5 at 396.8 kV; CS16 holding the DC voltage, 284 MW out of New England;
     * G65, the slack, 3631 MW.
     */
    @Test
    void theWholeNetworksLoadFlowSolves() {
        Network network = Ieee68DcNetwork.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        assertThat(network.getVoltageSourceConverter("CS16").getTerminal1().getP()).isCloseTo(284.23, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS33").getTerminal1().getP()).isCloseTo(-334.68, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS41").getTerminal1().getP()).isCloseTo(306.80, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS52").getTerminal1().getP()).isCloseTo(-251.91, within(0.01));
        assertThat(network.getDcNode("BD16").getV()).isCloseTo(400.0, within(1.0e-6));
        assertThat(network.getDcNode("BD33").getV()).isCloseTo(396.73, within(0.01));
        assertThat(network.getDcNode("BD41").getV()).isCloseTo(399.55, within(0.01));
        assertThat(network.getDcNode("BD52").getV()).isCloseTo(396.79, within(0.01));
        assertThat(-network.getGenerator("G65").getTerminal().getP()).isCloseTo(3631.21, within(0.01));
    }

    /**
     * On PSCAD Professional: its sixteen machines, PST's, each with its exciter and a TGOV1, G53 to G64 with their PSSs; its four
     * stations' controls; the DC grid's five lines.
     */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(Ieee68DcNetwork.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

        for (int bus = 53; bus <= 68; bus++) {
            Generator generator = network.getGenerator("G" + bus);
            Object model = generator.getExtension(GeneratorModel.class);
            Object governor = generator.getExtension(TurbineGovernorModel.class);
            assertThat(model).as("G%d", bus).isInstanceOf(SubtransientMachineModel.class);
            assertThat(governor).as("G%d governor", bus).isInstanceOf(Tgov1Model.class);
            PssModel pss = generator.getExtension(PssModel.class);
            if (bus <= 64) {
                assertThat(pss.isEnabled()).as("G%d PSS", bus).isTrue();
            }
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS16_CTRL", "CS33_CTRL", "CS41_CTRL", "CS52_CTRL");
        for (Ieee68DcNetwork.DcLine line : Ieee68DcNetwork.DC_LINES) {
            assertThat(circuit.components().stream().filter(c -> line.id().equals(c.parameters().get("Name")))).as(line.id()).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BD16", "BD33", "BD41", "BD52");
    }
}
