/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.iidm.network.extensions.ActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.iidm.network.regulation.RegulationMode;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The IEEE 39-bus system with a high share of power electronics, as this project runs it in PSCAD Professional, in six
 * variants of one AC network - {@link Ieee39DcNetwork#createAc}'s, MATPOWER's case39 - to set grid-following against
 * grid-forming converters on the same sources:
 * <ul>
 *   <li>{@code ieee39-sync}, its ten machines, the reference: {@code ieee39-sync.xiidm} and its extensions;</li>
 *   <li>{@code ieee39-gfl} and {@code ieee39-gfm}, four of New England's machines - G30, G32, G35 and G38, 2380 MW, 45 % of its nine
 *   machines' 5300 MW, 38 % with G39's - each with its step-up transformer and its bus replaced by a converter source: one {@code ieee39-ibr.xiidm}, its
 *   extensions {@code ieee39-gfl-extensions.xml} or {@code ieee39-gfm-extensions.xml}, the converters' controls the one
 *   difference;</li>
 *   <li>{@code ieee39-gfl65} and {@code ieee39-gfm65}, the same with six of them, G33 and G36 too ({@link #REPLACED_65}), 67 %:
 *   {@code ieee39-ibr65.xiidm}, {@code ieee39-gfl65-extensions.xml} or {@code ieee39-gfm65-extensions.xml}; and {@code
 *   ieee39-mix65}, grid-following but for CL33, grid-forming, {@code ieee39-mix65-extensions.xml}.</li>
 * </ul>
 *
 * <p>Each converter source is a point-to-point link, this project's own: its station CL.. on the replaced machine's 345 kV
 * bus - its transformer's other side -, delivering the machine's power; its far station CR.. holding the DC voltage on a strong
 * grid of its own, a 400 kV source of 30 GVA, X/R 10, as {@link PromotionDcNetwork}'s onshore grids; between them 100 km of
 * CIGRE B4's +-200 kV OHL as one 400 kV conductor, as {@link Ieee39DcNetwork}'s DC lines. So the source behind the converter is
 * stiff, its DC voltage held, and only the converter's control - following a PLL or forming the voltage - sets what the AC
 * network sees. The stations of 220 kV AC behind YNy transformers of 0.006 + j0.18 pu on their rating, as Ieee39DcNetwork's;
 * the CL stations holding their 220 kV bus at their AC bus's voltage in MATPOWER's solution, as the machine held it, the CR stations none of their reactive power
 * at their PCC.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class Ieee39IbrNetwork {

    /** The reference, its ten machines, from its flat files. */
    static Network loadSync() {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee39-sync.xiidm"), resource("/ieee39-sync-extensions.xml"));
    }

    /** Four machines replaced by converter sources, grid-forming if {@code gridForming}, else grid-following, from its flat files. */
    static Network loadIbr(boolean gridForming) {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee39-ibr.xiidm"),
                resource(gridForming ? "/ieee39-gfm-extensions.xml" : "/ieee39-gfl-extensions.xml"));
    }

    /** Six machines replaced, grid-following but for CL33, grid-forming: the least grid-forming that keeps the network through a
     * fault at bus 16, from its flat files. */
    static Network loadMix65() {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee39-ibr65.xiidm"), resource("/ieee39-mix65-extensions.xml"));
    }

    /** Six machines replaced ({@link #REPLACED_65}), grid-forming if {@code gridForming}, else grid-following, from its flat files. */
    static Network loadIbr65(boolean gridForming) {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee39-ibr65.xiidm"),
                resource(gridForming ? "/ieee39-gfm65-extensions.xml" : "/ieee39-gfl65-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(Ieee39IbrNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** A replaced machine: its bus, its step-up transformer's 345 kV bus, the converter source's rating in MVA. */
    record Replaced(int machineBus, int bus, double ratingMva) {
    }

    static final List<Replaced> REPLACED = List.of(
            new Replaced(30, 2, 300.0),
            new Replaced(32, 10, 800.0),
            new Replaced(35, 22, 800.0),
            new Replaced(38, 29, 1000.0));

    /** Six of them: {@link #REPLACED}'s and G33 and G36 - 3572 MW, 67 % of New England's nine machines' 5300 MW, G31 the slack,
     * G34 and G37 left beside G39. */
    static final List<Replaced> REPLACED_65 = List.of(
            new Replaced(30, 2, 300.0),
            new Replaced(32, 10, 800.0),
            new Replaced(33, 19, 800.0),
            new Replaced(35, 22, 800.0),
            new Replaced(36, 23, 700.0),
            new Replaced(38, 29, 1000.0));

    /** The DC voltage, kV. */
    private static final double DC_KV = 400.0;
    /** The links' length, km. */
    static final double LINK_KM = 100.0;

    /** The reference: MATPOWER's case39, its ten machines. */
    static Network createSync() {
        return Ieee39DcNetwork.createAc("ieee39-sync");
    }

    /** Four machines replaced by converter sources, delivering their power. */
    static Network createIbr() {
        return createIbr("ieee39-ibr", REPLACED);
    }

    /** Six machines replaced by converter sources ({@link #REPLACED_65}), delivering their power. */
    static Network createIbr65() {
        return createIbr("ieee39-ibr65", REPLACED_65);
    }

    private static Network createIbr(String networkId, List<Replaced> replacedMachines) {
        Network network = Ieee39DcNetwork.createAc(networkId);
        for (Replaced replaced : replacedMachines) {
            int m = replaced.machineBus();
            double pMw = Arrays.stream(Ieee39DcNetwork.GENERATORS).filter(g -> (int) g[0] == m).findFirst().orElseThrow()[1];
            network.getGenerator("G" + m).remove();
            network.getTwoWindingsTransformer("T" + replaced.bus() + "_" + m).remove();
            VoltageLevel machineLevel = network.getVoltageLevel("VL" + m);
            machineLevel.getBusBreakerView().removeAllBuses();
            machineLevel.remove();
            double busPu = Arrays.stream(Ieee39DcNetwork.BUSES).filter(b -> (int) b[0] == replaced.bus()).findFirst().orElseThrow()[3];
            addLink(network, m, "B" + replaced.bus(), replaced.ratingMva(), pMw, busPu);
        }
        return network;
    }

    /** One converter source: CL{m} on {@code acBus} delivering {@code pMw}, its 220 kV bus at {@code busPu} - the AC bus's in
     * MATPOWER's solution -, CR{m} on its own strong grid GR{m} holding the DC voltage, the line D{m} between their DC nodes BDL{m}
     * and BDR{m}. */
    private static void addLink(Network network, int m, String acBus, double ratingMva, double pMw, double busPu) {
        String local = "BDL" + m;
        String remote = "BDR" + m;
        network.newDcNode().setId(local).setNominalV(DC_KV).add();
        network.newDcNode().setId(remote).setNominalV(DC_KV).add();
        // A symmetric monopole's two poles in series, 2 R.
        network.newDcLine().setId("D" + m).setDcNode1(remote).setDcNode2(local).setR(2 * Ieee39DcNetwork.DC_OHL.resistance() * LINK_KM).add();

        VoltageLevel ac = network.getBusBreakerView().getBus(acBus).getVoltageLevel();
        VoltageSourceConverter cl = addStation(network, "CL" + m, ac.getSubstation().orElseThrow(), ac, acBus, local, ratingMva);
        cl.setControlMode(AcDcConverter.ControlMode.P_PCC).setTargetP(-pMw);
        cl.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0 * busPu);

        // The far grid: a 400 kV source of 30 GVA, its island's slack within 5000 MW either way.
        Substation far = network.newSubstation().setId("SR" + m).add();
        VoltageLevel grid = far.newVoltageLevel().setId("VR" + m).setNominalV(400.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        grid.getBusBreakerView().newBus().setId("BR" + m).add();
        Generator source = grid.newGenerator()
                .setId("GR" + m)
                .setBus("BR" + m).setConnectableBus("BR" + m)
                .setMinP(-5000.0).setMaxP(5000.0).setRatedS(30000.0)
                .setTargetP(pMw).setLocalTargetV(400.0).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        source.newExtension(ActivePowerControlAdder.class).withParticipate(true).withDroop(4.0).add();
        VoltageSourceConverter cr = addStation(network, "CR" + m, far, grid, "BR" + m, remote, ratingMva);
        cr.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(DC_KV);
    }

    /** One station, as {@link Ieee39DcNetwork}'s: its transformer TC.. from {@code acBus} to its converter's 220 kV bus BC.., the
     * converter between {@code dcNode} and its earthed return {@code dcNode}G, P_PCC at none, no reactive power at its PCC,
     * reactive limits of 0.5 pu of its rating. */
    private static VoltageSourceConverter addStation(Network network, String id, Substation substation, VoltageLevel ac, String acBus,
                                                     String dcNode, double ratingMva) {
        String suffix = id.substring(1);
        VoltageLevel valve = substation.newVoltageLevel().setId("VC" + suffix).setNominalV(220.0).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        valve.getBusBreakerView().newBus().setId("BC" + suffix).add();
        double baseOhm = 220.0 * 220.0 / ratingMva;
        TwoWindingsTransformer transformer = substation.newTwoWindingsTransformer()
                .setId("TC" + suffix)
                .setVoltageLevel1(ac.getId()).setBus1(acBus).setConnectableBus1(acBus)
                .setVoltageLevel2(valve.getId()).setBus2("BC" + suffix).setConnectableBus2("BC" + suffix)
                .setRatedU1(ac.getNominalV()).setRatedU2(220.0).setRatedS(ratingMva)
                .setR(0.006 * baseOhm).setX(0.18 * baseOhm).setG(0.0).setB(0.0)
                .add();
        transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withRz(transformer.getR()).withXz(transformer.getX())
                .withFreeFluxes(true).withConnectionType1(WindingConnectionType.Y_GROUNDED).withConnectionType2(WindingConnectionType.Y).add();
        network.newDcNode().setId(dcNode + "G").setNominalV(DC_KV).add();
        network.newDcGround().setId("DG" + suffix).setDcNode(dcNode + "G").setR(0.0).add();
        VoltageSourceConverter converter = valve.newVoltageSourceConverter()
                .setId(id)
                .setBus1("BC" + suffix).setConnectableBus1("BC" + suffix)
                .setDcNode1(dcNode).setDcNode2(dcNode + "G")
                .setPccTerminal(transformer.getTerminal1())
                .setControlMode(AcDcConverter.ControlMode.P_PCC).setTargetVdc(DC_KV).setTargetP(0.0)
                .setLocalTargetV(220.0)
                .newVoltageRegulation().withMode(RegulationMode.REACTIVE_POWER).withTargetValue(0.0).withTerminal(transformer.getTerminal1()).add()
                .add();
        converter.newMinMaxReactiveLimits().setMinQ(-0.5 * ratingMva).setMaxQ(0.5 * ratingMva).add();
        return converter;
    }

    private Ieee39IbrNetwork() {
    }
}
