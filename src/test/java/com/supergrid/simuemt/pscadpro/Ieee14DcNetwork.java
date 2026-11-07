/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.ieeecdf.model.IeeeCdfModel;
import com.powsybl.ieeecdf.model.IeeeCdfReader;
import com.powsybl.ieeecdf.model.elements.IeeeCdfBranch;
import com.powsybl.ieeecdf.model.elements.IeeeCdfBus;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The IEEE 14-bus test system with a meshed multi-terminal DC grid, as this project runs it in PSCAD Professional: {@link #create}
 * builds it flat, {@code ieee14-dc.xiidm} is what it writes, {@code ieee14-dc-extensions.xml} its EMT models; {@link #load} reads both.
 *
 * <p>The AC network is the IEEE 14-bus case of the University of Washington's archive, as powsybl ships it ({@code ieee14cdf.txt},
 * powsybl-ieee-cdf-model), read in per unit of 100 MVA and given voltages its kV can hold - every bus's: 132 kV for buses 1 to 5, 33 kV
 * for 6 to 14. The case gives none, and powsybl's own conversion of it guesses 135, 12, 14 and 20 kV, joining buses 7, 8 and 9 by
 * lines of negative charging that stand for the voltage ratios no line has: a load flow's, not a circuit's. Here buses 7, 8 and 9 are
 * on one voltage, 7 the star point of the three-winding transformer the case's branches 4-7, 4-9, 7-8 and 7-9 make up, 8 its tertiary,
 * where the synchronous condenser stands. Its machines are the case's, on their load flow: G1, the slack, and G2 producing; G3, G6
 * and G8 synchronous condensers. Their dynamic data are Dynawo's IEEE 14-bus test case's, in the extensions.
 *
 * <p>The DC grid is this project's own, not a published one: four stations on a 400 kV monopole earthed at each, its lines a meshed
 * triangle between buses 2, 3 and 9, and an offshore wind park's link to two of them:
 * <pre>
 *   CS2 (bus 2, 132 kV, 200 MVA) V_DC 400 kV      BD2 ==OHL 100 km== BD3 ==OHL 100 km== BD9 ==OHL 150 km== BD2
 *   CS3 (bus 3, 132 kV, 200 MVA) droop, -120 MW   BDW ==cable 150 km== BD2, BDW ==cable 100 km== BD9
 *   CS9 (bus 9, 33 kV, 100 MVA)  droop, -60 MW
 *   CSW (BW, 145 kV, 200 MVA)    grid-forming, its island's, WTW 150 MW
 * </pre>
 * The DC lines CIGRE B4's +-200 kV types as one 400 kV conductor, as {@link CigreB4Network}'s: OHLs of 0.0133 ohm, 0.8273 mH and
 * 0.0139 uF a pole and a km, cables of 0.011 ohm, 2.615 mH and 0.2185 uF; 2 R and 2 L, half the C, their L and C the extensions'.
 * The converters of 220 kV AC behind YNy transformers of 0.006 + j0.18 pu on their rating, as CIGRE B4's. CS3 and CS9 deliver the
 * wind's 150 MW to the AC network, and 30 MW more CS2 takes from it; CS3 and CS9 hold their 220 kV bus at 1 pu, CS2 none of its
 * reactive power at its PCC; CSW its island's 220 kV bus.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class Ieee14DcNetwork {

    /** The network, from its flat files: {@code ieee14-dc.xiidm} and its extensions. */
    static Network load() {
        return EmtExtensionsXmlReader.readNetwork(resource("/ieee14-dc.xiidm"), resource("/ieee14-dc-extensions.xml"));
    }

    private static InputStream resource(String name) {
        return Objects.requireNonNull(Ieee14DcNetwork.class.getResourceAsStream(name), "No resource " + name);
    }

    /** The X/R of the case's transformer windings, which it gives no resistance. */
    private static final double WINDING_X_OVER_R = 50.0;

    /** The case's power base, MVA. */
    private static final double BASE_MVA = 100.0;

    /** The machines' ratings, MVA: Dynawo's IEEE 14-bus case's, their per unit's base in the extensions. */
    private static final Map<Integer, Double> MACHINE_RATINGS = Map.of(1, 1211.0, 2, 1120.0, 3, 1650.0, 6, 80.0, 8, 250.0);

    /** Each bus's substation: one per bus, but for the transformers' ends - buses 7 and 9 bus 4's, bus 6 bus 5's. */
    private static int substationOf(int bus) {
        return switch (bus) {
            case 7, 9 -> 4;
            case 6 -> 5;
            default -> bus;
        };
    }

    /** A bus's nominal voltage, kV: 132 kV for buses 1 to 5, the case's HV ones; 33 kV for the others. */
    private static double nominalKv(int bus) {
        return bus <= 5 ? 132.0 : 33.0;
    }

    static Network create() {
        IeeeCdfModel cdf = readCase();
        Network network = NetworkFactory.findDefault().createNetwork("ieee14-dc", "test");
        for (IeeeCdfBus bus : cdf.getBuses()) {
            int n = bus.getNumber();
            Substation substation = network.getSubstation("S" + substationOf(n));
            if (substation == null) {
                substation = network.newSubstation().setId("S" + substationOf(n)).add();
            }
            VoltageLevel level = substation.newVoltageLevel().setId("VL" + n).setNominalV(nominalKv(n)).setTopologyKind(TopologyKind.BUS_BREAKER).add();
            level.getBusBreakerView().newBus().setId("B" + n).add();
        }
        for (IeeeCdfBus bus : cdf.getBuses()) {
            addInjections(network, bus);
        }
        for (IeeeCdfBranch branch : cdf.getBranches()) {
            addBranch(network, branch);
        }
        addDcGrid(network);
        return network;
    }

    private static IeeeCdfModel readCase() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                IeeeCdfModel.class.getResourceAsStream("/ieee14cdf.txt"), "No IEEE 14-bus case"), StandardCharsets.UTF_8))) {
            return new IeeeCdfReader().read(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A bus's load, shunt and machine, the case's: the machine regulating its bus at the case's voltage, within its reactive limits. */
    private static void addInjections(Network network, IeeeCdfBus bus) {
        int n = bus.getNumber();
        VoltageLevel level = network.getVoltageLevel("VL" + n);
        String id = "B" + n;
        if (bus.getActiveLoad() != 0.0 || bus.getReactiveLoad() != 0.0) {
            level.newLoad().setId("L" + n).setBus(id).setConnectableBus(id).setP0(bus.getActiveLoad()).setQ0(bus.getReactiveLoad()).add();
        }
        if (bus.getShuntSusceptance() != 0.0) {
            double siemens = bus.getShuntSusceptance() * BASE_MVA / (level.getNominalV() * level.getNominalV());
            level.newShuntCompensator().setId("SH" + n).setBus(id).setConnectableBus(id).setSectionCount(1)
                    .newLinearModel().setBPerSection(siemens).setMaximumSectionCount(1).add()
                    .add();
        }
        if (MACHINE_RATINGS.containsKey(n)) {
            double minQ = bus.getMinReactivePowerOrVoltageLimit();
            double maxQ = bus.getMaxReactivePowerOrVoltageLimit();
            boolean limited = minQ != 0.0 || maxQ != 0.0;
            Generator generator = level.newGenerator()
                    .setId("G" + n)
                    .setBus(id).setConnectableBus(id)
                    .setMinP(0.0).setMaxP(Math.max(bus.getActiveGeneration(), 1.0) * 10).setRatedS(MACHINE_RATINGS.get(n))
                    .setTargetP(bus.getActiveGeneration()).setLocalTargetV(bus.getDesiredVoltage() * level.getNominalV())
                    .setLocalTargetQ(bus.getReactiveGeneration())
                    .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(true).add()
                    .add();
            generator.newMinMaxReactiveLimits().setMinQ(limited ? minQ : -9999.0).setMaxQ(limited ? maxQ : 9999.0).add();
            // The case's slack, bus 1: its machine alone takes the losses.
            generator.newExtension(ActivePowerControlAdder.class).withParticipate(n == 1).withDroop(4.0).add();
        }
    }

    /**
     * A branch of the case: a line in ohm and siemens from its per unit on its buses' voltage, a transformer with its ratio on its
     * tap bus's side - its rated voltages that ratio times the buses' -, its reactance on its other side. The case's transformer
     * windings - its transformers and the branches 7-8 and 7-9 of the three-winding one - have no resistance: given one here, X/R
     * {@link #WINDING_X_OVER_R}, a winding's own. Without it, nothing bounds how long the DC offset a run from rest starts them with
     * lasts, and PSCAD's writer refuses an event on such a circuit.
     */
    private static void addBranch(Network network, IeeeCdfBranch branch) {
        int from = branch.getTapBusNumber();
        int to = branch.getzBusNumber();
        double kv = nominalKv(to);
        double baseOhm = kv * kv / BASE_MVA;
        double r = branch.getResistance() == 0.0 ? branch.getReactance() / WINDING_X_OVER_R : branch.getResistance();
        if (branch.getFinalTurnsRatio() == 0.0) {
            double halfB = branch.getChargingSusceptance() / baseOhm / 2;
            network.newLine()
                    .setId("L" + from + "_" + to)
                    .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                    .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                    .setR(r * baseOhm).setX(branch.getReactance() * baseOhm)
                    .setG1(0.0).setB1(halfB).setG2(0.0).setB2(halfB)
                    .add();
        } else {
            network.getSubstation("S" + substationOf(from)).newTwoWindingsTransformer()
                    .setId("T" + from + "_" + to)
                    .setVoltageLevel1("VL" + from).setBus1("B" + from).setConnectableBus1("B" + from)
                    .setVoltageLevel2("VL" + to).setBus2("B" + to).setConnectableBus2("B" + to)
                    .setRatedU1(branch.getFinalTurnsRatio() * nominalKv(from)).setRatedU2(kv)
                    .setR(r * baseOhm).setX(branch.getReactance() * baseOhm).setG(0.0).setB(0.0)
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
                .setMinP(0.0).setMaxP(150.0).setRatedS(150.0)
                .setTargetP(150.0).setLocalTargetV(145.0).setLocalTargetQ(0.0)
                .newVoltageRegulation().withMode(RegulationMode.VOLTAGE).withRegulating(false).add()
                .add();
        wind.newExtension(ActivePowerControlAdder.class).withParticipate(false).withDroop(4.0).add();

        for (String node : List.of("BD2", "BD3", "BD9", "BDW")) {
            network.newDcNode().setId(node).setNominalV(400.0).add();
        }
        addDcLine(network, "D2_3", "BD2", "BD3", DC_OHL, 100.0);
        addDcLine(network, "D3_9", "BD3", "BD9", DC_OHL, 100.0);
        addDcLine(network, "D2_9", "BD2", "BD9", DC_OHL, 150.0);
        addDcLine(network, "DW_2", "BDW", "BD2", DC_CABLE, 150.0);
        addDcLine(network, "DW_9", "BDW", "BD9", DC_CABLE, 100.0);

        VoltageSourceConverter cs2 = addStation(network, "CS2", "B2", "BD2", 200.0);
        VoltageSourceConverter cs3 = addStation(network, "CS3", "B3", "BD3", 200.0);
        VoltageSourceConverter cs9 = addStation(network, "CS9", "B9", "BD9", 100.0);
        VoltageSourceConverter csw = addStation(network, "CSW", "BW", "BDW", 200.0);
        cs2.setControlMode(AcDcConverter.ControlMode.V_DC).setTargetVdc(400.0);
        // Droops of 10 pu of their rating per pu of 400 kV, as CIGRE DCS2's.
        onDroop(cs3.setTargetP(-120.0), -400.0 / (10.0 * 200.0));
        onDroop(cs9.setTargetP(-60.0), -400.0 / (10.0 * 100.0));
        for (VoltageSourceConverter converter : List.of(cs3, cs9, csw)) {
            converter.getVoltageRegulation().setMode(RegulationMode.VOLTAGE).setRegulating(true).setTargetValue(220.0);
        }
        // The island's power, which its balancing brings to what the wind park gives less its losses.
        csw.setTargetP(150.0);
    }

    /** A DC line's conductor: a symmetric monopole's two poles in series, 2 R. */
    private static void addDcLine(Network network, String id, String node1, String node2, LineType type, double lengthKm) {
        network.newDcLine().setId(id).setDcNode1(node1).setDcNode2(node2).setR(2 * type.resistance() * lengthKm).add();
    }

    /** One converter station: its transformer TC.. from {@code acBus} to its converter's 220 kV bus BC.. (in the AC bus's substation),
     * the converter between {@code dcNode} and its earthed return {@code dcNode}G, P_PCC at none, no reactive power at its PCC,
     * reactive limits of 0.5 pu of its rating. */
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

    private Ieee14DcNetwork() {
    }
}
