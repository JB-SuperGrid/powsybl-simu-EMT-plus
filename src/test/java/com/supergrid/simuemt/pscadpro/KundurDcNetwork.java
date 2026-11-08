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
import java.util.List;
import java.util.Objects;

/**
 * Kundur's two-area four-machine system with a multi-terminal DC grid in parallel with its tie, as this project runs it in PSCAD
 * Professional: {@link #create} builds it flat, {@code kundur-dc.xiidm} is what it writes, {@code kundur-dc-extensions.xml} its EMT
 * models; {@link #load} reads both.
 *
 * <p>The AC network is the benchmark's, as P. Kundur's <i>Power System Stability and Control</i> (1994, example 12.6) gives its data:
 * two areas of two 900 MVA, 20 kV machines each, behind 20/230 kV step-up transformers of j0.15 pu on 900 MVA, joined by two 220 km
 * circuits through bus 8. Its 230 kV lines of 0.0001 + j0.001 pu and 0.00175 pu of charging a km on 100 MVA; loads of 967 MW and
 * 100 Mvar at bus 7, 1767 MW and 100 Mvar at bus 9, capacitors of 200 and 350 Mvar there. G1, G2 and G4 produce 700 MW, G3 is the
 * slack; G1 and G3 hold 1.03 pu, G2 and G4 1.01. The transformers, which the data give no resistance, X/R
 * {@link #WINDING_X_OVER_R}, as {@link Ieee14DcNetwork}'s: without it, nothing bounds the DC offset a run from rest starts them with.
 * <pre>
 *   G1 -- 1 -- 5 --25 km-- 6 --10 km-- 7 ==110 km== 8 ==110 km== 9 --10 km-- 10 --25 km-- 11 -- 3 -- G3
 *                          |                                                   |
 *                          2 -- G2                                             4 -- G4
 * </pre>
 *
 * <p>The DC grid is this project's own, not a published one: three stations on a 400 kV monopole earthed at each, its OHL beside
 * the tie and an offshore wind park's two cables, a meshed triangle:
 * <pre>
 *   CS7 (bus 7, 230 kV, 300 MVA) droop, 200 MW from area 1   BD7 ==OHL 220 km== BD9
 *   CS9 (bus 9, 230 kV, 500 MVA) V_DC 400 kV, into area 2    BDW ==cable 100 km== BD7, BDW ==cable 150 km== BD9
 *   CSW (BW, 145 kV, 250 MVA)    grid-forming, its island's, WTW 200 MW
 * </pre>
 * The DC lines CIGRE B4's +-200 kV types as one 400 kV conductor, as {@link Ieee14DcNetwork}'s. The converters of 220 kV AC behind
 * YNy transformers of 0.006 + j0.18 pu on their rating. CS9 delivers the wind's 200 MW and CS7's to area 2: the AC tie carries
 * what CS7 takes from area 1 less, G3 produces the wind's less. CS7 and CS9 hold their 220 kV bus at 1 pu, CSW its island's.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class KundurDcNetwork {

    /** The network, from its flat files: {@code kundur-dc.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/kundur-dc.xiidm"), resource("/kundur-dc-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(KundurDcNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** The X/R of the step-up transformers, which the data give no resistance. */
    static final double WINDING_X_OVER_R = 50.0;

    /** The machines' rating, MVA, and voltage, kV. */
    static final double MACHINE_MVA = 900.0;
    private static final double MACHINE_KV = 20.0;
    /** The transmission voltage, kV, and the lines' per unit base, MVA. */
    private static final double KV = 230.0;
    private static final double LINE_BASE_MVA = 100.0;

    /** A 230 kV line's data per km, per unit on 100 MVA and 230 kV. */
    private static final double R_PER_KM = 0.0001;
    private static final double X_PER_KM = 0.001;
    private static final double B_PER_KM = 0.00175;

    /** One machine: its bus, its HV bus, its active power, MW (the slack's its load flow's start), its voltage, pu. */
    private record Machine(int bus, int hvBus, double p, double v) {
    }

    static final List<Integer> MACHINES = List.of(1, 2, 3, 4);
    private static final List<Machine> MACHINE_DATA = List.of(
            new Machine(1, 5, 700.0, 1.03),
            new Machine(2, 6, 700.0, 1.01),
            new Machine(3, 11, 719.0, 1.03),
            new Machine(4, 10, 700.0, 1.01));
    /** The slack, bus 3's machine: its angle, -6.8 degrees, the data's. */
    static final int SLACK = 3;

    static Network create() {
        Network network = NetworkFactory.findDefault().createNetwork("kundur-dc", "test");
        for (int bus = 5; bus <= 11; bus++) {
            busOf(network, substationOf(bus), bus, KV);
        }
        for (Machine machine : MACHINE_DATA) {
            addMachine(network, machine);
        }
        addLoad(network, 7, 967.0, 100.0, 200.0);
        addLoad(network, 9, 1767.0, 100.0, 350.0);
        addLine(network, "L5_6", 5, 6, 25.0);
        addLine(network, "L6_7", 6, 7, 10.0);
        addLine(network, "L7_8a", 7, 8, 110.0);
        addLine(network, "L7_8b", 7, 8, 110.0);
        addLine(network, "L8_9a", 8, 9, 110.0);
        addLine(network, "L8_9b", 8, 9, 110.0);
        addLine(network, "L9_10", 9, 10, 10.0);
        addLine(network, "L10_11", 10, 11, 25.0);
        addDcGrid(network);
        return network;
    }

    /** Each bus's substation: a machine's bus its HV bus's. */
    private static String substationOf(int bus) {
        return "S" + switch (bus) {
            case 1 -> 5;
            case 2 -> 6;
            case 3 -> 11;
            case 4 -> 10;
            default -> bus;
        };
    }

    private static VoltageLevel busOf(Network network, String substationId, int bus, double kv) {
        Substation substation = network.getSubstation(substationId);
        if (substation == null) {
            substation = network.newSubstation().setId(substationId).add();
        }
        VoltageLevel level = substation.newVoltageLevel().setId("VL" + bus).setNominalV(kv).setTopologyKind(TopologyKind.BUS_BREAKER).add();
        level.getBusBreakerView().newBus().setId("B" + bus).add();
        return level;
    }

    /** A machine on its 20 kV bus, regulating it, and its step-up transformer T.. to its 230 kV bus. */
    private static void addMachine(Network network, Machine machine) {
        int n = machine.bus();
        String id = "B" + n;
        VoltageLevel level = busOf(network, substationOf(n), n, MACHINE_KV);
        Generator generator = level.newGenerator()
                .setId("G" + n)
                .setBus(id).setConnectableBus(id)
                .setMinP(0.0).setMaxP(MACHINE_MVA).setRatedS(MACHINE_MVA)
                .setTargetP(machine.p()).setLocalTargetV(machine.v() * MACHINE_KV).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                .add();
        generator.newMinMaxReactiveLimits().setMinQ(-9999.0).setMaxQ(9999.0).add();
        // G3 alone takes the losses, the data's slack.
        generator.newExtension(ActivePowerControlAdder.class).withParticipate(n == SLACK).withDroop(4.0).add();
        double baseOhm = KV * KV / MACHINE_MVA;
        double x = 0.15 * baseOhm;
        int hv = machine.hvBus();
        network.getSubstation(substationOf(n)).newTwoWindingsTransformer()
                .setId("T" + n + "_" + hv)
                .setVoltageLevel1("VL" + n).setBus1(id).setConnectableBus1(id)
                .setVoltageLevel2("VL" + hv).setBus2("B" + hv).setConnectableBus2("B" + hv)
                .setRatedU1(MACHINE_KV).setRatedU2(KV).setRatedS(MACHINE_MVA)
                .setR(x / WINDING_X_OVER_R).setX(x).setG(0.0).setB(0.0)
                .add();
    }

    /** A load and its capacitor bank, Mvar at 230 kV. */
    private static void addLoad(Network network, int bus, double p, double q, double capacitorMvar) {
        VoltageLevel level = network.getVoltageLevel("VL" + bus);
        String id = "B" + bus;
        level.newLoad().setId("L" + bus).setBus(id).setConnectableBus(id).setP0(p).setQ0(q).add();
        level.newShuntCompensator().setId("C" + bus).setBus(id).setConnectableBus(id).setSectionCount(1)
                .newLinearModel().setBPerSection(capacitorMvar / (KV * KV)).setMaximumSectionCount(1).add()
                .add();
    }

    /** A 230 kV line of {@code km}, in ohm and siemens from its per unit. */
    private static void addLine(Network network, String id, int from, int to, double km) {
        double baseOhm = KV * KV / LINE_BASE_MVA;
        double halfB = B_PER_KM * km / baseOhm / 2;
        network.newLine()
                .setId(id)
                .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                .setR(R_PER_KM * km * baseOhm).setX(X_PER_KM * km * baseOhm)
                .setG1(0.0).setB1(halfB).setG2(0.0).setB2(halfB)
                .add();
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
                .setMinP(0.0).setMaxP(200.0).setRatedS(200.0)
                .setTargetP(200.0).setLocalTargetV(145.0).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).add()
                .add();
        wind.newExtension(ActivePowerControlAdder.class).withParticipate(false).withDroop(4.0).add();

        for (String node : List.of("BD7", "BD9", "BDW")) {
            network.newDcNode().setId(node).setNominalV(400.0).add();
        }
        addDcLine(network, "D7_9", "BD7", "BD9", DC_OHL, 220.0);
        addDcLine(network, "DW_7", "BDW", "BD7", DC_CABLE, 100.0);
        addDcLine(network, "DW_9", "BDW", "BD9", DC_CABLE, 150.0);

        VoltageSourceConverter cs7 = addStation(network, "CS7", "B7", "BD7", 300.0);
        VoltageSourceConverter cs9 = addStation(network, "CS9", "B9", "BD9", 500.0);
        VoltageSourceConverter csw = addStation(network, "CSW", "BW", "BDW", 250.0);
        cs9.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        // A droop of 10 pu of its rating per pu of 400 kV, as CIGRE DCS2's: 200 MW from area 1 at 400 kV.
        onDroop(cs7.setTargetP(200.0), -400.0 / (10.0 * 300.0));
        for (VoltageSourceConverter converter : List.of(cs7, cs9, csw)) {
            converter.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }
        // The island's power, which its balancing brings to what the wind park gives less its losses.
        csw.setTargetP(200.0);
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

    private KundurDcNetwork() {
    }
}
