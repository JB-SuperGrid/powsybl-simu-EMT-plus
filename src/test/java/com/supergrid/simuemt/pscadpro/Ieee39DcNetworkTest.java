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
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.governor.Tgov1Model;
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

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The IEEE 39-bus system with its DC grid ({@link Ieee39DcNetwork}), without PSCAD: its flat files, its AC network's load flow
 * against MATPOWER's solution, the whole network's, the circuit PSCAD's writer makes of it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class Ieee39DcNetworkTest {

    /** The flat file is what {@link Ieee39DcNetwork#create} builds. */
    @Test
    void theFlatFileIsTheBuildersNetwork() {
        Network built = Ieee39DcNetwork.create();
        Network read = Ieee39DcNetwork.load();

        assertThat(ids(read)).isEqualTo(ids(built));
        assertThat(read.getLineCount()).isEqualTo(built.getLineCount()).isEqualTo(34);
        assertThat(read.getTwoWindingsTransformerCount()).isEqualTo(built.getTwoWindingsTransformerCount()).isEqualTo(16);
        assertThat(read.getGeneratorCount()).isEqualTo(11);
        assertThat(read.getDcLineCount()).isEqualTo(5);
        assertThat(read.getVoltageSourceConverterCount()).isEqualTo(4);
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /** {@code network}'s AC network alone: its DC grid, its stations and its wind park taken off. */
    private static Network acAlone() {
        Network network = Ieee39DcNetwork.create();
        List.copyOf(network.getVoltageSourceConverterStream().toList()).forEach(c -> c.remove());
        List.copyOf(network.getDcLineStream().toList()).forEach(l -> l.remove());
        List.copyOf(network.getDcGroundStream().toList()).forEach(g -> g.remove());
        List.copyOf(network.getDcNodeStream().toList()).forEach(n -> n.remove());
        List.of("TC4", "TC16", "TC26", "TCW").forEach(t -> network.getTwoWindingsTransformer(t).remove());
        network.getGenerator("WTW").remove();
        return network;
    }

    /** The largest gaps between {@code network}'s buses and MATPOWER's solution: |V| in pu, the angle in degrees from bus 31's. */
    private static double[] gaps(Network network) {
        assertThat(LoadFlow.run(network).isFullyConverged()).isTrue();
        double angle31 = network.getBusBreakerView().getBus("B" + Ieee39DcNetwork.SLACK).getAngle();
        double dv = 0.0;
        double da = 0.0;
        for (double[] published : Ieee39DcNetwork.BUSES) {
            Bus bus = network.getBusBreakerView().getBus("B" + (int) published[0]);
            dv = Math.max(dv, Math.abs(bus.getV() / bus.getVoltageLevel().getNominalV() - published[3]));
            da = Math.max(da, Math.abs(bus.getAngle() - angle31 - published[4]));
        }
        return new double[] {dv, da};
    }

    /**
     * Its AC network alone, the DC grid taken off, is MATPOWER's solution: exactly - within 1e-5 pu and 1e-4 degree, G31 on its
     * 677.87 MW - without the resistance its transformer windings are given; with it, within 0.0025 pu and 0.12 degree, G31 6 MW up.
     */
    @Test
    void theAcNetworkAloneIsMatpowersSolution() {
        Network network = acAlone();
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            if (Math.abs(transformer.getR() - transformer.getX() / Ieee39DcNetwork.WINDING_X_OVER_R) < 1.0e-9) {
                transformer.setR(0.0);
            }
        }
        double[] exact = gaps(network);
        assertThat(exact[0]).isLessThan(1.0e-5);
        assertThat(exact[1]).isLessThan(1.0e-4);
        assertThat(-network.getGenerator("G31").getTerminal().getP()).isCloseTo(677.871, within(0.01));

        Network withResistance = acAlone();
        double[] gaps = gaps(withResistance);
        assertThat(gaps[0]).isLessThan(0.0025);
        assertThat(gaps[1]).isLessThan(0.12);
        assertThat(-withResistance.getGenerator("G31").getTerminal().getP()).isCloseTo(683.9, within(0.1));
    }

    /**
     * The whole network's load flow, as the pipeline runs it: CS26 on its droop, 190 MW from the north at its 401.4 kV; CS4 on its
     * own, 327 MW into the load centre at 398.2 kV; CS16 holding the DC voltage, delivering the rest; CSW its island's slack, the
     * wind park's 400 MW less its losses; G31, the slack, down to 290 MW.
     */
    @Test
    void theWholeNetworksLoadFlowSolves() {
        Network network = Ieee39DcNetwork.load();

        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());

        assertThat(network.getGenerator("WTW").getTerminal().getP()).isCloseTo(-400.0, within(1.0e-6));
        assertThat(network.getVoltageSourceConverter("CSW").getTerminal1().getP()).isCloseTo(398.06, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS26").getTerminal1().getP()).isCloseTo(189.80, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS4").getTerminal1().getP()).isCloseTo(-327.20, within(0.01));
        assertThat(network.getVoltageSourceConverter("CS16").getTerminal1().getP()).isCloseTo(-256.42, within(0.01));
        assertThat(network.getDcNode("BD16").getV()).isCloseTo(400.0, within(1.0e-6));
        assertThat(network.getDcNode("BD4").getV()).isCloseTo(398.18, within(0.01));
        assertThat(network.getDcNode("BD26").getV()).isCloseTo(401.36, within(0.01));
        assertThat(-network.getGenerator("G31").getTerminal().getP()).isCloseTo(289.6, within(0.1));
    }

    /**
     * On PSCAD Professional: its ten machines, Athay's, each with its exciter and a TGOV1; its four stations' controls and its wind
     * park's; the DC grid's five lines.
     */
    @Test
    void pscadWritesItsWholeCircuit(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(Ieee39DcNetwork.load());
        PscadCircuit circuit = PscadFileWriter.write(network, tempDir, new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL))
                .circuit();

        for (int bus = 30; bus <= 39; bus++) {
            Generator generator = network.getGenerator("G" + bus);
            Object model = generator.getExtension(GeneratorModel.class);
            Object governor = generator.getExtension(TurbineGovernorModel.class);
            assertThat(model).as("G%d", bus).isInstanceOf(SubtransientMachineModel.class);
            assertThat(governor).as("G%d governor", bus).isInstanceOf(Tgov1Model.class);
        }
        assertThat(circuit.components().stream().map(PscadComponent::definition).filter(d -> d.endsWith("_CTRL")))
                .contains("CS4_CTRL", "CS16_CTRL", "CS26_CTRL", "CSW_CTRL", "WTW_CTRL");
        for (String line : List.of("D4_16", "D16_26", "D26_4", "DW_26", "DW_16")) {
            assertThat(circuit.components().stream().filter(c -> line.equals(c.parameters().get("Name")))).as(line).hasSize(1);
        }
        assertThat(circuit.singlePhaseNodes()).contains("BD4", "BD16", "BD26", "BDW");
    }
}
