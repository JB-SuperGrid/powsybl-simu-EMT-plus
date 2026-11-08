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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The IEEE 68-bus, 16-machine, 5-area system (New England and New York, NETS-NYPS) with a meshed four-terminal DC grid, as this
 * project runs it in PSCAD Professional: {@link #create} builds it flat, {@code ieee68-dc.xiidm} is what it writes, {@code
 * ieee68-dc-extensions.xml} its EMT models; {@link #load} reads both.
 *
 * <p>The AC network is the Power System Toolbox's {@code data16m.m} (Joe Chow, Cherry Tree Scientific Software, MIT licence, as
 * Sandia's PSTess distributes it; its data from GE's report <i>Singular Perturbations, Coherency and Aggregation of Dynamic
 * Systems</i>, 1981), the IEEE PES task force's benchmark for inter-area oscillations, in per unit of 100 MVA: New England's
 * nine machines (buses 53 to 61), New York's four (62 to 65) and three neighbouring areas' equivalents (66 to 68). Its voltages
 * those its kV can hold: 345 kV for its transmission, 20 kV for its machines' buses, 138 kV for bus 12 and 230 kV for bus 20, as
 * {@link Ieee39DcNetwork}'s New England part. The data give no load flow, only its start: OpenLoadFlow solves it, bus 65 the
 * slack, each machine holding its bus at the data's voltage with no reactive limit. Its transformers' windings and two of its lines, which the
 * data give no resistance, X/R {@link #WINDING_X_OVER_R}; a branch the data give no ratio between buses of one voltage a line.
 *
 * <p>The DC grid is this project's own, not a published one: four stations on a 400 kV monopole earthed at each, a ring across
 * four of the five areas - New England's hub (bus 16), New York's (bus 33), area 3's (bus 41) and area 5's (bus 52) - and its
 * diagonal, in parallel with the AC ties whose inter-area modes the benchmark is about:
 * <pre>
 *   CS16 (bus 16, NETS, 800 MVA)   V_DC 400 kV            BD16 ==OHL 300 km== BD33 ==OHL 250 km== BD41
 *   CS33 (bus 33, NYPS, 800 MVA)   droop, 400 MW into NY  BD41 ==OHL 300 km== BD52 ==OHL 400 km== BD16
 *   CS41 (bus 41, area 3, 600 MVA) droop, 300 MW out      BD33 ==OHL 200 km== BD52
 *   CS52 (bus 52, area 5, 600 MVA) droop, 300 MW in
 * </pre>
 * The DC lines CIGRE B4's +-200 kV OHL as one 400 kV conductor, as {@link Ieee39DcNetwork}'s; the converters of 220 kV AC behind
 * YNy transformers of 0.006 + j0.18 pu on their rating. CS33, CS41 and CS52 hold their 220 kV bus at 1 pu, CS16 none of its
 * reactive power at its PCC.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class Ieee68DcNetwork {

    /** The network, from its flat files: {@code ieee68-dc.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee68-dc.xiidm"), resource("/ieee68-dc-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(Ieee68DcNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** The X/R of the branches the data give no resistance: the transformers' windings, and lines 39-44 and 39-45 - PSCAD wants a
     * resistance to bound the DC offset a fault leaves in an inductance. */
    static final double WINDING_X_OVER_R = 50.0;

    /** The case's power base, MVA. */
    private static final double BASE_MVA = 100.0;

    /** The slack's bus, PST's swing bus: New York's largest machine. */
    static final int SLACK = 65;

    // PST's data16m.m - MIT licence, (c) Joe Chow / Cherry Tree Scientific Software 1991 to 2020.
    /** Buses: number, Pd MW, Qd Mvar - PST's on 100 MVA. */
    static final double[][] BUSES = {
        {1, 252.7, 118.56},
        {2, 0.0, 0.0},
        {3, 322.0, 2.0},
        {4, 500.0, 184.0},
        {5, 0.0, 0.0},
        {6, 0.0, 0.0},
        {7, 234.0, 84.0},
        {8, 522.0, 177.0},
        {9, 104.0, 125.0},
        {10, 0.0, 0.0},
        {11, 0.0, 0.0},
        {12, 9.0, 88.0},
        {13, 0.0, 0.0},
        {14, 0.0, 0.0},
        {15, 320.0, 153.0},
        {16, 329.0, 32.0},
        {17, 0.0, 0.0},
        {18, 158.0, 30.0},
        {19, 0.0, 0.0},
        {20, 680.0, 103.0},
        {21, 274.0, 115.0},
        {22, 0.0, 0.0},
        {23, 248.0, 85.0},
        {24, 309.0, -92.0},
        {25, 224.0, 47.0},
        {26, 139.0, 17.0},
        {27, 281.0, 76.0},
        {28, 206.0, 28.0},
        {29, 284.0, 27.0},
        {30, 0.0, 0.0},
        {31, 0.0, 0.0},
        {32, 0.0, 0.0},
        {33, 112.0, 0.0},
        {34, 0.0, 0.0},
        {35, 0.0, 0.0},
        {36, 102.0, -19.46},
        {37, 6000.0, 300.0},
        {38, 0.0, 0.0},
        {39, 267.0, 12.6},
        {40, 65.63, 23.53},
        {41, 1000.0, 250.0},
        {42, 1150.0, 250.0},
        {43, 0.0, 0.0},
        {44, 267.55, 4.84},
        {45, 208.0, 21.0},
        {46, 150.7, 28.5},
        {47, 203.12, 32.59},
        {48, 241.2, 2.2},
        {49, 164.0, 29.0},
        {50, 100.0, -147.0},
        {51, 337.0, -122.0},
        {52, 2470.0, 123.0},
        {53, 0.0, 0.0},
        {54, 0.0, 0.0},
        {55, 0.0, 0.0},
        {56, 0.0, 0.0},
        {57, 0.0, 0.0},
        {58, 0.0, 0.0},
        {59, 0.0, 0.0},
        {60, 0.0, 0.0},
        {61, 0.0, 0.0},
        {62, 0.0, 0.0},
        {63, 0.0, 0.0},
        {64, 0.0, 0.0},
        {65, 0.0, 0.0},
        {66, 0.0, 0.0},
        {67, 0.0, 0.0},
        {68, 0.0, 0.0},
    };

    /** Generators: bus, Pg MW, |V| pu, the machine's rating MVA - PST's; bus 65's the slack. */
    private static final double[][] GENERATORS = {
        {53, 250.0, 1.045, 300.0},
        {54, 545.0, 0.98, 800.0},
        {55, 650.0, 0.983, 800.0},
        {56, 632.0, 0.997, 800.0},
        {57, 505.2, 1.011, 700.0},
        {58, 700.0, 1.05, 900.0},
        {59, 560.0, 1.063, 800.0},
        {60, 540.0, 1.03, 800.0},
        {61, 800.0, 1.025, 1000.0},
        {62, 500.0, 1.01, 1200.0},
        {63, 1000.0, 1.0, 1600.0},
        {64, 1350.0, 1.0156, 1900.0},
        {65, 3591.0, 1.011, 12000.0},
        {66, 1785.0, 1.0, 10000.0},
        {67, 1000.0, 1.0, 10000.0},
        {68, 4000.0, 1.0, 11000.0},
    };

    /** Branches: from bus, to bus, r, x, b pu on 100 MVA, the transformer's ratio at its from bus (0 for none) - PST's. */
    private static final double[][] BRANCHES = {
        {1, 2, 0.0035, 0.0411, 0.6987, 0.0},
        {1, 30, 0.0008, 0.0074, 0.48, 0.0},
        {2, 3, 0.0013, 0.0151, 0.2572, 0.0},
        {2, 25, 0.007, 0.0086, 0.146, 0.0},
        {2, 53, 0.0, 0.0181, 0.0, 1.025},
        {3, 4, 0.0013, 0.0213, 0.2214, 0.0},
        {3, 18, 0.0011, 0.0133, 0.2138, 0.0},
        {4, 5, 0.0008, 0.0128, 0.1342, 0.0},
        {4, 14, 0.0008, 0.0129, 0.1382, 0.0},
        {5, 6, 0.0002, 0.0026, 0.0434, 0.0},
        {5, 8, 0.0008, 0.0112, 0.1476, 0.0},
        {6, 7, 0.0006, 0.0092, 0.113, 0.0},
        {6, 11, 0.0007, 0.0082, 0.1389, 0.0},
        {6, 54, 0.0, 0.025, 0.0, 1.07},
        {7, 8, 0.0004, 0.0046, 0.078, 0.0},
        {8, 9, 0.0023, 0.0363, 0.3804, 0.0},
        {9, 30, 0.0019, 0.0183, 0.29, 0.0},
        {10, 11, 0.0004, 0.0043, 0.0729, 0.0},
        {10, 13, 0.0004, 0.0043, 0.0729, 0.0},
        {10, 55, 0.0, 0.02, 0.0, 1.07},
        {12, 11, 0.0016, 0.0435, 0.0, 1.06},
        {12, 13, 0.0016, 0.0435, 0.0, 1.06},
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
        {19, 56, 0.0007, 0.0142, 0.0, 1.07},
        {20, 57, 0.0009, 0.018, 0.0, 1.009},
        {21, 22, 0.0008, 0.014, 0.2565, 0.0},
        {22, 23, 0.0006, 0.0096, 0.1846, 0.0},
        {22, 58, 0.0, 0.0143, 0.0, 1.025},
        {23, 24, 0.0022, 0.035, 0.361, 0.0},
        {23, 59, 0.0005, 0.0272, 0.0, 0.0},
        {25, 26, 0.0032, 0.0323, 0.531, 0.0},
        {25, 60, 0.0006, 0.0232, 0.0, 1.025},
        {26, 27, 0.0014, 0.0147, 0.2396, 0.0},
        {26, 28, 0.0043, 0.0474, 0.7802, 0.0},
        {26, 29, 0.0057, 0.0625, 1.029, 0.0},
        {28, 29, 0.0014, 0.0151, 0.249, 0.0},
        {29, 61, 0.0008, 0.0156, 0.0, 1.025},
        {9, 30, 0.0019, 0.0183, 0.29, 0.0},
        {9, 36, 0.0022, 0.0196, 0.34, 0.0},
        {9, 36, 0.0022, 0.0196, 0.34, 0.0},
        {36, 37, 0.0005, 0.0045, 0.32, 0.0},
        {34, 36, 0.0033, 0.0111, 1.45, 0.0},
        {35, 34, 0.0001, 0.0074, 0.0, 0.946},
        {33, 34, 0.0011, 0.0157, 0.202, 0.0},
        {32, 33, 0.0008, 0.0099, 0.168, 0.0},
        {30, 31, 0.0013, 0.0187, 0.333, 0.0},
        {30, 32, 0.0024, 0.0288, 0.488, 0.0},
        {1, 31, 0.0016, 0.0163, 0.25, 0.0},
        {31, 38, 0.0011, 0.0147, 0.247, 0.0},
        {33, 38, 0.0036, 0.0444, 0.693, 0.0},
        {38, 46, 0.0022, 0.0284, 0.43, 0.0},
        {46, 49, 0.0018, 0.0274, 0.27, 0.0},
        {1, 47, 0.0013, 0.0188, 1.31, 0.0},
        {47, 48, 0.0025, 0.0268, 0.4, 0.0},
        {47, 48, 0.0025, 0.0268, 0.4, 0.0},
        {48, 40, 0.002, 0.022, 1.28, 0.0},
        {35, 45, 0.0007, 0.0175, 1.39, 0.0},
        {37, 43, 0.0005, 0.0276, 0.0, 0.0},
        {43, 44, 0.0001, 0.0011, 0.0, 0.0},
        {44, 45, 0.0025, 0.073, 0.0, 0.0},
        {39, 44, 0.0, 0.0411, 0.0, 0.0},
        {39, 45, 0.0, 0.0839, 0.0, 0.0},
        {45, 51, 0.0004, 0.0105, 0.72, 0.0},
        {50, 52, 0.0012, 0.0288, 2.06, 0.0},
        {50, 51, 0.0009, 0.0221, 1.62, 0.0},
        {49, 52, 0.0076, 0.1141, 1.16, 0.0},
        {52, 42, 0.004, 0.06, 2.25, 0.0},
        {42, 41, 0.004, 0.06, 2.25, 0.0},
        {41, 40, 0.006, 0.084, 3.15, 0.0},
        {31, 62, 0.0, 0.026, 0.0, 1.04},
        {32, 63, 0.0, 0.013, 0.0, 1.04},
        {36, 64, 0.0, 0.0075, 0.0, 1.04},
        {37, 65, 0.0, 0.0033, 0.0, 1.04},
        {41, 66, 0.0, 0.0015, 0.0, 1.0},
        {42, 67, 0.0, 0.0015, 0.0, 1.0},
        {52, 68, 0.0, 0.003, 0.0, 1.0},
        {1, 27, 0.032, 0.32, 0.41, 1.0},
    };

    /** A bus's nominal voltage, kV. */
    static double nominalKv(int bus) {
        if (bus >= 53) {
            return 20.0;
        }
        return switch (bus) {
            case 12 -> 138.0;
            case 20 -> 230.0;
            default -> 345.0;
        };
    }

    /** Whether a branch of the data is a transformer: between two voltages, or with a ratio off 1. */
    static boolean isTransformer(double[] branch) {
        return nominalKv((int) branch[0]) != nominalKv((int) branch[1]) || branch[5] != 0.0 && branch[5] != 1.0;
    }

    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("ieee68-dc", "test");
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
        Set<String> ids = new HashSet<>();
        for (double[] branch : BRANCHES) {
            addBranch(network, branch, ids);
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
            if (isTransformer(branch)) {
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

    /** The most a machine may give, MW: the area equivalents' 10 to 12 GVA past it, OpenLoadFlow would leave the slack out of the
     * slack's distribution, a generator past 10 GW implausible to it. */
    private static final double MAX_P_MW = 9000.0;

    /** A machine of the case, on its own rating, regulating its bus at the data's voltage with no reactive limit; the slack alone
     * takes the losses. */
    private static void addMachine(Network network, double[] data) {
        int n = (int) data[0];
        VoltageLevel level = network.getVoltageLevel("VL" + n);
        Generator generator = level.newGenerator()
                .setId("G" + n)
                .setBus("B" + n).setConnectableBus("B" + n)
                .setMinP(0.0).setMaxP(Math.min(data[3], MAX_P_MW)).setRatedS(data[3])
                .setTargetP(data[1]).setLocalTargetV(data[2] * level.getNominalV()).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        generator.newMinMaxReactiveLimits().setMinQ(-99999.0).setMaxQ(99999.0).add();
        generator.newExtension(ActivePowerControlAdder.class).withParticipate(n == SLACK).withDroop(4.0).add();
    }

    /**
     * A branch of the case: a line in ohm and siemens from its per unit on its buses' voltage, a second circuit between the same
     * buses L.._2; a transformer ({@link #isTransformer}) with its ratio on its from bus's side - its rated voltages that ratio, 1
     * if the data give none, times the buses' -, its impedance on its other side, as MATPOWER has it. A branch without
     * resistance, line or transformer, given one, X/R {@link #WINDING_X_OVER_R}.
     */
    private static void addBranch(Network network, double[] branch, Set<String> ids) {
        int from = (int) branch[0];
        int to = (int) branch[1];
        double kv = nominalKv(to);
        double baseOhm = kv * kv / BASE_MVA;
        if (!isTransformer(branch)) {
            String id = "L" + from + "_" + to;
            if (!ids.add(id)) {
                id += "_2";
                ids.add(id);
            }
            double halfB = branch[4] / baseOhm / 2;
            double r = branch[2] == 0.0 ? branch[3] / WINDING_X_OVER_R : branch[2];
            network.newLine()
                    .setId(id)
                    .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                    .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                    .setR(r * baseOhm).setX(branch[3] * baseOhm)
                    .setG1(0.0).setB1(halfB).setG2(0.0).setB2(halfB)
                    .add();
        } else {
            double ratio = branch[5] == 0.0 ? 1.0 : branch[5];
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

    /** CIGRE B4's +-200 kV DC OHL, a pole and a km: resistance in ohm, inductance in H, capacitance in F. */
    static final double OHL_R = 0.0133;
    static final double OHL_L = 0.8273e-3;
    static final double OHL_C = 0.0139e-6;

    /** One DC line: its id, its ends, its length in km. */
    record DcLine(String id, String node1, String node2, double km) {
    }

    static final List<DcLine> DC_LINES = List.of(
            new DcLine("D16_33", "BD16", "BD33", 300.0),
            new DcLine("D33_41", "BD33", "BD41", 250.0),
            new DcLine("D41_52", "BD41", "BD52", 300.0),
            new DcLine("D52_16", "BD52", "BD16", 400.0),
            new DcLine("D33_52", "BD33", "BD52", 200.0));

    private static void addDcGrid(Network network) {
        for (String node : List.of("BD16", "BD33", "BD41", "BD52")) {
            network.newDcNode().setId(node).setNominalV(400.0).add();
        }
        // A symmetric monopole's two poles in series, 2 R.
        for (DcLine line : DC_LINES) {
            network.newDcLine().setId(line.id()).setDcNode1(line.node1()).setDcNode2(line.node2()).setR(2 * OHL_R * line.km()).add();
        }
        VoltageSourceConverter cs16 = addStation(network, "CS16", "B16", "BD16", 800.0);
        VoltageSourceConverter cs33 = addStation(network, "CS33", "B33", "BD33", 800.0);
        VoltageSourceConverter cs41 = addStation(network, "CS41", "B41", "BD41", 600.0);
        VoltageSourceConverter cs52 = addStation(network, "CS52", "B52", "BD52", 600.0);
        cs16.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        // Droops of 10 pu of their rating per pu of 400 kV, as CIGRE DCS2's.
        onDroop(cs33.setTargetP(-400.0), -400.0 / (10.0 * 800.0));
        onDroop(cs41.setTargetP(300.0), -400.0 / (10.0 * 600.0));
        onDroop(cs52.setTargetP(-300.0), -400.0 / (10.0 * 600.0));
        for (VoltageSourceConverter converter : List.of(cs33, cs41, cs52)) {
            converter.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }
    }

    /** One converter station, as {@link Ieee39DcNetwork}'s: its transformer TC.. from {@code acBus} to its converter's 220 kV bus
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

    private Ieee68DcNetwork() {
    }
}
