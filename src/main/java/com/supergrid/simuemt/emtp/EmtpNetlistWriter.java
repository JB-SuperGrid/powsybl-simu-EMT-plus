/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.ThreeWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescue;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.circuit.LoadBranches;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.circuit.PhaseShiftCoefficients;
import com.powsybl.simuemt.extensions.circuit.TapCorrectedImpedance;
import com.powsybl.simuemt.extensions.circuit.ThreeWindingsTransformerWindings;
import com.powsybl.simuemt.extensions.circuit.TransformerWindings;
import com.powsybl.simuemt.extensions.circuit.VectorGroupOffsets;
import com.powsybl.simuemt.extensions.circuit.ZeroSequenceFluxPath;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusEmf;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * An IIDM network, its load flow solved, as an EMTP netlist ({@code <network>.net}) for {@link EmtpRunner}: the circuit upstream's
 * backends write - ATP's deck of the same network element for element, the same numerical aids included ({@link NumericalAids}) -
 * in EMTP's devices, three single-phase ones per three-phase element, the nodes of phase a, b and c of bus {@code B1} named {@code
 * B1a}, {@code B1b}, {@code B1c}.
 *
 * <ul>
 *     <li>a line: its series resistance and inductance ({@code _RLC}), {@code 16L/Dt} across the inductance; and at each end, half its
 *     shunt susceptance as a capacitance to earth, {@code Dt/16C} in series with it;</li>
 *     <li>a two-winding transformer, star-earthed on both sides as ATP's three single-phase units: per phase an ideal unit
 *     ({@code _Tr0}, its ratio {@code V2/V1}), half the leakage - IIDM's r/x, a ratio tap changer's step folded in ({@link
 *     TapCorrectedImpedance}) - on each winding, the side-1 half referred to it by {@code (V1/V2)^2}; and, the excitation admittance
 *     referred the other way, a core-loss resistance and a magnetizing inductance from the node between winding 1's leakage and the
 *     ideal unit to earth, where ATP hangs them. No {@code 16L/Dt} across a leakage, which ATP keeps inside its component. With
 *     winding connections ({@code TwoWindingsTransformerFortescue} and its clock number), each unit's windings across the terminals
 *     {@link TransformerWindings} gives - a star winding from its phase to the star point, a delta one across two phases -, each its
 *     own rated voltage and its share of the leakage, three times its star-equivalent one in a delta, the admittance across winding 1
 *     a third in a delta; a star point earth when solidly earthed, else a node of its own with its grounding impedance, or 0.003 uF
 *     to earth isolated, and 0.003 uF from each phase of a delta side, as ATP has them;</li>
 *     <li>a load, constant impedance: per phase, from {@link LoadBranches} at the load flow's voltage, its resistance then its
 *     inductance to earth through a node of its own, {@code 16L/Dt} across the inductance;</li>
 *     <li>an infinite-bus generator: per phase a cosine source ({@code _Vsine}, active in the steady state) at the EMF that puts
 *     its bus on the load flow ({@link InfiniteBusEmf}), behind the network's short-circuit resistance - its milliohm at least - and
 *     inductance, {@code 16L/Dt} across it;</li>
 *     <li>every bus phase: its 1 Gohm earth reference, whose voltage scope ({@code ?v}, scope {@code vb:REF_<node>}) is the node's
 *     voltage.</li>
 * </ul>
 * And each {@link BusFault} the run goes through, branch by branch ({@link BusFault#branches}): an ideal switch ({@code _Sw0}) from the
 * bus phase to a node of its own, closing and opening at {@link NumericalAids#switchingInstant}, then the branch's closed resistance
 * ({@link BusFault#onResistanceOhm}) to earth or to the other phase - as ATP writes it. Cleared at its current's first zero after the
 * order, the switch's current margin 0, or at the order, a margin no current reaches - ATP's own.
 *
 * <p>EMTP starts the run from its own steady-state solution, every source active from {@code -1} s. Anything else the network holds -
 * another kind of generator or load, a line's shunt conductance,
 * a switch - or the run goes through is refused, said
 * so, until written.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class EmtpNetlistWriter {

    private static final String[] PHASES = {"a", "b", "c"};
    /** What EMTP takes as "active in the steady state" for a source's start, and as never for its stop. */
    private static final String STEADY_STATE_START = "-1";
    private static final String NEVER = "1E15";
    /** A switch's current margin no current reaches: it opens at the order, whatever its current - ATP's {@code ANY_CURRENT_AMPERES}. */
    private static final double ANY_CURRENT_AMPERES = 1.0e9;

    private EmtpNetlistWriter() {
    }

    /** The scope of node {@code node}'s voltage, as {@link EmtpOutput} names it: its earth reference's branch voltage. */
    public static String voltageScope(String busId, char phase) {
        return "vb:REF_" + node(busId, phase);
    }

    /** {@code network}'s netlist, written into {@code directory}; refused for a network this does not write yet. */
    public static Path write(Network network, Path directory, EmtpSimulationSettings settings) {
        return write(network, directory, settings, List.of());
    }

    /** {@code network}'s netlist through {@code events}, written into {@code directory}; refused for a network or an event this does
     * not write yet. */
    public static Path write(Network network, Path directory, EmtpSimulationSettings settings, List<? extends EmtEvent> events) {
        List<String> refused = new ArrayList<>(unsupported(network));
        for (EmtEvent event : events) {
            if (!(event instanceof BusFault fault)) {
                refused.add("event " + event.getClass().getSimpleName() + " is of a kind not written yet");
            } else if (network.getBusBreakerView().getBus(fault.busId()) == null) {
                refused.add("fault '" + fault.id() + "' is on bus '" + fault.busId() + "', which the network has not");
            }
        }
        if (!refused.isEmpty()) {
            throw new PowsyblException("SimuEmt Plus: EMTP cannot write network '" + network.getId() + "' yet: " + String.join("; ", refused));
        }
        Devices devices = new Devices();
        for (Line line : network.getLines()) {
            writeLine(devices, line, settings);
        }
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            writeTransformer(devices, transformer, settings);
        }
        for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
            writeThreeWindingsTransformer(devices, transformer, settings);
        }
        for (Load load : network.getLoads()) {
            writeLoad(devices, load, settings);
        }
        for (Generator generator : network.getGenerators()) {
            writeInfiniteBus(devices, generator, settings);
        }
        int faultBranch = 0;
        for (EmtEvent event : events) {
            for (BusFault.Branch branch : ((BusFault) event).branches()) {
                writeFaultBranch(devices, (BusFault) event, branch, ++faultBranch, settings);
            }
        }
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            for (String phase : PHASES) {
                String node = node(bus.getId(), phase.charAt(0));
                devices.comment(NumericalAids.COMMENT_MARKER + ": 1 Gohm earth reference of bus " + bus.getId() + " - its voltage scope the bus's");
                devices.add("_RLC", "REF_" + node, List.of(node), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,?v,");
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("! Generated by SimuEmt Plus for network '" + network.getId() + "'");
        lines.add("! " + NumericalAids.FILE_LEGEND);
        lines.add("Dt=" + number(settings.timeStepSeconds()) + ";");
        lines.add("tmax=" + number(settings.stopTimeSeconds()) + ";");
        lines.addAll(devices.lines());
        Path netlist = directory.resolve(name(network) + ".net");
        try {
            Files.createDirectories(directory);
            Files.write(netlist, lines, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not write " + netlist, e);
        }
        return netlist;
    }

    private static void writeLine(Devices lines, Line line, EmtpSimulationSettings settings) {
        double inductance = line.getX() / omega(settings);
        for (String phase : PHASES) {
            String from = node(line.getTerminal1().getBusBreakerView().getBus().getId(), phase.charAt(0));
            String to = node(line.getTerminal2().getBusBreakerView().getBus().getId(), phase.charAt(0));
            lines.add("_RLC", id(line) + "_" + phase, List.of(from, to), rl(line.getR(), inductance));
            damping(lines, id(line) + "_D" + phase, from, to, inductance, settings, "the series inductance");
            charging(lines, id(line) + "_C1" + phase, from, line.getB1(), settings);
            charging(lines, id(line) + "_C2" + phase, to, line.getB2(), settings);
        }
    }

    private static void writeTransformer(Devices lines, TwoWindingsTransformer transformer, EmtpSimulationSettings settings) {
        TransformerWindings windings = TransformerWindings.of(transformer);
        if (windings != null) {
            writeWindingConnectedTransformer(lines, transformer, windings, settings);
            return;
        }
        if (transformer.getOptionalPhaseTapChanger().isPresent()) {
            writePhaseShiftingTransformer(lines, transformer, settings);
            return;
        }
        TapCorrectedImpedance corrected = TapCorrectedImpedance.of(transformer);
        double ratedU1 = transformer.getRatedU1();
        double ratedU2 = corrected.ratedU();
        double toSide1 = ratedU1 * ratedU1 / (ratedU2 * ratedU2);
        double l1 = 0.5 * corrected.x() * toSide1 / omega(settings);
        double l2 = 0.5 * corrected.x() / omega(settings);
        // The excitation admittance, given at side 2, referred to winding 1 - the other way from the leakage.
        double g1 = corrected.g() / toSide1;
        double b1 = Math.abs(corrected.b()) / toSide1;
        String bus1 = transformer.getTerminal1().getBusBreakerView().getBus().getId();
        String bus2 = transformer.getTerminal2().getBusBreakerView().getBus().getId();
        for (String phase : PHASES) {
            String winding1 = "T" + id(transformer) + "_1" + phase;
            String winding2 = "T" + id(transformer) + "_2" + phase;
            lines.add("_RLC", id(transformer) + "_W1" + phase, List.of(node(bus1, phase.charAt(0)), winding1), rl(0.5 * corrected.r() * toSide1, l1));
            // Its two windings each to earth: the earth pins left blank, as EMTPWorks writes them.
            lines.add("_Tr0", id(transformer) + "_" + phase, List.of(winding1, "", winding2, ""), number(ratedU2 / ratedU1) + ",,,");
            lines.add("_RLC", id(transformer) + "_W2" + phase, List.of(winding2, node(bus2, phase.charAt(0))), rl(0.5 * corrected.r(), l2));
            if (g1 != 0.0) {
                lines.add("_RLC", id(transformer) + "_RM" + phase, List.of(winding1), rl(1.0 / g1, 0.0));
            }
            if (b1 != 0.0) {
                lines.add("_RLC", id(transformer) + "_LM" + phase, List.of(winding1), rl(0.0, 1.0 / (omega(settings) * b1)));
            }
        }
    }

    /** A transformer with winding connections, as ATP wires its three single-phase units - see the class's javadoc. */
    private static void writeWindingConnectedTransformer(Devices lines, TwoWindingsTransformer transformer, TransformerWindings windings,
                                                         EmtpSimulationSettings settings) {
        TapCorrectedImpedance corrected = TapCorrectedImpedance.of(transformer);
        double ratedU1 = transformer.getRatedU1();
        double ratedU2 = corrected.ratedU();
        double toSide1 = ratedU1 * ratedU1 / (ratedU2 * ratedU2);
        double factor1 = TransformerWindings.windingImpedanceFactor(windings.connection1());
        double factor2 = TransformerWindings.windingImpedanceFactor(windings.connection2());
        double winding1Kv = TransformerWindings.windingVoltage(windings.connection1(), ratedU1);
        double winding2Kv = TransformerWindings.windingVoltage(windings.connection2(), ratedU2);
        // The excitation admittance, star-equivalent at side 2, referred to winding 1: a third of it across a delta winding.
        double g1 = corrected.g() / toSide1 / factor1;
        double b1 = Math.abs(corrected.b()) / toSide1 / factor1;
        String id = id(transformer);
        String[] side1 = phaseNodes(transformer.getTerminal1().getBusBreakerView().getBus().getId());
        double alpha = transformer.getOptionalPhaseTapChanger().map(changer -> changer.getCurrentStep().getAlpha()).orElse(0.0);
        if (alpha != 0.0) {
            // A phase tap changer's angle, cascaded ahead of the bank on side 1: the bank's displacement is wiring, the tap's an angle
            // no wiring produces; its rho is already in side 2's rated voltage.
            side1 = coupling(lines, "PS" + id(transformer), side1, 1.0, alpha);
        }
        String[] side2 = phaseNodes(transformer.getTerminal2().getBusBreakerView().getBus().getId());
        String neutral1 = neutral(windings.connection1(), windings.groundingR1(), windings.groundingX1(), "N" + id + "_1");
        String neutral2 = neutral(windings.connection2(), windings.groundingR2(), windings.groundingX2(), "N" + id + "_2");
        ZeroSequenceFluxPath fluxPath = ZeroSequenceFluxPath.of(transformer.getExtension(TwoWindingsTransformerFortescue.class));
        for (int unit = 0; unit < 3; unit++) {
            String phase = PHASES[unit];
            int[] terminals1 = windings.side1Terminals(unit);
            int[] terminals2 = windings.side2Terminals(unit);
            String winding1 = "T" + id + "_1" + phase;
            String winding2 = "T" + id + "_2" + phase;
            String end1 = terminal(side1, terminals1[1], neutral1);
            lines.add("_RLC", id + "_W1" + phase, List.of(terminal(side1, terminals1[0], neutral1), winding1),
                    rl(factor1 * 0.5 * corrected.r() * toSide1, factor1 * 0.5 * corrected.x() * toSide1 / omega(settings)));
            if (fluxPath == null) {
                lines.add("_Tr0", id + "_" + phase, List.of(winding1, end1, winding2, terminal(side2, terminals2[1], neutral2)),
                        number(winding2Kv / winding1Kv) + ",,,");
            } else {
                // A three-limb core: a third winding on each unit, the tank path's closed delta at side 2's line voltage.
                String tank = "T" + id + "_Z" + phase;
                lines.add("_Tr0", id + "_" + phase, List.of(winding1, end1, winding2, terminal(side2, terminals2[1], neutral2), tank,
                        tankCorner(id, unit + 1)), number(winding1Kv) + ",2,1,0,0,0,0", number(winding2Kv), number(ratedU2));
                lines.add("_RLC", id + "_WZ" + phase, List.of(tankCorner(id, unit), tank),
                        rl(3.0 * fluxPath.r(), 3.0 * fluxPath.x() / omega(settings)));
            }
            lines.add("_RLC", id + "_W2" + phase, List.of(winding2, terminal(side2, terminals2[0], neutral2)),
                    rl(factor2 * 0.5 * corrected.r(), factor2 * 0.5 * corrected.x() / omega(settings)));
            List<String> across1 = end1.isEmpty() ? List.of(winding1) : List.of(winding1, end1);
            if (g1 != 0.0) {
                lines.add("_RLC", id + "_RM" + phase, across1, rl(1.0 / g1, 0.0));
            }
            if (b1 != 0.0) {
                lines.add("_RLC", id + "_LM" + phase, across1, rl(0.0, 1.0 / (omega(settings) * b1)));
            }
        }
        earthReference(lines, id + "_1", windings.connection1(), windings.groundingR1(), windings.groundingX1(), neutral1, side1, settings);
        earthReference(lines, id + "_2", windings.connection2(), windings.groundingR2(), windings.groundingX2(), neutral2, side2, settings);
        if (fluxPath != null) {
            tankEarthReferences(lines, id);
        }
    }

    /** Corner {@code corner} (modulo 3) of the tank path's fictitious delta: the node its unit's winding starts from, the previous one's
     * ends on. */
    private static String tankCorner(String id, int corner) {
        return "TANK" + id + "_" + (corner % 3);
    }

    /** The tank path's delta closes on itself and touches nothing else: 0.003 uF from each corner to earth, as ATP. */
    private static void tankEarthReferences(Devices lines, String id) {
        for (int corner = 0; corner < 3; corner++) {
            lines.comment(NumericalAids.COMMENT_MARKER + ": 0.003 uF earth reference of the core's fictitious delta");
            lines.add("_RLC", id + "_CZ" + corner, List.of(tankCorner(id, corner)), "0,0," + number(NumericalAids.FLOATING_WINDING_EARTH_REFERENCE_F) + ",0,0,");
        }
    }

    /**
     * A three-winding transformer as ATP writes it: per phase one three-winding unit, here winding 1's leakage, an ideal unit of one
     * primary and two secondaries ({@code _Tr0}, six pins, each winding at its own rated voltage), and windings 2's and 3's leakages.
     * Each winding its leg's r/x, a ratio tap changer's step folded in, referred from the star bus's voltage to the leg's ({@link
     * TapCorrectedImpedance#referredFromStarBus}); the three legs' excitation admittances summed, referred to winding 1, across it.
     * Without winding connections each leg is star-earthed at its own rated voltage; with them ({@code ThreeWindingsTransformerFortescue}
     * and its clock numbers), wired as {@link ThreeWindingsTransformerWindings} gives, as a two-winding transformer is.
     */
    private static void writeThreeWindingsTransformer(Devices lines, ThreeWindingsTransformer transformer, EmtpSimulationSettings settings) {
        ThreeWindingsTransformerWindings windings = ThreeWindingsTransformerWindings.of(transformer);
        if (windings == null && transformer.getLegStream().anyMatch(leg -> leg.getOptionalPhaseTapChanger().isPresent())) {
            writePhaseShiftingCapableTransformer(lines, transformer, settings);
            return;
        }
        ZeroSequenceFluxPath fluxPath = ZeroSequenceFluxPath.of(transformer.getExtension(ThreeWindingsTransformerFortescue.class));
        List<ThreeWindingsTransformer.Leg> legs = List.of(transformer.getLeg1(), transformer.getLeg2(), transformer.getLeg3());
        TapCorrectedImpedance[] values = new TapCorrectedImpedance[3];
        String[][] legNodes = new String[3][];
        String[] neutrals = new String[3];
        WindingConnectionType[] connections = new WindingConnectionType[3];
        String id = id(transformer);
        for (int k = 0; k < 3; k++) {
            values[k] = TapCorrectedImpedance.ofLeg(legs.get(k)).referredFromStarBus(transformer.getRatedU0());
            legNodes[k] = phaseNodes(legs.get(k).getTerminal().getBusBreakerView().getBus().getId());
            double alpha = legs.get(k).getOptionalPhaseTapChanger().map(changer -> changer.getCurrentStep().getAlpha()).orElse(0.0);
            if (alpha != 0.0) {
                // The leg's tap angle, cascaded between its bus and its windings. The leg lags the star by the tap's angle
                // (PhaseShiftCoefficients#legAlphaDegrees), but this block runs the other way, from the leg's bus back to the winding:
                // minus that, as ATP writes it - with legAlphaDegrees itself, the leg's bus came out 2 alpha off the load flow.
                legNodes[k] = coupling(lines, "PS" + id + "_" + (k + 1), legNodes[k], 1.0, -PhaseShiftCoefficients.legAlphaDegrees(alpha));
            }
            connections[k] = windings == null ? WindingConnectionType.Y_GROUNDED : windings.connection(k + 1);
            neutrals[k] = windings == null ? "" : neutral(connections[k], windings.groundingR(k + 1), windings.groundingX(k + 1), "N" + id + "_" + (k + 1));
        }
        // The legs' excitation admittances at winding 1, a third of it across a delta winding.
        double g1 = 0.0;
        double b1 = 0.0;
        for (TapCorrectedImpedance leg : values) {
            double toWinding1 = leg.ratedU() / values[0].ratedU() * (leg.ratedU() / values[0].ratedU());
            g1 += leg.g() * toWinding1;
            b1 += Math.abs(leg.b()) * toWinding1;
        }
        g1 /= TransformerWindings.windingImpedanceFactor(connections[0]);
        b1 /= TransformerWindings.windingImpedanceFactor(connections[0]);
        double[] windingKv = new double[3];
        for (int k = 0; k < 3; k++) {
            // Without connections, each winding at its leg's rated voltage, as ATP's VRAT fields: only the ratios count.
            windingKv[k] = windings == null ? values[k].ratedU() : TransformerWindings.windingVoltage(connections[k], values[k].ratedU());
        }
        for (int unit = 0; unit < 3; unit++) {
            String phase = PHASES[unit];
            String[] from = new String[3];
            String[] to = new String[3];
            for (int k = 0; k < 3; k++) {
                int[] terminals = windings == null ? new int[] {unit, TransformerWindings.NEUTRAL} : windings.terminals(k + 1, unit);
                from[k] = terminal(legNodes[k], terminals[0], neutrals[k]);
                to[k] = terminal(legNodes[k], terminals[1], neutrals[k]);
            }
            String primary = "T" + id + "_1" + phase;
            String winding2 = "T" + id + "_2" + phase;
            String winding3 = "T" + id + "_3" + phase;
            String[] inner = {primary, winding2, winding3};
            for (int k = 0; k < 3; k++) {
                double factor = TransformerWindings.windingImpedanceFactor(connections[k]);
                lines.add("_RLC", id + "_W" + (k + 1) + phase, List.of(from[k], inner[k]),
                        rl(factor * values[k].r(), factor * values[k].x() / omega(settings)));
            }
            if (fluxPath == null) {
                lines.add("_Tr0", id + "_" + phase, List.of(primary, to[0], winding2, to[1], winding3, to[2]),
                        number(windingKv[0]) + ",2,1,0,0,0,0", number(windingKv[1]), number(windingKv[2]));
            } else {
                // A three-limb core: a fourth winding on each unit, the tank path's closed delta at the star bus's voltage - the base
                // IIDM gives every leg's impedance on, this one's included.
                String tank = "T" + id + "_Z" + phase;
                lines.add("_Tr0", id + "_" + phase, List.of(primary, to[0], winding2, to[1], winding3, to[2], tank, tankCorner(id, unit + 1)),
                        number(windingKv[0]) + ",3,1,0,0,0,0", number(windingKv[1]), number(windingKv[2]), number(transformer.getRatedU0()));
                lines.add("_RLC", id + "_WZ" + phase, List.of(tankCorner(id, unit), tank),
                        rl(3.0 * fluxPath.r(), 3.0 * fluxPath.x() / omega(settings)));
            }
            List<String> across1 = to[0].isEmpty() ? List.of(primary) : List.of(primary, to[0]);
            if (g1 != 0.0) {
                lines.add("_RLC", id + "_RM" + phase, across1, rl(1.0 / g1, 0.0));
            }
            if (b1 != 0.0) {
                lines.add("_RLC", id + "_LM" + phase, across1, rl(0.0, 1.0 / (omega(settings) * b1)));
            }
        }
        if (windings != null) {
            for (int k = 0; k < 3; k++) {
                earthReference(lines, id + "_" + (k + 1), connections[k], windings.groundingR(k + 1), windings.groundingX(k + 1), neutrals[k],
                        legNodes[k], settings);
            }
        }
        if (fluxPath != null) {
            tankEarthReferences(lines, id);
        }
    }

    /**
     * A two-winding transformer with a phase tap changer and no winding connections, as ATP writes it: a T around an ideal phase-shifting
     * coupling ({@link #coupling}) - half the leakage on each side, each half referred to its own side, {@code 16L/Dt} across it; the
     * excitation admittance from the T's middle, the coupling's input, to earth, referred to side 1; the coupling at the windings' ratio
     * (a ratio tap's and the phase tap's rho folded in, {@link TapCorrectedImpedance}) and the step's angle.
     */
    private static void writePhaseShiftingTransformer(Devices lines, TwoWindingsTransformer transformer, EmtpSimulationSettings settings) {
        TapCorrectedImpedance corrected = TapCorrectedImpedance.of(transformer);
        double ratedU1 = transformer.getRatedU1();
        double ratedU2 = corrected.ratedU();
        double toSide1 = ratedU1 * ratedU1 / (ratedU2 * ratedU2);
        double l1 = 0.5 * corrected.x() * toSide1 / omega(settings);
        double l2 = 0.5 * corrected.x() / omega(settings);
        double g1 = corrected.g() / toSide1;
        double b1 = Math.abs(corrected.b()) / toSide1;
        String id = id(transformer);
        String[] side1 = phaseNodes(transformer.getTerminal1().getBusBreakerView().getBus().getId());
        String[] side2 = phaseNodes(transformer.getTerminal2().getBusBreakerView().getBus().getId());
        String[] middle = new String[3];
        for (int p = 0; p < 3; p++) {
            middle[p] = "T" + id + "_1" + PHASES[p];
            lines.add("_RLC", id + "_W1" + PHASES[p], List.of(side1[p], middle[p]), rl(0.5 * corrected.r() * toSide1, l1));
            damping(lines, id + "_D1" + PHASES[p], side1[p], middle[p], l1, settings, "half the leakage");
            if (g1 != 0.0) {
                lines.add("_RLC", id + "_RM" + PHASES[p], List.of(middle[p]), rl(1.0 / g1, 0.0));
            }
            if (b1 != 0.0) {
                lines.add("_RLC", id + "_LM" + PHASES[p], List.of(middle[p]), rl(0.0, 1.0 / (omega(settings) * b1)));
            }
        }
        String[] ideal = coupling(lines, "PS" + id, middle, ratedU2 / ratedU1, transformer.getPhaseTapChanger().getCurrentStep().getAlpha());
        for (int p = 0; p < 3; p++) {
            lines.add("_RLC", id + "_W2" + PHASES[p], List.of(ideal[p], side2[p]), rl(0.5 * corrected.r(), l2));
            damping(lines, id + "_D2" + PHASES[p], ideal[p], side2[p], l2, settings, "half the leakage");
        }
    }

    /**
     * A three-winding transformer with a phase-shifting leg and no winding connections, as ATP writes it: the star point an explicit node
     * per phase, each leg on it - a ratio-only leg an ideal unit at {@code U_leg / U0} then its leakage, a phase-shifting leg a coupling
     * ({@link #coupling}) at that ratio and its angle ({@link PhaseShiftCoefficients#legAlphaDegrees}) then its leakage, {@code 16L/Dt}
     * across that one; each leg's excitation admittance on the star node, referred to {@code U0}.
     */
    private static void writePhaseShiftingCapableTransformer(Devices lines, ThreeWindingsTransformer transformer, EmtpSimulationSettings settings) {
        String id = id(transformer);
        double ratedU0 = transformer.getRatedU0();
        String[] star = {"S" + id + "a", "S" + id + "b", "S" + id + "c"};
        List<ThreeWindingsTransformer.Leg> legs = List.of(transformer.getLeg1(), transformer.getLeg2(), transformer.getLeg3());
        for (int k = 0; k < 3; k++) {
            ThreeWindingsTransformer.Leg leg = legs.get(k);
            TapCorrectedImpedance values = TapCorrectedImpedance.ofLeg(leg).referredFromStarBus(ratedU0);
            String[] bus = phaseNodes(leg.getTerminal().getBusBreakerView().getBus().getId());
            double ratio = values.ratedU() / ratedU0;
            double inductance = values.x() / omega(settings);
            String legId = id + "_" + (k + 1);
            if (leg.getOptionalPhaseTapChanger().isPresent()) {
                String[] ideal = coupling(lines, "PS" + legId, star, ratio,
                        PhaseShiftCoefficients.legAlphaDegrees(leg.getPhaseTapChanger().getCurrentStep().getAlpha()));
                for (int p = 0; p < 3; p++) {
                    lines.add("_RLC", legId + "_W" + PHASES[p], List.of(ideal[p], bus[p]), rl(values.r(), inductance));
                    damping(lines, legId + "_D" + PHASES[p], ideal[p], bus[p], inductance, settings, "the leg's leakage");
                }
            } else {
                for (int p = 0; p < 3; p++) {
                    String winding = "T" + legId + PHASES[p];
                    lines.add("_Tr0", legId + "_" + PHASES[p], List.of(star[p], "", winding, ""), number(ratio) + ",,,");
                    lines.add("_RLC", legId + "_W" + PHASES[p], List.of(winding, bus[p]), rl(values.r(), inductance));
                }
            }
            double g0 = values.g() * ratio * ratio;
            double b0 = Math.abs(values.b()) * ratio * ratio;
            for (int p = 0; p < 3; p++) {
                if (g0 != 0.0) {
                    lines.add("_RLC", legId + "_RM" + PHASES[p], List.of(star[p]), rl(1.0 / g0, 0.0));
                }
                if (b0 != 0.0) {
                    lines.add("_RLC", legId + "_LM" + PHASES[p], List.of(star[p]), rl(0.0, 1.0 / (omega(settings) * b0)));
                }
            }
        }
    }

    /**
     * An ideal three-phase coupling at ratio {@code rho} and angle {@code alphaDegrees}, from {@code inputs} to the three nodes it returns -
     * ATP's {@code AtpPhaseShiftingCoupling} in EMTP's ideal units: for each output phase, a chain from earth of one ideal unit per input
     * phase ({@code _Tr0}), its primary from that input to earth, its secondary adding {@code rho} times the circulant coefficient {@link
     * PhaseShiftCoefficients} gives of that input's voltage to the chain, its terminals swapped for a negative one - ampere-turns
     * conserved by the units themselves. A zero coefficient adds nothing, and no unit.
     */
    private static String[] coupling(Devices lines, String name, String[] inputs, double rho, double alphaDegrees) {
        double[] k = PhaseShiftCoefficients.compute(alphaDegrees);
        String[] outputs = new String[3];
        for (int out = 0; out < 3; out++) {
            String chain = "";
            for (int in = 0; in < 3; in++) {
                double ratio = rho * PhaseShiftCoefficients.coefficientAt(k, out, in);
                if (ratio == 0.0) {
                    continue;
                }
                String next = name + "_" + PHASES[out] + (in + 1);
                List<String> secondary = ratio > 0.0 ? List.of(next, chain) : List.of(chain, next);
                lines.add("_Tr0", name + "_" + PHASES[out] + PHASES[in], List.of(inputs[in], "", secondary.get(0), secondary.get(1)),
                        number(Math.abs(ratio)) + ",,,");
                chain = next;
            }
            outputs[out] = chain;
        }
        return outputs;
    }

    /** A side's star point: earth ({@code ""}) when solidly earthed, the node {@code name} otherwise; none for a delta. */
    private static String neutral(WindingConnectionType connection, double groundingR, double groundingX, String name) {
        if (connection == WindingConnectionType.DELTA) {
            return null;
        }
        return connection == WindingConnectionType.Y_GROUNDED && groundingR == 0.0 && groundingX == 0.0 ? "" : name;
    }

    /** A winding's terminal: its side's node of phase {@code terminal}, or the star point. */
    private static String terminal(String[] phaseNodes, int terminal, String neutral) {
        return terminal == TransformerWindings.NEUTRAL ? neutral : phaseNodes[terminal];
    }

    /** What ties a side to earth: its star point's grounding impedance, 0.003 uF from an isolated star point, 0.003 uF from each phase
     * of a delta side - ATP's. */
    private static void earthReference(Devices lines, String name, WindingConnectionType connection, double groundingR, double groundingX,
                                       String neutral, String[] phaseNodes, EmtpSimulationSettings settings) {
        String capacitor = "0,0," + number(NumericalAids.FLOATING_WINDING_EARTH_REFERENCE_F) + ",0,0,";
        if (connection == WindingConnectionType.DELTA) {
            for (int p = 0; p < phaseNodes.length; p++) {
                lines.comment(NumericalAids.COMMENT_MARKER + ": 0.003 uF earth reference of a delta side's phase");
                lines.add("_RLC", name + "_CD" + PHASES[p], List.of(phaseNodes[p]), capacitor);
            }
        } else if (connection == WindingConnectionType.Y) {
            lines.comment(NumericalAids.COMMENT_MARKER + ": 0.003 uF earth reference of an isolated star point");
            lines.add("_RLC", name + "_CN", List.of(neutral), capacitor);
        } else if (!neutral.isEmpty()) {
            lines.add("_RLC", name + "_ZN", List.of(neutral), rl(groundingR, groundingX / omega(settings)));
        }
    }

    private static String[] phaseNodes(String busId) {
        return new String[] {node(busId, 'a'), node(busId, 'b'), node(busId, 'c')};
    }

    /** A line end's charging, {@code susceptanceS} to earth from {@code node}: its capacitance, {@code Dt/16C} in series with it - one
     * branch, as ATP's card; none without susceptance. */
    private static void charging(Devices lines, String name, String node, double susceptanceS, EmtpSimulationSettings settings) {
        if (susceptanceS == 0.0) {
            return;
        }
        double capacitance = susceptanceS / omega(settings);
        lines.comment(NumericalAids.COMMENT_MARKER + ": the R, Dt/16C in series with the line charging");
        lines.add("_RLC", name, List.of(node), number(NumericalAids.shuntCapacitanceDampingOhm(capacitance, settings.timeStepSeconds())) + ",0,"
                + number(capacitance) + ",0,0,");
    }

    /**
     * Branch {@code index} of a fault, numbered across the run's faults: its switch from its first phase on the fault's bus to node
     * {@code FLT<index>}, then its closed resistance to earth or to its other phase.
     */
    private static void writeFaultBranch(Devices lines, BusFault fault, BusFault.Branch branch, int index, EmtpSimulationSettings settings) {
        String inner = "FLT" + index;
        String label = sanitize(fault.id()) + "_" + branch.label();
        double margin = fault.clearing() == FaultClearing.AT_ORDER ? ANY_CURRENT_AMPERES : 0.0;
        lines.add("_Sw0", label, List.of(node(fault.busId(), branch.from()), inner),
                number(NumericalAids.switchingInstant(fault.closeTimeSeconds(), settings.timeStepSeconds())) + ","
                        + number(NumericalAids.switchingInstant(fault.openTimeSeconds(), settings.timeStepSeconds())) + "," + number(margin)
                        + ",0,0,,1,.1,.001,5,?i,");
        lines.add("_RLC", label + "_R", branch.toEarth() ? List.of(inner) : List.of(inner, node(fault.busId(), branch.to())),
                rl(fault.onResistanceOhm(), 0.0));
    }

    private static void writeLoad(Devices lines, Load load, EmtpSimulationSettings settings) {
        String busId = load.getTerminal().getBusBreakerView().getBus().getId();
        for (LoadBranches.Branch branch : LoadBranches.of(load)) {
            String phase = String.valueOf(Character.toLowerCase(branch.from()));
            double v2 = branch.referenceVoltageVolts() * branch.referenceVoltageVolts();
            double s2 = (branch.pMw() * branch.pMw() + branch.qMvar() * branch.qMvar()) * 1.0e12;
            double resistance = v2 * branch.pMw() * 1.0e6 / s2;
            double inductance = v2 * branch.qMvar() * 1.0e6 / s2 / omega(settings);
            String inner = "Z" + id(load) + phase;
            lines.add("_RLC", id(load) + "_R" + phase, List.of(node(busId, branch.from()), inner), rl(resistance, 0.0));
            lines.add("_RLC", id(load) + "_L" + phase, List.of(inner), rl(0.0, inductance));
            damping(lines, id(load) + "_D" + phase, inner, null, inductance, settings, "the load inductance");
        }
    }

    private static void writeInfiniteBus(Devices lines, Generator generator, EmtpSimulationSettings settings) {
        InfiniteBusGeneratorModel model = (InfiniteBusGeneratorModel) generator.getExtension(GeneratorModel.class);
        Bus bus = generator.getTerminal().getBusBreakerView().getBus();
        double nominalKv = bus.getVoltageLevel().getNominalV();
        InfiniteBusEmf emf = InfiniteBusEmf.fromLoadFlow(generator, model, settings.nominalFrequencyHz(), settings.timeStepSeconds());
        // The vector-group displacement between the load flow's reference and the bus, as for any source (VectorGroupOffsets).
        double offset = VectorGroupOffsets.of(generator.getNetwork()).degrees(bus);
        double resistance = NumericalAids.sourceSeriesResistanceOhm(InfiniteBusEmf.theveninResistanceOhm(model, nominalKv));
        double inductance = InfiniteBusEmf.theveninReactanceOhm(model, nominalKv) / omega(settings);
        for (int p = 0; p < PHASES.length; p++) {
            String phase = PHASES[p];
            String source = "E" + id(generator) + phase;
            double angle = Math.IEEEremainder(emf.angleDegrees() + offset - 120.0 * p, 360.0);
            lines.add("_Vsine", id(generator) + "_" + phase, List.of(source),
                    number(emf.crestPerPhaseVolts()) + "," + number(settings.nominalFrequencyHz()) + "," + number(angle) + "," + STEADY_STATE_START + "," + NEVER + ",");
            String to = node(bus.getId(), phase.charAt(0));
            lines.add("_RLC", id(generator) + "_Z" + phase, List.of(source, to), rl(resistance, inductance));
            damping(lines, id(generator) + "_D" + phase, source, to, inductance, settings, "the series inductance");
        }
    }

    /** {@code 16L/Dt} across {@code inductanceH} from {@code from} to {@code to} - earth if {@code null}; none without inductance. */
    private static void damping(Devices lines, String name, String from, String to, double inductanceH, EmtpSimulationSettings settings,
                                String across) {
        if (inductanceH == 0.0) {
            return;
        }
        lines.comment(NumericalAids.COMMENT_MARKER + ": 16L/Dt across " + across);
        lines.add("_RLC", name, to == null ? List.of(from) : List.of(from, to),
                rl(NumericalAids.seriesInductanceDampingOhm(inductanceH, settings.timeStepSeconds()), 0.0));
    }

    /**
     * The devices, one block per part: EMTP reads a netlist part by part - a part met again after another is misread (an infinite
     * bus's second phase written after its first phase's impedance came out at 0 V, its first phase's bus on its EMF, near which EMTP
     * saw "a floating network condition") - so they are written grouped, the parts in alphabetical order as EMTPWorks sorts them.
     * A comment goes with the device that follows it.
     */
    private static final class Devices {

        private final Map<String, List<String>> blocks = new TreeMap<>();
        private final List<String> pendingComments = new ArrayList<>();

        void comment(String text) {
            pendingComments.add("! " + text);
        }

        /** A device: its header line - part, name, its pins twice, its nodes - and its data line. */
        void add(String part, String name, List<String> nodes, String... data) {
            List<String> block = blocks.computeIfAbsent(part, k -> new ArrayList<>());
            block.addAll(pendingComments);
            pendingComments.clear();
            block.add(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",");
            block.addAll(List.of(data));
        }

        List<String> lines() {
            return blocks.values().stream().flatMap(List::stream).toList();
        }
    }

    /** An {@code _RLC}'s data: R and L, no capacitance, no initial condition. */
    private static String rl(double resistanceOhm, double inductanceH) {
        return number(resistanceOhm) + "," + number(inductanceH) + ",0,0,0,";
    }

    /** What this writer cannot write yet, said for each element. */
    static List<String> unsupported(Network network) {
        List<String> reasons = new ArrayList<>();
        for (Line line : network.getLines()) {
            if (line.getG1() != 0.0 || line.getG2() != 0.0) {
                reasons.add("line '" + line.getId() + "' has a shunt conductance");
            }
        }
        for (Load load : network.getLoads()) {
            if (load.getExtension(LoadModel.class) != null) {
                reasons.add("load '" + load.getId() + "' has a load model, " + load.getExtension(LoadModel.class).getClass().getSimpleName());
            } else if (LoadBranches.isDelta(load) || load.getQ0() < 0.0) {
                reasons.add("load '" + load.getId() + "' is delta-connected or capacitive");
            }
        }
        for (Generator generator : network.getGenerators()) {
            if (!(generator.getExtension(GeneratorModel.class) instanceof InfiniteBusGeneratorModel)) {
                reasons.add("generator '" + generator.getId() + "' is not an infinite bus");
            }
        }
        for (TwoWindingsTransformer transformer : network.getTwoWindingsTransformers()) {
            List<String> windingReasons = TransformerWindings.unsupportedReasons(transformer);
            if (!windingReasons.isEmpty()) {
                reasons.add("transformer '" + transformer.getId() + "': " + String.join(", ", windingReasons));
            }
        }
        for (ThreeWindingsTransformer transformer : network.getThreeWindingsTransformers()) {
            List<String> windingReasons = ThreeWindingsTransformerWindings.unsupportedReasons(transformer);
            if (!windingReasons.isEmpty()) {
                reasons.add("transformer '" + transformer.getId() + "': " + String.join(", ", windingReasons));
            }
        }
        Stream.of(network.getShuntCompensatorStream(),
                        network.getSwitchStream(), network.getHvdcLineStream(), network.getStaticVarCompensatorStream(),
                        network.getBatteryStream(), network.getVoltageSourceConverterStream())
                .flatMap(s -> s.map(Identifiable::getId))
                .forEach(id -> reasons.add("'" + id + "' is of a kind not written yet"));
        return reasons;
    }

    /** Bus {@code busId}'s node of {@code phase}: {@code B1a}. */
    static String node(String busId, char phase) {
        return sanitize(busId) + Character.toLowerCase(phase);
    }

    private static String id(Identifiable<?> identifiable) {
        return sanitize(identifiable.getId());
    }

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    static String name(Network network) {
        return sanitize(network.getId());
    }

    private static double omega(EmtpSimulationSettings settings) {
        return 2.0 * Math.PI * settings.nominalFrequencyHz();
    }

    /** {@code x} to its last digit, as EMTP reads a number. */
    static String number(double x) {
        return String.format(Locale.ROOT, "%.16G", x);
    }
}
