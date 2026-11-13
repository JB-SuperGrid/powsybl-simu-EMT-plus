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
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.circuit.LoadBranches;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.circuit.TapCorrectedImpedance;
import com.powsybl.simuemt.extensions.circuit.TransformerWindings;
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
 *     ideal unit to earth, where ATP hangs them. No {@code 16L/Dt} across a leakage, which ATP keeps inside its component;</li>
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
 * another kind of generator or load, a line's shunt conductance, a transformer with winding connections or a phase tap changer, a
 * switch - or the run goes through is refused, said
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
        double resistance = NumericalAids.sourceSeriesResistanceOhm(InfiniteBusEmf.theveninResistanceOhm(model, nominalKv));
        double inductance = InfiniteBusEmf.theveninReactanceOhm(model, nominalKv) / omega(settings);
        for (int p = 0; p < PHASES.length; p++) {
            String phase = PHASES[p];
            String source = "E" + id(generator) + phase;
            double angle = Math.IEEEremainder(emf.angleDegrees() - 120.0 * p, 360.0);
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
        void add(String part, String name, List<String> nodes, String data) {
            List<String> block = blocks.computeIfAbsent(part, k -> new ArrayList<>());
            block.addAll(pendingComments);
            pendingComments.clear();
            block.add(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",");
            block.add(data);
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
            if (TransformerWindings.of(transformer) != null) {
                reasons.add("transformer '" + transformer.getId() + "' has winding connections");
            } else if (transformer.getOptionalPhaseTapChanger().isPresent()) {
                reasons.add("transformer '" + transformer.getId() + "' has a phase tap changer");
            }
        }
        Stream.of(network.getThreeWindingsTransformerStream(), network.getShuntCompensatorStream(),
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
