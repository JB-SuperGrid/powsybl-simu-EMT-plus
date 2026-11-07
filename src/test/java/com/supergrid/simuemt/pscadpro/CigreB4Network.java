/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
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
import com.powsybl.simuemt.extensions.EmtLoadFlow;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.testresources.ExampleNetworks;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * CIGRE B4's DC grid test system, whole, as this project runs it in PSCAD Professional: {@link #create} builds it flat, {@code
 * cigre-b4.xiidm} is what it writes, {@code cigre-b4-extensions.xml} its EMT models; {@link #load} reads both.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class CigreB4Network {

    /** The network, from its flat files: {@code cigre-b4.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/cigre-b4.xiidm"), resource("/cigre-b4-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(CigreB4Network.class.getResourceAsStream(name), "No test resource " + name);
    }

    /**
     * CIGRE B4's DC grid test system, whole (CIGRE TB 604, WG B4.57/B4.58; data from DIgSILENT's load-flow model of it, its "Full Test
     * System" study case): two meshed onshore AC systems, four offshore islands and three DC grids - DCS1, a +-200 kV link; DCS2, the +-200
     * kV five-node radial grid of {@link ExampleNetworks#createCigreDcs2Network}; DCS3, a meshed +-400 kV bipolar grid of seven nodes, five
     * of them stations:
     * <pre>
     *   System A (380 kV): GRA0 slack (BAA0) ==2 x OHL 200 km== BAA1: GA1 2000 MW, LDA1 1000 MW, CMA1 (DCS1), CBA1 (DCS3)
     *   System B (380 kV): GRB0 slack (BAB0), OHL 200 km B0-B1, B0-B2, B0-B3, B1-B3, B2-B3;
     *                      BAB1: GB1 1000 MW, LDB1 2200 MW, CBB1 (DCS3); BAB2: GB2 1000 MW, LDB2 2300 MW, CMB2 (DCS2), CBB2 (DCS3);
     *                      BAB3: GB3 1000 MW, LDB3 1900 MW, CMB3 (DCS2)
     *   Offshore (145 kV): C - BOC1 (WTC1 500 MW, CMC1) ==cable 50 km== BOC2 (WTC2 500 MW, CBC2); D - BOD1 (WTD1 1000 MW, CBD1);
     *                      E - BOE1 (LDE1 100 MW, CME1); F - BOF1 (WTF1 500 MW, CMF1)
     *   DCS1: BMA1 ==cable 200 km== BMC1
     *   DCS2: BMB2 ==cable 200 km== BMB3 ==OHL 100 km== BMB5 ==cable 100 km== BMF1 ==cable 200 km== BME1
     *   DCS3: BBA1 ==2 x OHL 400 km== BBB1, BBA1 ==OHL 500 km== BBB4, BBA1 ==cable 200 km== BBC2, BBB1 ==OHL 200 km== BBB4,
     *         BBB2 ==2 x OHL 300 km== BBB4, BBC2 ==cable 300 km== BBD1, BBD1 ==cable 200 km== BBE1 ==cable 200 km== BBB1
     * </pre>
     * As in {@link ExampleNetworks#createCigreDcs2Network}, each grid an asymmetric 400 kV monopole earthed at each station ({@code
     * DcGround}s of R = 0): a symmetric +-200 kV monopole's conductor its two poles' resistance and inductance, 2 R and 2 L, and half a
     * pole's capacitance, C / 2; a balanced +-400 kV bipole both poles in parallel at the same 400 kV, R / 2, L / 2 and 2 C, its two
     * converters one of twice their rating, its two transformers one of twice theirs, the same power at the same losses. Per km of a pole:
     * DC cables 0.011 ohm, 2.615 mH and 0.2185 uF at +-200 kV, 0.1908 uF at +-400 kV; DC OHLs 0.0133 ohm, 0.8273 mH, 0.0139 uF at +-200 kV,
     * 0.0114 ohm, 0.9356 mH, 0.0123 uF at +-400 kV (the cables' leakage left out). The DC lines' L and C are the extensions'.
     *
     * <p>Two departures from the brochure's network: its DC/DC converters are not, IIDM has none. Cd-B1, of ratio 1 between DCS3's nodes B1x
     * and B1, is that one node, BBB1; Cd-E1, between DCS3's E1 and DCS2's, is left out - DCS2 and DCS3 apart, BBE1 a junction of two cables,
     * the offshore load LDE1 fed by DCS2 alone, as in the DCS2 study case.
     *
     * <p>Onshore, AC lines of 380 kV, 0.02 ohm, 0.8532 mH, 0.0135 uF per km; offshore, C1 and C2 joined by a 145 kV cable of 0.0843 ohm,
     * 0.2526 mH, 0.1837 uF per km (its leakage left out). The external grids GRA0 and GRB0 hold their bus at 380 kV, their systems' slacks;
     * the onshore generators GA1 and GB1 to GB3 are the brochure's power sources, on their active power out of the slack's distribution and
     * at no reactive power - their models, like the external grids', the extensions' (30 GVA, X/R 10). The offshore generation, wind parks:
     * on their power whatever their island's losses, out of the slack's distribution.
     *
     * <p>Converters of 220 kV AC behind YNy transformers (0.006 + j0.18 pu on their converter's rating, earthed on the network's side,
     * isolated on the valves', as {@link ExampleNetworks#createCigreDcs2Network}'s), 380/220 kV onshore, 145/220 kV offshore; their controls
     * the "Full Test System"'s. DC voltage: CMA1 holds DCS1's at 400 kV, CMB2 DCS2's at 400 kV, CBA1 DCS3's at 404 kV. Droops of K =
     * 0.00016666 pu of 400 kV per MW of a converter: CMB3 around -800 MW (the DCS2 study case's, the full one gives none), CBB1 around -1500
     * MW and CBB2 -1700 MW (750 and 850 MW a pole, K halved for the two poles in parallel). Reactive: CMA1, CMB2 and CBC2 at none at their
     * PCC, CBA1, CBB1, CBB2 and CMB3 holding a bus at 1 pu, their own 220 kV one - the brochure's is their 380 kV bus; OpenLoadFlow
     * regulates a converter's own, as in DCS2. Offshore, CMC1, CBD1, CME1 and CMF1 form their island and hold their 220 kV bus at 1 pu
     * (P_PCC, the islands' slacks, {@link EmtLoadFlow#balanceConverterIslands}); CBC2, in CMC1's island, its only grid-following converter,
     * brings 600 MW (300 MW a pole) at its PCC.
     */
    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("cigre-b4", "test");
        for (String bus : List.of("BAA0", "BAA1", "BAB0", "BAB1", "BAB2", "BAB3")) {
            addAcBus(network, bus, 380.0);
        }
        for (String bus : List.of("BOC1", "BOC2", "BOD1", "BOE1", "BOF1")) {
            addAcBus(network, bus, 145.0);
        }
        addAcLine(network, "LA0A1A", "BAA0", "BAA1", AC_OHL, 200.0);
        addAcLine(network, "LA0A1B", "BAA0", "BAA1", AC_OHL, 200.0);
        addAcLine(network, "LB0B1", "BAB0", "BAB1", AC_OHL, 200.0);
        addAcLine(network, "LB0B2", "BAB0", "BAB2", AC_OHL, 200.0);
        addAcLine(network, "LB0B3", "BAB0", "BAB3", AC_OHL, 200.0);
        addAcLine(network, "LB1B3", "BAB1", "BAB3", AC_OHL, 200.0);
        addAcLine(network, "LB2B3", "BAB2", "BAB3", AC_OHL, 200.0);
        addAcLine(network, "LC1C2", "BOC1", "BOC2", AC_CABLE, 50.0);

        // The DC nodes: each grid's, each station's earthed return.
        for (String dcNode : List.of("BMA1", "BMC1", "BMB2", "BMB3", "BMB5", "BME1", "BMF1",
                "BBA1", "BBB1", "BBB2", "BBB4", "BBC2", "BBD1", "BBE1")) {
            network.newDcNode().setId(dcNode).setNominalV(400.0).add();
        }
        addDcLine(network, "DCA1C1", "BMA1", "BMC1", DC_CABLE_200, 200.0, false);
        addDcLine(network, "DCB2B3", "BMB2", "BMB3", DC_CABLE_200, 200.0, false);
        addDcLine(network, "DCB3B5", "BMB3", "BMB5", DC_OHL_200, 100.0, false);
        addDcLine(network, "DCB5F1", "BMB5", "BMF1", DC_CABLE_200, 100.0, false);
        addDcLine(network, "DCF1E1", "BMF1", "BME1", DC_CABLE_200, 200.0, false);
        addDcLine(network, "DBA1B1A", "BBA1", "BBB1", DC_OHL_400, 400.0, true);
        addDcLine(network, "DBA1B1B", "BBA1", "BBB1", DC_OHL_400, 400.0, true);
        addDcLine(network, "DBA1B4", "BBA1", "BBB4", DC_OHL_400, 500.0, true);
        addDcLine(network, "DBA1C2", "BBA1", "BBC2", DC_CABLE_400, 200.0, true);
        addDcLine(network, "DBB1B4", "BBB1", "BBB4", DC_OHL_400, 200.0, true);
        addDcLine(network, "DBB1E1", "BBB1", "BBE1", DC_CABLE_400, 200.0, true);
        addDcLine(network, "DBB2B4A", "BBB2", "BBB4", DC_OHL_400, 300.0, true);
        addDcLine(network, "DBB2B4B", "BBB2", "BBB4", DC_OHL_400, 300.0, true);
        addDcLine(network, "DBC2D1", "BBC2", "BBD1", DC_CABLE_400, 300.0, true);
        addDcLine(network, "DBD1E1", "BBD1", "BBE1", DC_CABLE_400, 200.0, true);

        VoltageSourceConverter cma1 = addStation(network, "CMA1", "BAA1", "BMA1", 800.0);
        VoltageSourceConverter cba1 = addStation(network, "CBA1", "BAA1", "BBA1", 2 * 1200.0);
        VoltageSourceConverter cbb1 = addStation(network, "CBB1", "BAB1", "BBB1", 2 * 1200.0);
        VoltageSourceConverter cmb2 = addStation(network, "CMB2", "BAB2", "BMB2", 800.0);
        VoltageSourceConverter cbb2 = addStation(network, "CBB2", "BAB2", "BBB2", 2 * 800.0);
        VoltageSourceConverter cmb3 = addStation(network, "CMB3", "BAB3", "BMB3", 1200.0);
        VoltageSourceConverter cmc1 = addStation(network, "CMC1", "BOC1", "BMC1", 800.0);
        VoltageSourceConverter cbc2 = addStation(network, "CBC2", "BOC2", "BBC2", 2 * 400.0);
        VoltageSourceConverter cbd1 = addStation(network, "CBD1", "BOD1", "BBD1", 2 * 800.0);
        VoltageSourceConverter cme1 = addStation(network, "CME1", "BOE1", "BME1", 200.0);
        VoltageSourceConverter cmf1 = addStation(network, "CMF1", "BOF1", "BMF1", 800.0);
        cma1.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        cmb2.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        cba1.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(404.0);
        onDroop(cmb3.setTargetP(-800.0), -DROOP_PU_PER_MW * 400.0);
        onDroop(cbb1.setTargetP(-1500.0), -DROOP_PU_PER_MW * 400.0 / 2);
        onDroop(cbb2.setTargetP(-1700.0), -DROOP_PU_PER_MW * 400.0 / 2);
        for (VoltageSourceConverter onshore : List.of(cba1, cbb1, cbb2, cmb3)) {
            onshore.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }
        cbc2.setTargetP(600.0);
        // The islands' powers, which their balancing brings to what each island takes: its wind less its losses, CBC2's, the load.
        cmc1.setTargetP(400.0);
        cbd1.setTargetP(1000.0);
        cme1.setTargetP(-100.0);
        cmf1.setTargetP(500.0);
        for (VoltageSourceConverter offshore : List.of(cmc1, cbd1, cme1, cmf1)) {
            offshore.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }

        // The external grids, their systems' slacks: on what the load flow then gives them, some 300 MW into system A, 200 MW out of B.
        for (String grid : List.of("A0", "B0")) {
            network.getVoltageLevel("VA" + grid).newGenerator()
                    .setId("GR" + grid)
                    .setBus("BA" + grid).setConnectableBus("BA" + grid)
                    .setMinP(-5000.0).setMaxP(5000.0).setRatedS(30000.0)
                    .setTargetP("A0".equals(grid) ? 300.0 : -200.0).setLocalTargetV(380.0).setLocalTargetQ(0.0)
                    .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                    .add();
        }
        addPowerSource(network, "GA1", "BAA1", 2000.0, 30000.0);
        for (String bus : List.of("B1", "B2", "B3")) {
            addPowerSource(network, "G" + bus, "BA" + bus, 1000.0, 30000.0);
        }
        addPowerSource(network, "WTC1", "BOC1", 500.0, 500.0);
        addPowerSource(network, "WTC2", "BOC2", 500.0, 500.0);
        addPowerSource(network, "WTD1", "BOD1", 1000.0, 1000.0);
        addPowerSource(network, "WTF1", "BOF1", 500.0, 500.0);
        addLoad(network, "LDA1", "BAA1", 1000.0);
        addLoad(network, "LDB1", "BAB1", 2200.0);
        addLoad(network, "LDB2", "BAB2", 2300.0);
        addLoad(network, "LDB3", "BAB3", 1900.0);
        addLoad(network, "LDE1", "BOE1", 100.0);
        return network;
    }

    /** {@code converter} on a DC voltage droop: its {@code targetP} at 400 kV, {@code k} kV/MW, over the whole operating range. */
    private static void onDroop(VoltageSourceConverter converter, double k) {
        converter.setControlMode(AcDcConverter.ControlMode.DC_DROOP).setTargetVdc(400.0);
        converter.newDroopCurve().beginSegment().setK(k).setMinV(200.0).setMaxV(600.0).endSegment().add();
    }

    /** The CIGRE B4 test system's DC voltage droops' slope, pu of 400 kV per MW of a converter: the brochure's K. */
    private static final double DROOP_PU_PER_MW = 0.00016666;

    /** A CIGRE B4 line type's data per km - of a pole for a DC one: resistance in ohm, inductance in H, capacitance in F. */
    private record LineType(double resistance, double inductance, double capacitance) {
    }

    private static final LineType AC_OHL = new LineType(0.02, 0.8532e-3, 0.0135e-6);
    private static final LineType AC_CABLE = new LineType(0.0843, 0.2526e-3, 0.1837e-6);
    private static final LineType DC_CABLE_200 = new LineType(0.011, 2.615e-3, 0.2185e-6);
    private static final LineType DC_OHL_200 = new LineType(0.0133, 0.8273e-3, 0.0139e-6);
    private static final LineType DC_CABLE_400 = new LineType(0.011, 2.615e-3, 0.1908e-6);
    private static final LineType DC_OHL_400 = new LineType(0.0114, 0.9356e-3, 0.0123e-6);

    /** A CIGRE B4 AC bus: its substation S.., its voltage level V.., the bus itself. */
    private static void addAcBus(Network network, String bus, double nominalKv) {
        Substation substation = network.newSubstation().setId("S" + bus.substring(2)).add();
        substation.newVoltageLevel().setId("V" + bus.substring(1)).setNominalV(nominalKv).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId(bus).add();
    }

    /** A CIGRE B4 AC line of {@code type}, {@code lengthKm} long, as a pi: its charging half at each end. */
    private static void addAcLine(Network network, String id, String bus1, String bus2, LineType type, double lengthKm) {
        double omega = 2 * Math.PI * 50.0;
        double halfB = omega * type.capacitance() * lengthKm / 2;
        network.newLine()
                .setId(id)
                .setVoltageLevel1("V" + bus1.substring(1)).setBus1(bus1).setConnectableBus1(bus1)
                .setVoltageLevel2("V" + bus2.substring(1)).setBus2(bus2).setConnectableBus2(bus2)
                .setR(type.resistance() * lengthKm).setX(omega * type.inductance() * lengthKm)
                .setG1(0.0).setB1(halfB).setG2(0.0).setB2(halfB)
                .add();
    }

    /** A CIGRE B4 DC line's conductor: a symmetric monopole's, both poles in series, 2 R; a bipole's, both in parallel, R / 2. */
    private static void addDcLine(Network network, String id, String node1, String node2, LineType type, double lengthKm,
                                         boolean bipole) {
        network.newDcLine().setId(id).setDcNode1(node1).setDcNode2(node2).setR(type.resistance() * lengthKm * (bipole ? 0.5 : 2.0)).add();
    }

    /** One CIGRE B4 converter station: its transformer TR.. from {@code acBus} to its converter's 220 kV bus BC.. (its voltage level
     * VC.., in the AC bus's substation), the converter between {@code dcNode} and its earthed return {@code dcNode}G, P_PCC at none, no
     * reactive power at its PCC, reactive limits of 0.5 pu of its rating. */
    private static VoltageSourceConverter addStation(Network network, String id, String acBus, String dcNode, double ratingMva) {
        String suffix = id.substring(1);
        VoltageLevel ac = network.getVoltageLevel("V" + acBus.substring(1));
        double acKv = ac.getNominalV();
        VoltageLevel valve = ac.getSubstation().orElseThrow().newVoltageLevel().setId("VC" + suffix).setNominalV(220.0)
                .setTopologyKind(TopologyKind.BUS_BREAKER).add();
        valve.getBusBreakerView().newBus().setId("BC" + suffix).add();
        double baseOhm = 220.0 * 220.0 / ratingMva;
        TwoWindingsTransformer transformer = ac.getSubstation().orElseThrow().newTwoWindingsTransformer()
                .setId("TR" + suffix)
                .setVoltageLevel1(ac.getId()).setBus1(acBus).setConnectableBus1(acBus)
                .setVoltageLevel2(valve.getId()).setBus2("BC" + suffix).setConnectableBus2("BC" + suffix)
                .setRatedU1(acKv).setRatedU2(220.0).setRatedS(ratingMva)
                .setR(0.006 * baseOhm).setX(0.18 * baseOhm).setG(0.0).setB(0.0)
                .add();
        transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withRz(transformer.getR()).withXz(transformer.getX())
                .withFreeFluxes(true).withConnectionType1(WindingConnectionType.Y_GROUNDED).withConnectionType2(WindingConnectionType.Y).add();
        network.newDcNode().setId(dcNode + "G").setNominalV(400.0).add();
        network.newDcGround().setId("DG" + suffix).setDcNode(dcNode + "G").setR(0.0).add();
        VoltageSourceConverter converter = valve.newVoltageSourceConverter()
                .setId(id)
                .setBus1("BC" + suffix).setConnectableBus1("BC" + suffix)
                .setDcNode1(dcNode).setDcNode2(dcNode + "G")
                .setPccTerminal(transformer.getTerminal1())
                .setControlMode(AcDcConverter.ControlMode.P_PCC).setTargetVdc(400.0).setTargetP(0.0)
                .setLocalTargetV(220.0)
                .newVoltageRegulation().withMode(RegulationMode.REACTIVE_POWER).withTargetValue(0.0).withTerminal(transformer.getTerminal1()).add()
                .add();
        converter.newMinMaxReactiveLimits().setMinQ(-0.5 * ratingMva).setMaxQ(0.5 * ratingMva).add();
        return converter;
    }

    /** A CIGRE B4 power source: on {@code targetMw} at no reactive power, out of the slack's distribution. */
    private static void addPowerSource(Network network, String id, String bus, double targetMw, double ratedMva) {
        Generator generator = network.getVoltageLevel("V" + bus.substring(1)).newGenerator()
                .setId(id)
                .setBus(bus).setConnectableBus(bus)
                .setMinP(0.0).setMaxP(targetMw).setRatedS(ratedMva)
                .setTargetP(targetMw).setLocalTargetV(network.getVoltageLevel("V" + bus.substring(1)).getNominalV()).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).add()
                .add();
        generator.newExtension(ActivePowerControlAdder.class).withParticipate(false).withDroop(4.0).add();
    }

    private static void addLoad(Network network, String id, String bus, double p0) {
        network.getVoltageLevel("V" + bus.substring(1)).newLoad().setId(id).setBus(bus).setConnectableBus(bus).setP0(p0).setQ0(0.0).add();
    }

    private CigreB4Network() {
    }
}
