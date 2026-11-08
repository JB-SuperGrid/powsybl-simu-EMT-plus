/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.DcSwitchKind;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.NetworkFactory;
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
import java.util.List;
import java.util.Objects;

/**
 * PROMOTioN's five-terminal meshed offshore HVDC benchmark network, as this project runs it in PSCAD Professional: {@link #create}
 * builds it flat, {@code promotion-dc.xiidm} is what it writes, {@code promotion-dc-extensions.xml} its EMT models; {@link #load}
 * reads both.
 *
 * <p>The network is the one PROMOTioN's deliverable D5.1, <i>HVDC Network Fault Analysis</i> (2017, its section 3.2, figures 9 and
 * 13, tables 3 and 4), defines for its DC fault studies, after CIGRE B4-57's and B4-58's test network: two onshore stations, A1 and
 * B1, and three offshore ones, C2, D1 and D2, each collecting a wind park, on +-320 kV bipoles of half-bridge MMCs, five submarine
 * cables between them:
 * <pre>
 *   A1 (onshore, 1600 MVA) V_DC 640 kV       A1 ==200 km== C2 ==150 km== D1 ==150 km== D2 ==150 km== B1 ==250 km== D1
 *   B1 (onshore, 1600 MVA) droop, 1600 MW at 644 kV
 *   C2 (offshore, 800 MVA)  600 MW            the onshore grids 30 GVA at 400 kV, the offshore ones 3.8 GVA at 145 kV
 *   D1 (offshore, 1600 MVA) 1200 MW
 *   D2 (offshore, 1200 MVA) 900 MW
 * </pre>
 * Its powers D5.1's figure 13's: the wind parks' 2700 MW, B1 delivering 1600 MW, A1 - the DC voltage's - the rest. Each AC grid,
 * as D5.1 has them, a source behind the impedance of its short-circuit power, X/R 10; each station's transformer, 18 % and 0.6 % on
 * its rating, as D5.1's, to its converter's 333 kV: not D9.1's 380 kV, which leaves an averaged half-bridge MMC, its AC voltage's
 * crest at most half its DC voltage, no margin at 640 kV beyond its arms' drop - run so in PSCAD, its DC voltage rose 23 % until it
 * had one; at 333 kV its modulation index about 0.91, as the project's other stations' 220 kV at 400 kV.
 *
 * <p>Ours, where D5.1 gives none or the backend has none: its bipoles balanced, each as one 640 kV conductor - its two poles in
 * series, as a symmetric monopole's, its neutral carrying nothing -, so that no pole-to-ground fault of a bipole, D5.1's own, can be
 * studied here, only pole-to-pole ones; its cables CIGRE B4's +-400 kV type, the data D5.1 adapts but does not give, 0.011 ohm,
 * 2.615 mH and 0.1908 uF a pole and a km - 2 R and 2 L, half the C, as one conductor. Each cable between two DC breakers, one at
 * each end, as D4.2's fully selective protection has them: K{cable}_{station}, from the station's node B{station} to the cable's end
 * B{cable}_{station}. B1 holds no reactive power at its PCC, at its
 * rating; A1 its 333 kV bus at 1 pu; the offshore stations none at their PCC.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class PromotionDcNetwork {

    /** The network, from its flat files: {@code promotion-dc.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/promotion-dc.xiidm"), resource("/promotion-dc-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(PromotionDcNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** The DC voltage, pole to pole, kV. */
    static final double DC_KV = 640.0;
    /** The converters' AC voltage, kV: a half-bridge MMC's modulation margin at 640 kV. */
    static final double VALVE_KV = 333.0;

    /** CIGRE B4's +-400 kV DC cable, a pole and a km: resistance in ohm, inductance in H, capacitance in F. */
    static final double CABLE_R = 0.011;
    static final double CABLE_L = 2.615e-3;
    static final double CABLE_C = 0.1908e-6;

    /** One cable: its id, its ends, its length in km. */
    record Cable(String id, String node1, String node2, double km) {
    }

    static final List<Cable> CABLES = List.of(
            new Cable("CA1C2", "BA1", "BC2", 200.0),
            new Cable("CC2D1", "BC2", "BD1", 150.0),
            new Cable("CD1D2", "BD1", "BD2", 150.0),
            new Cable("CB1D1", "BB1", "BD1", 250.0),
            new Cable("CB1D2", "BB1", "BD2", 150.0));

    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("promotion-dc", "test");
        for (String node : List.of("BA1", "BB1", "BC2", "BD1", "BD2")) {
            network.newDcNode().setId(node).setNominalV(DC_KV).add();
        }
        // Each cable between two DC breakers, one at each end, on its own node (D4.2's fully selective protection).
        for (Cable cable : CABLES) {
            String end1 = breakerAt(network, cable, cable.node1());
            String end2 = breakerAt(network, cable, cable.node2());
            network.newDcLine().setId(cable.id()).setDcNode1(end1).setDcNode2(end2).setR(2 * CABLE_R * cable.km()).add();
        }
        // The onshore grids taking what the DC grid delivers, about: OpenLoadFlow never changes a generator's sign.
        VoltageSourceConverter a1 = addStation(network, "A1", 400.0, 30000.0, 1600.0, -1070.0);
        VoltageSourceConverter b1 = addStation(network, "B1", 400.0, 30000.0, 1600.0, -1600.0);
        VoltageSourceConverter c2 = addStation(network, "C2", 145.0, 3800.0, 800.0, 600.0);
        VoltageSourceConverter d1 = addStation(network, "D1", 145.0, 3800.0, 1600.0, 1200.0);
        VoltageSourceConverter d2 = addStation(network, "D2", 145.0, 3800.0, 1200.0, 900.0);
        a1.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(DC_KV);
        a1.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(VALVE_KV);
        // A droop of 10 pu of its rating per pu of 640 kV, as CIGRE DCS2's: 1500 MW at 640 kV, so 1600 MW - D5.1's - at the 644 kV
        // it settles at.
        b1.setControlMode(AcDcConverter.ControlMode.DC_DROOP).setTargetVdc(DC_KV).setTargetP(-1500.0);
        b1.newDroopCurve().beginSegment().setK(-DC_KV / (10.0 * 1600.0)).setMinV(0.5 * DC_KV).setMaxV(1.5 * DC_KV).endSegment().add();
        c2.setTargetP(600.0);
        d1.setTargetP(1200.0);
        d2.setTargetP(900.0);
        return network;
    }

    /** The DC breaker at {@code bus}'s end of {@code cable}, from the bus to the cable's end node, which it returns. */
    private static String breakerAt(Network network, Cable cable, String bus) {
        String cableEnd = cableEnd(cable.id(), bus);
        network.newDcNode().setId(cableEnd).setNominalV(DC_KV).add();
        network.newDcSwitch().setId(breaker(cable.id(), bus)).setKind(DcSwitchKind.BREAKER).setDcNode1(bus).setDcNode2(cableEnd)
                .setOpen(false).setR(0.0).add();
        return cableEnd;
    }

    /** The DC breaker at {@code bus}'s end of cable {@code cableId}: K{cable}_{station}, as KCD1D2_D1. */
    static String breaker(String cableId, String bus) {
        return "K" + cableId + "_" + bus.substring(1);
    }

    /** The node between the DC breaker at {@code bus}'s end of cable {@code cableId} and the cable: B{cable}_{station}. */
    static String cableEnd(String cableId, String bus) {
        return "B" + cableId + "_" + bus.substring(1);
    }

    /**
     * One station: its AC grid G.. - a source of {@code shortCircuitMva}, injecting {@code gridMw}, its island's slack within 5000 MW
     * either way (as CIGRE DCS2's: OpenLoadFlow leaves a generator past 10 GW out of the slack's distribution) - on its bus BG.. at
     * {@code gridKv},
     * its transformer T.. to its converter's 333 kV bus BV.., the converter between B.. and its earthed return B..G, P_PCC at none, no
     * reactive power at its PCC, reactive limits of 0.5 pu of its rating.
     */
    private static VoltageSourceConverter addStation(Network network, String id, double gridKv, double shortCircuitMva, double ratingMva,
                                                     double gridMw) {
        Substation substation = network.newSubstation().setId("S" + id).add();
        VoltageLevel grid = substation.newVoltageLevel().setId("VG" + id).setNominalV(gridKv).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        grid.getBusBreakerView().newBus().setId("BG" + id).add();
        Generator source = grid.newGenerator()
                .setId("G" + id)
                .setBus("BG" + id).setConnectableBus("BG" + id)
                .setMinP(-5000.0).setMaxP(5000.0).setRatedS(shortCircuitMva)
                .setTargetP(gridMw).setLocalTargetV(gridKv).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        source.newExtension(ActivePowerControlAdder.class).withParticipate(true).withDroop(4.0).add();
        VoltageLevel valve = substation.newVoltageLevel().setId("VV" + id).setNominalV(VALVE_KV).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        valve.getBusBreakerView().newBus().setId("BV" + id).add();
        double baseOhm = VALVE_KV * VALVE_KV / ratingMva;
        TwoWindingsTransformer transformer = substation.newTwoWindingsTransformer()
                .setId("T" + id)
                .setVoltageLevel1(grid.getId()).setBus1("BG" + id).setConnectableBus1("BG" + id)
                .setVoltageLevel2(valve.getId()).setBus2("BV" + id).setConnectableBus2("BV" + id)
                .setRatedU1(gridKv).setRatedU2(VALVE_KV).setRatedS(ratingMva)
                .setR(0.006 * baseOhm).setX(0.18 * baseOhm).setG(0.0).setB(0.0)
                .add();
        transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withRz(transformer.getR()).withXz(transformer.getX())
                .withFreeFluxes(true).withConnectionType1(WindingConnectionType.Y_GROUNDED).withConnectionType2(WindingConnectionType.Y).add();
        String dcNode = "B" + id;
        network.newDcNode().setId(dcNode + "G").setNominalV(DC_KV).add();
        network.newDcGround().setId("DG" + id).setDcNode(dcNode + "G").setR(0.0).add();
        VoltageSourceConverter converter = valve.newVoltageSourceConverter()
                .setId("C" + id)
                .setBus1("BV" + id).setConnectableBus1("BV" + id)
                .setDcNode1(dcNode).setDcNode2(dcNode + "G")
                .setPccTerminal(transformer.getTerminal1())
                .setControlMode(AcDcConverter.ControlMode.P_PCC).setTargetVdc(DC_KV).setTargetP(0.0)
                .setLocalTargetV(VALVE_KV)
                .newVoltageRegulation().withMode(RegulationMode.REACTIVE_POWER).withTargetValue(0.0).withTerminal(transformer.getTerminal1()).add()
                .add();
        converter.newMinMaxReactiveLimits().setMinQ(-0.5 * ratingMva).setMaxQ(0.5 * ratingMva).add();
        return converter;
    }

    private PromotionDcNetwork() {
    }
}
