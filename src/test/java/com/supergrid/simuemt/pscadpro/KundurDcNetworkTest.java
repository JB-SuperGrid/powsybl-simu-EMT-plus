/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Kundur's two-area system with its DC grid ({@link KundurDcNetwork}), without PSCAD: its flat files, its AC network's load flow
 * against the benchmark's own, the whole network's, the circuit PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class KundurDcNetworkTest {

    /** The flat file is what {@link KundurDcNetwork#create} builds. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = KundurDcNetwork.create();
        Network read = KundurDcNetwork.load();

        assertThat(ids(read)).isEqualTo(ids(built));
        assertThat(read.getLineCount()).isEqualTo(built.getLineCount()).isEqualTo(8);
        assertThat(read.getTwoWindingsTransformerCount()).isEqualTo(built.getTwoWindingsTransformerCount()).isEqualTo(7);
        assertThat(read.getDcLineCount()).isEqualTo(3);
        assertThat(read.getVoltageSourceConverterCount()).isEqualTo(3);
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /** The benchmark's load flow: each machine's reactive power, Mvar, and its terminal's angle, degrees, G3's -6.8. */
    private static final Map<String, double[]> PUBLISHED = Map.of(
            "G1", new double[] {185.0, 20.2},
            "G2", new double[] {235.0, 10.5},
            "G3", new double[] {176.0, -6.8},
            "G4", new double[] {202.0, -17.0});

    /**
     * Its AC network alone, the DC grid taken off: the benchmark's load flow - each machine's reactive power within 5 Mvar, its
     * angle within 0.4 degree of the data's, from G3's; G3's power, 719 MW in the data, 8 MW up on the losses of its transformers'
     * resistance, the only data the benchmark has not; the tie's 400 MW.
     */
    @Test
    void theAcNetworkAloneIsTheBenchmarksLoadFlow() {
        Network network = KundurDcNetwork.create();
        List.copyOf(network.getVoltageSourceConverterStream().toList()).forEach(c -> c.remove());
        List.copyOf(network.getDcLineStream().toList()).forEach(l -> l.remove());
        List.copyOf(network.getDcGroundStream().toList()).forEach(g -> g.remove());
        List.copyOf(network.getDcNodeStream().toList()).forEach(n -> n.remove());
        network.getGenerator("WTW").remove();

        assertThat(LoadFlow.run(network).isFullyConverged()).isTrue();

        double angle3 = network.getBusBreakerView().getBus("B3").getAngle();
        PUBLISHED.forEach((id, published) -> {
            Generator generator = network.getGenerator(id);
            Bus bus = generator.getTerminal().getBusBreakerView().getBus();
            assertThat(-generator.getTerminal().getQ()).as("Q %s", id).isCloseTo(published[0], within(5.0));
            assertThat(bus.getAngle() - angle3 - 6.8).as("angle %s", id).isCloseTo(published[1], within(0.4));
        });
        assertThat(-network.getGenerator("G3").getTerminal().getP()).isCloseTo(727.0, within(1.0));
        assertThat(network.getLine("L7_8a").getTerminal1().getP() + network.getLine("L7_8b").getTerminal1().getP())
                .isCloseTo(400.0, within(5.0));
    }

    /**
     * The whole network's load flow, as the pipeline runs it: CS7 on its droop, 184 MW from area 1 at its 402 kV; CS9 holding the
     * DC voltage, delivering that and the wind park's 200 MW, less the losses, to area 2; CSW its island's slack; the AC tie down to
     * 213 MW, G3's production to 502 MW.
     */
    @Test
    void theWholeNetworksLoadFlowSolves() {
        Network network = KundurDcNetwork.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        assertThat(network.getGenerator("WTW").getTerminal().getP()).isCloseTo(-200.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CSW").getTerminal1().getP()).isCloseTo(199.03, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS7").getTerminal1().getP()).isCloseTo(183.96, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS9").getTerminal1().getP()).isCloseTo(-381.05, within(0.01));
        assertThat(network.getDcNode("BD9").getV()).isCloseTo(400.0, within(1.0e-6));
        assertThat(network.getDcNode("BD7").getV()).isCloseTo(402.14, within(0.01));
        assertThat(-network.getGenerator("G3").getTerminal().getP()).isCloseTo(502.2, within(0.1));
        assertThat(network.getLine("L7_8a").getTerminal1().getP() + network.getLine("L7_8b").getTerminal1().getP())
                .isCloseTo(213.3, within(0.1));
    }

    /**
     * On PSCAD Professional: its four machines, Kundur's, each with its exciter, its PSS and a TGOV1; its three stations' controls
     * and its wind park's; the DC grid's three lines.
     */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(KundurDcNetwork.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

        for (int machine : KundurDcNetwork.MACHINES) {
            Generator generator = network.getGenerator("G" + machine);
            Object model = generator.getExtension(GeneratorModel.class);
            PssModel pss = generator.getExtension(PssModel.class);
            Object governor = generator.getExtension(TurbineGovernorModel.class);
            assertThat(model).as("G%d", machine).isInstanceOf(SubtransientMachineModel.class);
            assertThat(pss.isEnabled()).as("G%d PSS", machine).isTrue();
            assertThat(governor).as("G%d governor", machine).isInstanceOf(Tgov1Model.class);
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS7_CTRL", "CS9_CTRL", "CSW_CTRL", "WTW_CTRL");
        for (String line : List.of("D7_9", "DW_7", "DW_9")) {
            assertThat(circuit.components().stream().filter(c -> line.equals(c.parameters().get("Name")))).as(line).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BD7", "BD9", "BDW");
    }
}
