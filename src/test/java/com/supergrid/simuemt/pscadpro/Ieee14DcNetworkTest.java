/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.ieeecdf.model.IeeeCdfModel;
import com.powsybl.ieeecdf.model.IeeeCdfReader;
import com.powsybl.ieeecdf.model.elements.IeeeCdfBus;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.governor.TurbineGovernorModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadComponent;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The IEEE 14-bus test system with its DC grid ({@link Ieee14DcNetwork}), without PSCAD: its flat files, its AC network's load flow
 * against the case's own solution, the whole network's, the circuit PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class Ieee14DcNetworkTest {

    /** The flat file is what {@link Ieee14DcNetwork#create} builds. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = Ieee14DcNetwork.create();
        Network read = Ieee14DcNetwork.load();

        assertThat(ids(read)).isEqualTo(ids(built));
        assertThat(read.getLineCount()).isEqualTo(built.getLineCount()).isEqualTo(17);
        assertThat(read.getTwoWindingsTransformerCount()).isEqualTo(built.getTwoWindingsTransformerCount()).isEqualTo(7);
        assertThat(read.getDcLineCount()).isEqualTo(5);
        assertThat(read.getVoltageSourceConverterCount()).isEqualTo(4);
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /**
     * Its AC network alone, the DC grid taken off: the case's own solution, on its kV - every bus within 0.001 pu and 0.06 degree of
     * the archive's, angles from bus 1's; its transformer windings' resistance, X/R 50, the only data the case has not.
     */
    @Test
    void theAcNetworkAloneIsTheCasesSolution() throws Exception {
        Network network = Ieee14DcNetwork.create();
        List.copyOf(network.getVoltageSourceConverterStream().toList()).forEach(c -> c.remove());
        List.copyOf(network.getDcLineStream().toList()).forEach(l -> l.remove());
        List.copyOf(network.getDcGroundStream().toList()).forEach(g -> g.remove());
        List.copyOf(network.getDcNodeStream().toList()).forEach(n -> n.remove());
        network.getGenerator("WTW").remove();

        LoadFlow.run(network);

        IeeeCdfModel cdf;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(IeeeCdfModel.class.getResourceAsStream("/ieee14cdf.txt"),
                StandardCharsets.UTF_8))) {
            cdf = new IeeeCdfReader().read(reader);
        }
        double angle1 = network.getBusBreakerView().getBus("B1").getAngle();
        for (IeeeCdfBus published : cdf.getBuses()) {
            Bus bus = network.getBusBreakerView().getBus("B" + published.getNumber());
            assertThat(bus.getV() / bus.getVoltageLevel().getNominalV()).as("|V| B%d", published.getNumber())
                    .isCloseTo(published.getFinalVoltage(), within(1.0e-3));
            assertThat(bus.getAngle() - angle1).as("angle B%d", published.getNumber()).isCloseTo(published.getFinalAngle(), within(0.06));
        }
    }

    /**
     * The whole network's load flow, as the pipeline runs it: the wind park's 150 MW to the AC side through CS3 and CS9, on their
     * droops; CSW its island's slack; G1 the AC network's, its production down to some 74 MW.
     */
    @Test
    void theWholeNetworksLoadFlowSolves() {
        Network network = Ieee14DcNetwork.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        assertThat(network.getGenerator("WTW").getTerminal().getP()).isCloseTo(-150.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CSW").getTerminal1().getP()).isCloseTo(149.32, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS3").getTerminal1().getP()).isCloseTo(-117.75, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS9").getTerminal1().getP()).isCloseTo(-59.71, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS2").getTerminal1().getP()).isCloseTo(28.44, within(0.01));
        assertThat(-network.getGenerator("G1").getTerminal().getP()).isCloseTo(73.5, within(0.1));
        assertThat(network.getDcNode("BD2").getV()).isCloseTo(400.0, within(1.0e-6));
    }

    /**
     * On PSCAD Professional: its five machines, Dynawo's, each its controls - G1 and G2 a governor, the synchronous condensers none;
     * its four stations' controls and its wind park's; the DC grid's triangle and the wind park's two cables.
     */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(Ieee14DcNetwork.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

        for (String machine : List.of("G1", "G2", "G3", "G6", "G8")) {
            Object model = network.getGenerator(machine).getExtension(GeneratorModel.class);
            assertThat(model).as(machine).isInstanceOf(SubtransientMachineModel.class);
        }
        assertThat(network.getGeneratorStream().filter(g -> g.getExtension(TurbineGovernorModel.class) != null).map(g -> g.getId()).toList())
                .containsExactlyInAnyOrder("G1", "G2");
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS2_CTRL", "CS3_CTRL", "CS9_CTRL", "CSW_CTRL", "WTW_CTRL");
        for (String line : List.of("D2_3", "D3_9", "D2_9", "DW_2", "DW_9")) {
            assertThat(circuit.components().stream().filter(c -> line.equals(c.parameters().get("Name")))).as(line).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BD2", "BD3", "BD9", "BDW");
    }
}
