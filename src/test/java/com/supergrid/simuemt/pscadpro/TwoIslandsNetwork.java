/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.LoadFlowRunParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.simuemt.extensions.EmtLoadFlow;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;

/**
 * Two passive islands no branch joins, each fed from its own infinite bus: B1, a 400 kV line to B2 and its load, as upstream's
 * two-bus bench; and beside it B3, a 400 kV line to B4, a 400/225 kV transformer to B5 and its load. PSCAD Professional solves each
 * island as a subsystem of its own - what a passive network's snapshot, patched with its steady state, is split into there.
 *
 * <p>Its load flow is OpenLoadFlow's on every island ({@link #withLoadFlow}): upstream's ({@code ExampleNetworks#withLoadFlow}) solves
 * the main one only.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class TwoIslandsNetwork {

    private TwoIslandsNetwork() {
    }

    /** The bench, without its load flow. */
    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("two-islands", "test");
        island(network, "1", "2", null, 4.0, 40.0, 100.0, 400.0, 100.0, 20.0);
        island(network, "3", "4", "5", 2.0, 30.0, 60.0, 405.0, 60.0, 15.0);
        return network;
    }

    /** {@link #create()} with its load flow on both islands. */
    static Network loaded() {
        return withLoadFlow(create());
    }

    /** {@code network} once OpenLoadFlow has solved every island, as upstream's own parameters have it otherwise; refused unless it converged. */
    static Network withLoadFlow(Network network) {
        LoadFlowParameters parameters = new LoadFlowParameters().setComponentMode(LoadFlowParameters.ComponentMode.ALL_CONNECTED);
        OpenLoadFlowParameters.create(parameters).setSlackBusPMaxMismatch(EmtLoadFlow.SLACK_BUS_MAX_MISMATCH_MW)
                .setNewtonRaphsonConvEpsPerEq(EmtLoadFlow.newtonRaphsonConvEpsPerEq(network));
        LoadFlowResult result = LoadFlow.find("OpenLoadFlow").run(network,
                LoadFlowRunParameters.getDefault().setParameters(parameters).setComputationManager(LocalComputationManager.getDefault()));
        if (!result.isFullyConverged()) {
            throw new IllegalStateException("two-islands: the load flow did not converge, " + result.getStatus());
        }
        return network;
    }

    /** An island from bus {@code from} through a line to bus {@code to}, and through a transformer to bus {@code behind} if any. */
    private static void island(Network network, String from, String to, String behind, double lineR, double lineX, double generation,
                               double targetKv, double load, double reactiveLoad) {
        Substation sending = network.newSubstation().setId("S" + from).add();
        Substation receiving = network.newSubstation().setId("S" + to).add();
        VoltageLevel vl1 = sending.newVoltageLevel().setId("VL" + from).setNominalV(400.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        VoltageLevel vl2 = receiving.newVoltageLevel().setId("VL" + to).setNominalV(400.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        Bus b1 = vl1.getBusBreakerView().newBus().setId("B" + from).add();
        Bus b2 = vl2.getBusBreakerView().newBus().setId("B" + to).add();
        network.newLine().setId("L" + from + "_" + to)
                .setVoltageLevel1(vl1.getId()).setBus1(b1.getId()).setConnectableBus1(b1.getId())
                .setVoltageLevel2(vl2.getId()).setBus2(b2.getId()).setConnectableBus2(b2.getId())
                .setR(lineR).setX(lineX).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0)
                .add();
        String generator = "GEN" + from;
        vl1.newGenerator().setId(generator)
                .setBus(b1.getId()).setConnectableBus(b1.getId())
                .setMinP(-500.0).setMaxP(500.0).setRatedS(100.0)
                .setTargetP(generation).setLocalTargetV(targetKv).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        network.getGenerator(generator).addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(20000.0, 10.0));
        VoltageLevel loadLevel = vl2;
        Bus loadBus = b2;
        if (behind != null) {
            loadLevel = receiving.newVoltageLevel().setId("VL" + behind).setNominalV(225.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
            loadBus = loadLevel.getBusBreakerView().newBus().setId("B" + behind).add();
            receiving.newTwoWindingsTransformer().setId("T" + to + "_" + behind)
                    .setVoltageLevel1(vl2.getId()).setBus1(b2.getId()).setConnectableBus1(b2.getId())
                    .setVoltageLevel2(loadLevel.getId()).setBus2(loadBus.getId()).setConnectableBus2(loadBus.getId())
                    .setRatedU1(400.0).setRatedU2(225.0).setR(0.2).setX(8.0).setG(0.0).setB(0.0)
                    .add();
        }
        loadLevel.newLoad().setId("LOAD" + from)
                .setBus(loadBus.getId()).setConnectableBus(loadBus.getId())
                .setP0(load).setQ0(reactiveLoad)
                .add();
    }
}
