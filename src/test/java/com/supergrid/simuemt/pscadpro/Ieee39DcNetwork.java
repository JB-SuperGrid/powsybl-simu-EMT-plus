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
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The IEEE 39-bus New England system with a meshed four-terminal DC grid, as this project runs it in PSCAD Professional:
 * {@link #create} builds it flat, {@code ieee39-dc.xiidm} is what it writes, {@code ieee39-dc-extensions.xml} its EMT models;
 * {@link #load} reads both.
 *
 * <p>The AC network is MATPOWER's {@code case39} (its data from Athay, Podmore and Virmani, IEEE PAS-98, 1979, through M. A. Pai,
 * <i>Energy Function Analysis for Power System Stability</i>, 1989), in per unit of 100 MVA, on voltages its kV can hold: 345 kV for
 * its transmission, 20 kV for its machines' buses 30 to 38, 138 kV for bus 12 and 230 kV for bus 20, the load buses its transformers
 * feed. Its machines on their load flow, bus 31 the slack, each holding its bus at its voltage, with no reactive limit - as
 * MATPOWER's solution has them, which two limits would otherwise bind; G39 the rest of the eastern interconnection. Its
 * transformers' windings, which the data give no resistance, X/R {@link #WINDING_X_OVER_R}, as {@link Ieee14DcNetwork}'s.
 *
 * <p>The DC grid is this project's own, not a published one: four stations on a 400 kV monopole earthed at each, a meshed
 * triangle between the network's load centre (bus 4), its hub (bus 16) and its northern generation (bus 26), and an offshore
 * wind park's two cables:
 * <pre>
 *   CS4  (bus 4,  345 kV, 500 MVA) droop, 350 MW into the load centre    BD4 ==OHL 200 km== BD16 ==OHL 150 km== BD26
 *   CS16 (bus 16, 345 kV, 500 MVA) V_DC 400 kV                           BD26 ==OHL 250 km== BD4
 *   CS26 (bus 26, 345 kV, 300 MVA) droop, 200 MW from the north          BDW ==cable 100 km== BD26, BDW ==cable 150 km== BD16
 *   CSW  (BW, 145 kV, 500 MVA)     grid-forming, its island's, WTW 400 MW
 * </pre>
 * The DC lines CIGRE B4's +-200 kV types as one 400 kV conductor, as {@link Ieee14DcNetwork}'s. The converters of 220 kV AC behind
 * YNy transformers of 0.006 + j0.18 pu on their rating. The wind park's 400 MW displace the slack's production; CS4 and CS26 hold
 * their 220 kV bus at 1 pu, CS16 none of its reactive power at its PCC; CSW its island's 220 kV bus.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class Ieee39DcNetwork {

    /** The network, from its flat files: {@code ieee39-dc.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee39-dc.xiidm"), resource("/ieee39-dc-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(Ieee39DcNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** The X/R of the transformer windings the data give no resistance. */
    static final double WINDING_X_OVER_R = 50.0;

    /** The case's power base, MVA. */
    private static final double BASE_MVA = 100.0;

    /** A machine's rating, MVA: the data's reactances on 100 MVA are those of 1000 MVA units; G39's, the rest of the eastern
     * interconnection, of 10000 MVA - H 500 s on 100 MVA, 1000 MW. */
    static double machineMva(int bus) {
        return bus == 39 ? 10000.0 : 1000.0;
    }

    /** The slack's bus, MATPOWER's. */
    static final int SLACK = 31;

    // MATPOWER's case39 (BSD-3-Clause), its re-solved load flow.
    /** Buses: number, Pd MW, Qd Mvar, the solved |V| pu and angle degrees. */
    static final double[][] BUSES = {
        {1, 97.6, 44.2, 1.0393836, -13.536602},
        {2, 0.0, 0.0, 1.0484941, -9.7852666},
        {3, 322.0, 2.4, 1.0307077, -12.276384},
        {4, 500.0, 184.0, 1.00446, -12.626734},
        {5, 0.0, 0.0, 1.0060063, -11.192339},
        {6, 0.0, 0.0, 1.0082256, -10.40833},
        {7, 233.8, 84.0, 0.99839728, -12.755626},
        {8, 522.0, 176.6, 0.99787232, -13.335844},
        {9, 6.5, -66.6, 1.038332, -14.178442},
        {10, 0.0, 0.0, 1.0178431, -8.170875},
        {11, 0.0, 0.0, 1.0133858, -8.9369663},
        {12, 8.53, 88.0, 1.000815, -8.9988236},
        {13, 0.0, 0.0, 1.014923, -8.9299272},
        {14, 0.0, 0.0, 1.012319, -10.715295},
        {15, 320.0, 153.0, 1.0161854, -11.345399},
        {16, 329.0, 32.3, 1.0325203, -10.033348},
        {17, 0.0, 0.0, 1.0342365, -11.116436},
        {18, 158.0, 30.0, 1.0315726, -11.986168},
        {19, 0.0, 0.0, 1.0501068, -5.4100729},
        {20, 680.0, 103.0, 0.99101054, -6.8211783},
        {21, 274.0, 115.0, 1.0323192, -7.6287461},
        {22, 0.0, 0.0, 1.0501427, -3.1831199},
        {23, 247.5, 84.6, 1.0451451, -3.3812763},
        {24, 308.6, -92.2, 1.038001, -9.9137585},
        {25, 224.0, 47.2, 1.0576827, -8.3692354},
        {26, 139.0, 17.0, 1.0525613, -9.4387696},
        {27, 281.0, 75.5, 1.0383449, -11.362152},
        {28, 206.0, 27.6, 1.0503737, -5.9283592},
        {29, 283.5, 26.9, 1.0501149, -3.1698741},
        {30, 0.0, 0.0, 1.0499, -7.3704746},
        {31, 9.2, 4.6, 0.982, 0.0},
        {32, 0.0, 0.0, 0.9841, -0.1884374},
        {33, 0.0, 0.0, 0.9972, -0.19317445},
        {34, 0.0, 0.0, 1.0123, -1.631119},
        {35, 0.0, 0.0, 1.0494, 1.7765069},
        {36, 0.0, 0.0, 1.0636, 4.4684374},
        {37, 0.0, 0.0, 1.0275, -1.5828988},
        {38, 0.0, 0.0, 1.0265, 3.8928177},
        {39, 1104.0, 250.0, 1.03, -14.535256},
    };
    /** Generators: bus, Pg MW, Qg Mvar, Vg pu. */
    private static final double[][] GENERATORS = {
        {30, 250.0, 161.762, 1.0499},
        {31, 677.871, 221.574, 0.982},
        {32, 650.0, 206.965, 0.9841},
        {33, 632.0, 108.293, 0.9972},
        {34, 508.0, 166.688, 1.0123},
        {35, 650.0, 210.661, 1.0494},
        {36, 560.0, 100.165, 1.0636},
        {37, 540.0, -1.36945, 1.0275},
        {38, 830.0, 21.7327, 1.0265},
        {39, 1000.0, 78.4674, 1.03},
    };
    /** Branches: from bus, to bus, r, x, b pu on 100 MVA, the transformer's ratio at its from bus (0 for a line). */
    private static final double[][] BRANCHES = {
        {1, 2, 0.0035, 0.0411, 0.6987, 0.0},
        {1, 39, 0.001, 0.025, 0.75, 0.0},
        {2, 3, 0.0013, 0.0151, 0.2572, 0.0},
        {2, 25, 0.007, 0.0086, 0.146, 0.0},
        {2, 30, 0.0, 0.0181, 0.0, 1.025},
        {3, 4, 0.0013, 0.0213, 0.2214, 0.0},
        {3, 18, 0.0011, 0.0133, 0.2138, 0.0},
        {4, 5, 0.0008, 0.0128, 0.1342, 0.0},
        {4, 14, 0.0008, 0.0129, 0.1382, 0.0},
        {5, 6, 0.0002, 0.0026, 0.0434, 0.0},
        {5, 8, 0.0008, 0.0112, 0.1476, 0.0},
        {6, 7, 0.0006, 0.0092, 0.113, 0.0},
        {6, 11, 0.0007, 0.0082, 0.1389, 0.0},
        {6, 31, 0.0, 0.025, 0.0, 1.07},
        {7, 8, 0.0004, 0.0046, 0.078, 0.0},
        {8, 9, 0.0023, 0.0363, 0.3804, 0.0},
        {9, 39, 0.001, 0.025, 1.2, 0.0},
        {10, 11, 0.0004, 0.0043, 0.0729, 0.0},
        {10, 13, 0.0004, 0.0043, 0.0729, 0.0},
        {10, 32, 0.0, 0.02, 0.0, 1.07},
        {12, 11, 0.0016, 0.0435, 0.0, 1.006},
        {12, 13, 0.0016, 0.0435, 0.0, 1.006},
        {13, 14, 0.0009, 0.0101, 0.1723, 0.0},
        {14, 15, 0.0018, 0.0217, 0.366, 0.0},
        {15, 16, 0.0009, 0.0094, 0.171, 0.0},
        {16, 17, 0.0007, 0.0089, 0.1342, 0.0},
        {16, 19, 0.0016, 0.0195, 0.304, 0.0},
        {16, 21, 0.0008, 0.0135, 0.2548, 0.0},
        {16, 24, 0.0003, 0.0059, 0.068, 0.0},
        {17, 18, 0.0007, 0.0082, 0.1319, 0.0},
        {17, 27, 0.0013, 0.0173, 0.3216, 0.0},
        {19, 20, 0.0007, 0.0138, 0.0, 1.06},
        {19, 33, 0.0007, 0.0142, 0.0, 1.07},
        {20, 34, 0.0009, 0.018, 0.0, 1.009},
        {21, 22, 0.0008, 0.014, 0.2565, 0.0},
        {22, 23, 0.0006, 0.0096, 0.1846, 0.0},
        {22, 35, 0.0, 0.0143, 0.0, 1.025},
        {23, 24, 0.0022, 0.035, 0.361, 0.0},
        {23, 36, 0.0005, 0.0272, 0.0, 1.0},
        {25, 26, 0.0032, 0.0323, 0.531, 0.0},
        {25, 37, 0.0006, 0.0232, 0.0, 1.025},
        {26, 27, 0.0014, 0.0147, 0.2396, 0.0},
        {26, 28, 0.0043, 0.0474, 0.7802, 0.0},
        {26, 29, 0.0057, 0.0625, 1.029, 0.0},
        {28, 29, 0.0014, 0.0151, 0.249, 0.0},
        {29, 38, 0.0008, 0.0156, 0.0, 1.025},
    };

    /** A bus's nominal voltage, kV. */
    static double nominalKv(int bus) {
        if (bus >= 30 && bus <= 38) {
            return 20.0;
        }
        return switch (bus) {
            case 12 -> 138.0;
            case 20 -> 230.0;
            default -> 345.0;
        };
    }

    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("ieee39-dc", "test");
        Map<Integer, Integer> substations = substations();
        for (double[] bus : BUSES) {
            int n = (int) bus[0];
            String substationId = "S" + substations.get(n);
            Substation substation = network.getSubstation(substationId);
            if (substation == null) {
                substation = network.newSubstation().setId(substationId).add();
            }
            VoltageLevel level = substation.newVoltageLevel().setId("VL" + n).setNominalV(nominalKv(n)).setTopologyKind(TopologyKind.BUS_BREAKER).add();
            level.getBusBreakerView().newBus().setId("B" + n).add();
            if (bus[1] != 0.0 || bus[2] != 0.0) {
                level.newLoad().setId("L" + n).setBus("B" + n).setConnectableBus("B" + n).setP0(bus[1]).setQ0(bus[2]).add();
            }
        }
        for (double[] generator : GENERATORS) {
            addMachine(network, generator);
        }
        for (double[] branch : BRANCHES) {
            addBranch(network, branch);
        }
        addDcGrid(network);
        return network;
    }

    /** Each bus's substation, numbered by its lowest bus: the buses its transformers join together. */
    private static Map<Integer, Integer> substations() {
        Map<Integer, Integer> parent = new HashMap<>();
        for (double[] bus : BUSES) {
            parent.put((int) bus[0], (int) bus[0]);
        }
        for (double[] branch : BRANCHES) {
            if (branch[5] != 0.0) {
                int a = root(parent, (int) branch[0]);
                int b = root(parent, (int) branch[1]);
                parent.put(Math.max(a, b), Math.min(a, b));
            }
        }
        Map<Integer, Integer> substations = new HashMap<>();
        parent.keySet().forEach(bus -> substations.put(bus, root(parent, bus)));
        return substations;
    }

    private static int root(Map<Integer, Integer> parent, int bus) {
        int r = bus;
        while (parent.get(r) != r) {
            r = parent.get(r);
        }
        return r;
    }

    /** A machine of the case, regulating its bus at the case's voltage with no reactive limit; the slack alone takes the losses. */
    private static void addMachine(Network network, double[] data) {
        int n = (int) data[0];
        VoltageLevel level = network.getVoltageLevel("VL" + n);
        Generator generator = level.newGenerator()
                .setId("G" + n)
                .setBus("B" + n).setConnectableBus("B" + n)
                .setMinP(0.0).setMaxP(machineMva(n)).setRatedS(machineMva(n))
                .setTargetP(data[1]).setLocalTargetV(data[3] * level.getNominalV()).setLocalTargetQ(data[2])
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        generator.newMinMaxReactiveLimits().setMinQ(-9999.0).setMaxQ(9999.0).add();
        generator.newExtension(ActivePowerControlAdder.class).withParticipate(n == SLACK).withDroop(4.0).add();
    }

    /**
     * A branch of the case: a line in ohm and siemens from its per unit on its buses' voltage, a transformer with its ratio on its
     * from bus's side - its rated voltages that ratio times the buses' -, its impedance on its other side, as MATPOWER has it. A
     * transformer without resistance given one, X/R {@link #WINDING_X_OVER_R}.
     */
    private static void addBranch(Network network, double[] branch) {
        int from = (int) branch[0];
        int to = (int) branch[1];
        double kv = nominalKv(to);
        double baseOhm = kv * kv / BASE_MVA;
        double ratio = branch[5];
        if (ratio == 0.0) {
            double halfB = branch[4] / baseOhm / 2;
            network.newLine()
                    .setId("L" + from + "_" + to)
                    .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                    .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                    .setR(branch[2] * baseOhm).setX(branch[3] * baseOhm)
                    .setG1(0.0).setB1(halfB).setG2(0.0).setB2(halfB)
                    .add();
        } else {
            double r = branch[2] == 0.0 ? branch[3] / WINDING_X_OVER_R : branch[2];
            network.getVoltageLevel("VL" + from).getSubstation().orElseThrow().newTwoWindingsTransformer()
                    .setId("T" + from + "_" + to)
                    .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                    .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                    .setRatedU1(ratio * nominalKv(from)).setRatedU2(kv)
                    .setR(r * baseOhm).setX(branch[3] * baseOhm).setG(0.0).setB(0.0)
                    .add();
        }
    }

    /** A DC line type's data per km of a pole: resistance in ohm, inductance in H, capacitance in F. */
    private record LineType(double resistance, double inductance, double capacitance) {
    }

    /** CIGRE B4's +-200 kV DC OHL and cable. */
    private static final LineType DC_OHL = new LineType(0.0133, 0.8273e-3, 0.0139e-6);
    private static final LineType DC_CABLE = new LineType(0.011, 2.615e-3, 0.2185e-6);

    private static void addDcGrid(Network network) {
        // The offshore island: its bus, its wind park, out of the slack's distribution.
        Substation offshore = network.newSubstation().setId("SW").add();
        offshore.newVoltageLevel().setId("VLW").setNominalV(145.0).setTopologyKind(TopologyKind.BUS_BREAKER).add()
                .getBusBreakerView().newBus().setId("BW").add();
        Generator wind = network.getVoltageLevel("VLW").newGenerator()
                .setId("WTW")
                .setBus("BW").setConnectableBus("BW")
                .setMinP(0.0).setMaxP(400.0).setRatedS(400.0)
                .setTargetP(400.0).setLocalTargetV(145.0).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).add()
                .add();
        wind.newExtension(ActivePowerControlAdder.class).withParticipate(false).withDroop(4.0).add();

        for (String node : List.of("BD4", "BD16", "BD26", "BDW")) {
            network.newDcNode().setId(node).setNominalV(400.0).add();
        }
        addDcLine(network, "D4_16", "BD4", "BD16", DC_OHL, 200.0);
        addDcLine(network, "D16_26", "BD16", "BD26", DC_OHL, 150.0);
        addDcLine(network, "D26_4", "BD26", "BD4", DC_OHL, 250.0);
        addDcLine(network, "DW_26", "BDW", "BD26", DC_CABLE, 100.0);
        addDcLine(network, "DW_16", "BDW", "BD16", DC_CABLE, 150.0);

        VoltageSourceConverter cs4 = addStation(network, "CS4", "B4", "BD4", 500.0);
        VoltageSourceConverter cs16 = addStation(network, "CS16", "B16", "BD16", 500.0);
        VoltageSourceConverter cs26 = addStation(network, "CS26", "B26", "BD26", 300.0);
        VoltageSourceConverter csw = addStation(network, "CSW", "BW", "BDW", 500.0);
        cs16.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        // Droops of 10 pu of their rating per pu of 400 kV, as CIGRE DCS2's.
        onDroop(cs4.setTargetP(-350.0), -400.0 / (10.0 * 500.0));
        onDroop(cs26.setTargetP(200.0), -400.0 / (10.0 * 300.0));
        for (VoltageSourceConverter converter : List.of(cs4, cs26, csw)) {
            converter.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }
        // The island's power, which its balancing brings to what the wind park gives less its losses.
        csw.setTargetP(400.0);
    }

    /** A DC line's conductor: a symmetric monopole's two poles in series, 2 R. */
    private static void addDcLine(Network network, String id, String node1, String node2, LineType type, double lengthKm) {
        network.newDcLine().setId(id).setDcNode1(node1).setDcNode2(node2).setR(2 * type.resistance() * lengthKm).add();
    }

    /** One converter station, as {@link Ieee14DcNetwork}'s: its transformer TC.. from {@code acBus} to its converter's 220 kV bus
     * BC.. (in the AC bus's substation), the converter between {@code dcNode} and its earthed return {@code dcNode}G, P_PCC at none,
     * no reactive power at its PCC, reactive limits of 0.5 pu of its rating. */
    private static VoltageSourceConverter addStation(Network network, String id, String acBus, String dcNode, double ratingMva) {
        String suffix = id.substring(2);
        VoltageLevel ac = network.getBusBreakerView().getBus(acBus).getVoltageLevel();
        Substation substation = ac.getSubstation().orElseThrow();
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

    /** {@code converter} on a DC voltage droop: its {@code targetP} at 400 kV, {@code k} kV/MW, over the whole operating range. */
    private static void onDroop(VoltageSourceConverter converter, double k) {
        converter.setControlMode(AcDcConverter.ControlMode.DC_DROOP).setTargetVdc(400.0);
        converter.newDroopCurve().beginSegment().setK(k).setMinV(200.0).setMaxV(600.0).endSegment().add();
    }

    private Ieee39DcNetwork() {
    }
}
