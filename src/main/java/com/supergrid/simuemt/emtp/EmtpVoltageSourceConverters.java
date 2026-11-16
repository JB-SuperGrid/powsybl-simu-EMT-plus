/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.DcGround;
import com.powsybl.iidm.network.DcLine;
import com.powsybl.iidm.network.DcNode;
import com.powsybl.iidm.network.DcSwitch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.arrester.ZnOArrester;
import com.powsybl.simuemt.extensions.arrester.ZnOSegment;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.BreakerAction;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.ConverterDeblocking;
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.DcSwitchOperation;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.hvdc.AutomaticRestart;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedDcChopperModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedMmcModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.DcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.DcChopperModel;
import com.powsybl.simuemt.extensions.hvdc.DcEarthingModel;
import com.powsybl.simuemt.extensions.hvdc.DcLineModel;
import com.powsybl.simuemt.extensions.hvdc.DcMidpointEarthing;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.HybridDcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.LumpedDcLineModel;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.extensions.hvdc.control.Ddsrf;
import com.powsybl.simuemt.extensions.hvdc.control.DdsrfPll;
import com.powsybl.simuemt.extensions.hvdc.control.DualSequenceCurrentControl;
import com.powsybl.simuemt.extensions.hvdc.control.FaultRideThrough;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.NegativeSequenceInjection;
import com.powsybl.simuemt.extensions.hvdc.control.OuterLoops;
import com.powsybl.simuemt.extensions.hvdc.control.SrfPll;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * The DC side of IIDM's detailed DC model and its voltage source converters carrying an {@link AveragedVscModel} under a {@link
 * GridFollowingControl}, as upstream's backends write them - {@code XyceVscWriter}'s circuit and equations, in EMTP's devices and
 * control blocks.
 *
 * <p><b>The DC side.</b> A DC node is a single node, earthed through 1 Gohm as a bus phase is, its voltage scope ({@code vb:REF_<node>})
 * the node's. A DC line is its resistance and its {@link LumpedDcLineModel}'s inductance in one branch, {@code 16L/Dt} across it, and
 * half its capacitance to earth at each end behind {@code Dt/16C}; or its resistance alone. A DC ground is its resistance to earth, a
 * solid one 0.01 ohm, as ATP's. A DC switch closed, its resistance; a {@link HybridDcBreakerModel} opened through the run, its switch
 * and its ZnO absorber across it ({@link #dcSwitch}); a {@link DcFault}, a switch to earth or to the other DC node through its
 * resistance, letting go of its current at its order.
 *
 * <p><b>The converter's power stage.</b> Per phase, an EMF - a controlled voltage source ({@code _cV}) - from the converter's star
 * point, isolated (1 Gohm to earth), through the phase reactor's resistance and inductance to its bus, {@code 16L/Dt} across the
 * inductance; the current from the bus into the converter read on the source's observe pin. On the DC side, the current that carries
 * the EMFs' power - {@code (ed id + eq iq) S / max(Vdc, 0.1 Vdc,nom)}, less an {@link AveragedDcChopperModel}'s {@code d Vdc / R} -
 * into DC terminal 1 and out of terminal 2, a controlled current source ({@code _cI}) between nodes of its own tied to the DC nodes by
 * {@link NumericalAids#CURRENT_SOURCE_TIE_RESISTANCE_OHM}; and the DC capacitor across the two DC nodes. An {@link AveragedMmcModel}
 * running, CIGRE B4.57's type 4: its EMFs behind half an arm, its equivalent capacitor and DC current on a node of its own behind two
 * thirds of an arm and its DC reactor, its DC voltage measured at its DC terminal.
 *
 * <p><b>Its control</b>, per unit of its rating, the bus voltages read by probes - zero-current controlled current sources whose voltage is
 * observed: an SRF PLL on {@code vq}, the Park transform on its angle {@code theta = theta0 + ws t + int(KP vq + KI int vq)}; the
 * measured {@code P = vd id + vq iq}, {@code Q = vq id - vd iq}, {@code Vac}, {@code Vdc} through the measurement's lag; the outer PIs,
 * the d axis on the active power - its reference along the fault ride-through's recovery ramp, if any - or the DC voltage, the q axis on
 * the reactive power, the AC voltage or the AC voltage's droop; the current references limited to {@code Imax}, the d axis first, or the
 * q axis first below the ride-through's threshold, the ride-through's reactive current added; the outer integrals stopped at those
 * limits (conditional integration), the reactive one held through a ride-through; the decoupled inner PIs, {@code e = v + jX i - u};
 * and the EMFs {@code Vb (ed cos(theta + phi) - eq sin(theta + phi))}. The switches - the ride-through's, the integrations' - ATP's plain
 * logic. Every block at rest on the load flow ({@link AveragedVscInitialState}).
 *
 * <p><b>Its sequences</b>, a {@link Ddsrf}'s: the voltage and the current read in a frame turning the other way too, each sequence less
 * the other's twice-frequency ripple rebuilt from that sequence's filtered value; the measurements and the ride-through on the positive
 * sequence; a {@link DdsrfPll} on its decoupled {@code vq}. A {@link DualSequenceCurrentControl}: the positive sequence's loop on its
 * decoupled current and voltage, the negative sequence's in its own frame, {@code e- = LPF(v-*) - (R - jX) i- - u-}, on zero or a
 * {@link NegativeSequenceInjection}'s reference within the crest the q axis leaves; the EMFs the two sequences' sum, the DC current
 * their power - each sequence's own and the ripple of each with the other.
 *
 * <p><b>Started from the load flow.</b> A controlled source is a short circuit in EMTP's steady state: each EMF is stood in for by a
 * cosine source at its load-flow phasor, in series with it and active in the steady state only, as EMTP's own documentation does it; each
 * converter's DC voltage by a DC source across its terminals at its load-flow voltage - a negative pole's converter has its neutral on
 * terminal 1 - through a switch closed in the steady state and opened at the first step - a source stopped is a short circuit -, as
 * ATP's stand-ins.
 *
 * <p>A converter in {@code DC_DROOP}: its active power's reference less its {@link DcVoltageDroopLaw}'s reduction as its DC voltage
 * moves, within its {@code [minP, maxP]}.
 *
 * <p>A {@link GridFormingControl}: {@link EmtpGridFormingControls}'s angle and EMF, the EMFs and the DC current as here.
 *
 * <p>A {@link ConverterBlocking} of a grid-following converter - an MMC's equivalent capacitor then behind its diode ({@link
 * #capacitorBehindDiode}), on both sides earthed at its midpoint -, and its {@link ConverterDeblocking} once after: {@link
 * #blocking}, its integrals held while it is blocked and restarted from 0 at the deblocking ({@link #freeze}). An {@link
 * OvercurrentProtection}: blocked as by an event when its worst arm current passes its threshold, its delay on ({@link #runningFlag}),
 * its DC current read on a meter at its DC terminal, every switch of its blocking on its running flag; restarted by its {@link
 * AutomaticRestart}, if any ({@link #automaticRestart}).
 *
 * <p>Refused, until written: a PLL but the SRF and DDSRF ones, a protection, a chopper but the averaged one, a
 * chopper at an MMC's DC terminal; what {@link EmtpGridFormingControls} refuses.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpVoltageSourceConverters {

    /** The DC voltage the DC current is divided by at the least, per unit: upstream's. */
    private static final double DC_VOLTAGE_FLOOR_PU = 0.1;
    /** What keeps a current limit's headroom real: upstream's. */
    private static final double MEASUREMENT_EPS = 1.0e-9;
    /** A solid DC ground's resistance: ATP's. */
    private static final double GROUND_CONNECTION_OHM = 1.0e-2;
    /** The least time constant a recovery ramp's tracking takes: upstream's. */
    private static final double MIN_TRACKING_TIME_CONSTANT = 1.0e-4;
    /** A switch's current margin no current reaches: it opens at the order, whatever its current. */
    private static final double ANY_CURRENT_AMPERES = 1.0e9;
    /** The lowest a ZnO column's first segment starts from, pu of its reference voltage: EMTP's must start above zero. */
    private static final double ZNO_FIRST_SEGMENT_PU = 1.0e-3;

    private EmtpVoltageSourceConverters() {
    }

    /** Why {@code converter} cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(VoltageSourceConverter converter) {
        List<String> reasons = new ArrayList<>();
        if (!(converter.getExtension(VoltageSourceConverterModel.class) instanceof AveragedVscModel
                || converter.getExtension(VoltageSourceConverterModel.class) instanceof AveragedMmcModel)) {
            reasons.add("its model is neither an averaged two-level VSC nor an averaged MMC");
            return reasons;
        }
        AveragedConverterModel model = (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class);
        if (converter.getExtension(VscControlModel.class) instanceof GridFormingControl gridForming) {
            reasons.addAll(EmtpGridFormingControls.unsupportedReasons(gridForming));
        } else if (!(GridFollowingControl.of(converter).getSynchronization() instanceof SrfPll
                || GridFollowingControl.of(converter).getSynchronization() instanceof DdsrfPll)) {
            reasons.add("its PLL but the SRF and DDSRF ones are not written yet");
        }
        if (converter.getExtension(VscProtectionModel.class) != null && (!(converter.getExtension(VscProtectionModel.class) instanceof OvercurrentProtection protection)
                || converter.getExtension(VscControlModel.class) instanceof GridFormingControl
                || converter.getExtension(VoltageSourceConverterModel.class) instanceof AveragedMmcModel && converter.getExtension(DcEarthingModel.class) != null
                && !DcMidpointEarthing.valveWindingEarthed(converter))) {
            reasons.add("its protection but an overcurrent one on a grid-following converter - an MMC earthed at its midpoint with its valve winding"
                    + " earthed - is not written yet");
        }
        if (converter.getExtension(DcEarthingModel.class) != null && !(converter.getExtension(DcEarthingModel.class) instanceof DcMidpointEarthing)) {
            reasons.add("its DC earthing but the midpoint one is not written yet");
        }
        DcChopperModel chopper = converter.getExtension(DcChopperModel.class);
        if (chopper != null && !(chopper instanceof AveragedDcChopperModel)) {
            reasons.add("its chopper but the averaged one is not written yet");
        }
        if (chopper != null && model.measuresDcVoltageAtTerminal()) {
            reasons.add("its chopper at its DC terminal, beyond its arms, is not written yet");
        }
        return reasons;
    }

    /** Why the DC side cannot be written yet - empty if it can. */
    static List<String> unsupportedDcReasons(Network network) {
        List<String> reasons = new ArrayList<>();
        network.getDcLineStream().filter(line -> line.getExtension(DcLineModel.class) != null && !(line.getExtension(DcLineModel.class) instanceof LumpedDcLineModel))
                .forEach(line -> reasons.add("DC line '" + line.getId() + "''s model but the lumped one is not written yet"));
        return reasons;
    }

    /** Why {@code blocking} cannot be written yet - empty if it can: a converter but an MMC earthed at its midpoint blocked once, deblocked
     * once after at most. */
    static List<String> unsupportedBlockingReasons(Network network, ConverterBlocking blocking, List<? extends EmtEvent> events) {
        List<String> reasons = new ArrayList<>();
        VoltageSourceConverter converter = network.getVoltageSourceConverter(blocking.converterId());
        if (converter == null) {
            reasons.add("converter '" + blocking.converterId() + "' is blocked, which the network has not");
        } else if (converter.getExtension(VoltageSourceConverterModel.class) instanceof AveragedMmcModel && converter.getExtension(DcEarthingModel.class) != null
                && !DcMidpointEarthing.valveWindingEarthed(converter)) {
            reasons.add("converter '" + converter.getId() + "' is blocked but it is an MMC earthed at its midpoint with its valve winding unearthed,"
                    + " which nothing then holds to earth");
        } else if (events.stream().filter(e -> e instanceof ConverterBlocking b && b.converterId().equals(converter.getId())).count() > 1) {
            reasons.add("converter '" + converter.getId() + "' is blocked more than once");
        } else if (events.stream().filter(e -> e instanceof ConverterDeblocking d && d.converterId().equals(converter.getId())
                && !(d.timeSeconds() > blocking.timeSeconds())).count() > 0
                || events.stream().filter(e -> e instanceof ConverterDeblocking d && d.converterId().equals(converter.getId())).count() > 1) {
            reasons.add("converter '" + converter.getId() + "' is deblocked but once after its blocking, which is not written yet");
        }
        return reasons;
    }

    /** Why the DC events cannot be written yet - empty if they can: a DC switch operated but a closed hybrid breaker opened, then reclosed and
     * opened again in turn. */
    static List<String> unsupportedDcEventReasons(Network network, List<? extends EmtEvent> events) {
        List<String> reasons = new ArrayList<>();
        Map<String, BreakerAction> lastActions = new HashMap<>();
        for (EmtEvent event : events) {
            if (event instanceof DcFault fault) {
                for (String node : Stream.of(fault.dcNodeId(), fault.otherDcNodeId()).filter(Objects::nonNull).toList()) {
                    if (network.getDcNode(node) == null) {
                        reasons.add("DC fault '" + fault.id() + "' is on DC node '" + node + "', which the network has not");
                    }
                }
            } else if (event instanceof DcSwitchOperation operation) {
                DcSwitch dcSwitch = network.getDcSwitch(operation.switchId());
                if (dcSwitch == null) {
                    reasons.add("DC switch '" + operation.switchId() + "' is operated, which the network has not");
                } else if (!(dcSwitch.getExtension(DcBreakerModel.class) instanceof HybridDcBreakerModel) || dcSwitch.isOpen()
                        || operation.action() == lastActions.getOrDefault(dcSwitch.getId(), BreakerAction.CLOSE)) {
                    reasons.add("DC switch '" + dcSwitch.getId() + "' is operated but as a closed hybrid breaker opened, then reclosed and opened again in"
                            + " turn, which is not written yet");
                } else {
                    lastActions.put(dcSwitch.getId(), operation.action());
                }
            }
        }
        return reasons;
    }

    /** The DC side's devices: its nodes' earth references, its lines, its grounds, its switches, its faults through {@code events}. */
    static List<EmtpSynchronousMachines.Device> dcSide(Network network, EmtpSimulationSettings settings, List<? extends EmtEvent> events) {
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        for (DcNode node : network.getDcNodes()) {
            devices.add(element("_RLC", "REF_" + dcNode(node), List.of(dcNode(node)), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,?v,"));
        }
        for (DcLine line : network.getDcLines()) {
            String from = dcNode(line.getDcTerminal1().getDcNode());
            String to = dcNode(line.getDcTerminal2().getDcNode());
            String id = sanitize(line.getId());
            LumpedDcLineModel model = line.getExtension(DcLineModel.class) instanceof LumpedDcLineModel lumped ? lumped : null;
            double inductance = model == null ? 0.0 : model.getInductance();
            devices.add(element("_RLC", id, List.of(from, to), number(line.getR()) + "," + number(inductance) + ",0,0,0,"));
            if (inductance > 0.0) {
                devices.add(element("_RLC", id + "_D", List.of(from, to),
                        number(NumericalAids.seriesInductanceDampingOhm(inductance, settings.timeStepSeconds())) + ",0,0,0,0,"));
            }
            if (model != null && model.getCapacitance() > 0.0) {
                double half = model.getCapacitance() / 2.0;
                String damping = number(NumericalAids.shuntCapacitanceDampingOhm(half, settings.timeStepSeconds()));
                devices.add(element("_RLC", id + "_C1", List.of(from), damping + ",0," + number(half) + ",0,0,"));
                devices.add(element("_RLC", id + "_C2", List.of(to), damping + ",0," + number(half) + ",0,0,"));
            }
        }
        for (DcGround ground : network.getDcGrounds()) {
            devices.add(element("_RLC", sanitize(ground.getId()), List.of(dcNode(ground.getDcTerminal().getDcNode())),
                    number(ground.getR() > 0.0 ? ground.getR() : GROUND_CONNECTION_OHM) + ",0,0,0,0,"));
        }
        for (DcSwitch dcSwitch : network.getDcSwitches()) {
            List<DcSwitchOperation> operations = events.stream().filter(e -> e instanceof DcSwitchOperation o && o.switchId().equals(dcSwitch.getId()))
                    .map(DcSwitchOperation.class::cast).sorted(Comparator.comparingDouble(DcSwitchOperation::timeSeconds)).toList();
            dcSwitch(devices, dcSwitch, operations, settings);
        }
        int index = 0;
        for (EmtEvent event : events) {
            if (event instanceof DcFault fault) {
                // Its switch lets go of its current at the order: a DC current has no zero to wait for.
                String inner = "DFLT" + ++index;
                String id = sanitize(fault.id());
                devices.add(element("_Sw0", id, List.of(dcNode(network.getDcNode(fault.dcNodeId())), inner),
                        number(NumericalAids.switchingInstant(fault.closeTimeSeconds(), settings.timeStepSeconds())) + ","
                                + number(NumericalAids.switchingInstant(fault.openTimeSeconds(), settings.timeStepSeconds())) + "," + number(ANY_CURRENT_AMPERES)
                                + ",0,0,,1,.1,.001,5,?i,"));
                List<String> to = fault.otherDcNodeId() == null ? List.of(inner) : List.of(inner, dcNode(network.getDcNode(fault.otherDcNodeId())));
                devices.add(element("_RLC", id + "_R", to, number(Math.max(fault.resistanceOhm(), NumericalAids.FAULT_ON_RESISTANCE_OHM)) + ",0,0,0,0,"));
            }
        }
        return devices;
    }

    /**
     * A DC switch, as upstream's backends write it. Left alone, its state: closed, its resistance - {@link
     * NumericalAids#FAULT_ON_RESISTANCE_OHM} at the least - and a {@link HybridDcBreakerModel}'s reactor, {@code 16L/Dt} across it; open,
     * nothing. A hybrid breaker operated, {@code operations} in time order: its reactor, if any, from DC node 1 to a node of its own, then its
     * main branch - that resistance and a switch per interval it is closed for, from the steady state on or a reclosing's order to an
     * opening's, letting go at the order whatever its current, as ATP's cards - and across it its
     * energy absorber, an EMTP ZnO column (its first segment from {@link #ZNO_FIRST_SEGMENT_PU} at the least: EMTP joins it to the origin
     * linearly, at currents nothing reads); with a reactor, the breaker's RC across them ({@link NumericalAids#DC_BREAKER_SNUBBER_F}).
     */
    private static void dcSwitch(List<EmtpSynchronousMachines.Device> devices, DcSwitch dcSwitch, List<DcSwitchOperation> operations,
                                 EmtpSimulationSettings settings) {
        String id = sanitize(dcSwitch.getId());
        String node1 = dcNode(dcSwitch.getDcNode1());
        String node2 = dcNode(dcSwitch.getDcNode2());
        double resistance = Math.max(dcSwitch.getR(), NumericalAids.FAULT_ON_RESISTANCE_OHM);
        HybridDcBreakerModel model = dcSwitch.getExtension(DcBreakerModel.class) instanceof HybridDcBreakerModel hybrid ? hybrid : null;
        double reactor = model == null ? 0.0 : model.getReactorInductance();
        if (operations.isEmpty()) {
            if (!dcSwitch.isOpen()) {
                devices.add(element("_RLC", id, List.of(node1, node2), number(resistance) + "," + number(reactor) + ",0,0,0,"));
                if (reactor > 0.0) {
                    devices.add(element("_RLC", id + "_D", List.of(node1, node2),
                            number(NumericalAids.seriesInductanceDampingOhm(reactor, settings.timeStepSeconds())) + ",0,0,0,0,"));
                }
            }
            return;
        }
        String side = node1;
        if (reactor > 0.0) {
            side = id + "_X";
            devices.add(element("_RLC", id + "_L", List.of(node1, side), "0," + number(reactor) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LD", List.of(node1, side),
                    number(NumericalAids.seriesInductanceDampingOhm(reactor, settings.timeStepSeconds())) + ",0,0,0,0,"));
            devices.add(element("_RLC", id + "_RC", List.of(side, node2), number(NumericalAids.DC_BREAKER_SNUBBER_OHM) + ",0,"
                    + number(NumericalAids.DC_BREAKER_SNUBBER_F) + ",0,0,"));
        }
        String inner = id + "_S";
        devices.add(element("_RLC", id + "_R", List.of(side, inner), number(resistance) + ",0,0,0,0,"));
        String closedFrom = "-1";
        int interval = 0;
        for (DcSwitchOperation operation : operations) {
            String order = number(NumericalAids.switchingInstant(operation.timeSeconds(), settings.timeStepSeconds()));
            if (operation.action() == BreakerAction.OPEN) {
                devices.add(mainBranchSwitch(id, ++interval, inner, node2, closedFrom, order));
            } else {
                closedFrom = order;
            }
        }
        if (operations.get(operations.size() - 1).action() == BreakerAction.CLOSE) {
            devices.add(mainBranchSwitch(id, ++interval, inner, node2, closedFrom, "1E15"));
        }
        devices.add(zno(id + "_ZNO", List.of(side, node2), model.getArrester()));
    }

    /** An EMTP ZnO column {@code name} on {@code nodes} - one: to earth -, {@code arrester}'s curve, its current recorded; its first segment
     * from {@link #ZNO_FIRST_SEGMENT_PU} at the least (EMTP joins it to the origin linearly, at currents nothing reads). */
    static EmtpSynchronousMachines.Device zno(String name, List<String> nodes, ZnOArrester arrester) {
        List<String> lines = new ArrayList<>(List.of("_ZnO;" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",",
                number(arrester.referenceVoltage()) + ",,,1e-06,15,5,1,?i,"));
        for (int k = 0; k < arrester.segments().size(); k++) {
            ZnOSegment segment = arrester.segments().get(k);
            double minimum = k == 0 ? Math.max(segment.minVoltage(), ZNO_FIRST_SEGMENT_PU) : segment.minVoltage();
            lines.add(number(segment.coefficient()) + " " + number(segment.exponent()) + " " + number(minimum));
        }
        return new EmtpSynchronousMachines.Device("_ZnO", lines);
    }

    /** A hybrid breaker's main branch's switch for its {@code interval}-th closed interval, {@code <id>} then {@code <id>_<interval>}, its
     * current recorded. */
    private static EmtpSynchronousMachines.Device mainBranchSwitch(String id, int interval, String inner, String node2, String closing, String opening) {
        return element("_Sw0", interval == 1 ? id : id + "_" + interval, List.of(inner, node2), closing + "," + opening + "," + number(ANY_CURRENT_AMPERES)
                + ",0,0,,1,.1,.001,5,?i,");
    }

    /** The DC node's EMTP node. */
    static String dcNode(DcNode node) {
        return sanitize(node.getId());
    }

    /** The DC node's voltage scope, as {@link EmtpOutput} names it. */
    static String dcVoltageScope(String dcNodeId) {
        return "vb:REF_" + sanitize(dcNodeId);
    }

    /** {@code converter}'s devices - its power stage, its stand-ins and its control - on bus phases {@code phaseNodes}, its terminal's
     * angle displaced by {@code offsetDegrees}, its bus's vector group's. */
    static List<EmtpSynchronousMachines.Device> converter(VoltageSourceConverter converter, String[] phaseNodes, double offsetDegrees,
                                                          EmtpSimulationSettings settings) {
        return converter(converter, phaseNodes, offsetDegrees, settings, Double.NaN, Double.NaN);
    }

    /** The same, {@code converter} blocked at {@code blockingSeconds} and deblocked at {@code deblockingSeconds} - each {@code NaN} for
     * never ({@link #blocking}). */
    static List<EmtpSynchronousMachines.Device> converter(VoltageSourceConverter converter, String[] phaseNodes, double offsetDegrees,
                                                          EmtpSimulationSettings settings, double blockingSeconds, double deblockingSeconds) {
        AveragedConverterModel model = (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class);
        AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
        double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
        double vbpk = initial.peakPhaseVoltageBase();
        double ibpk = initial.peakPhaseCurrentBase();
        double x = model.getPhaseReactorReactance();
        double r = model.getPhaseReactorResistance();
        double angle0 = Math.toRadians(initial.terminalAngle() + offsetDegrees);
        double dcBaseVolts = initial.dcNominalVoltage() * 1.0e3;
        String id = sanitize(converter.getId());
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();

        double emfMagnitude = Math.hypot(initial.directEmf(), initial.quadratureEmf()) * vbpk;
        double emfAngle = angle0 + Math.atan2(initial.quadratureEmf(), initial.directEmf());
        String[] v = new String[3];
        String[] i = new String[3];
        powerStage(devices, converter, model, initial, phaseNodes, v, i, emfMagnitude, emfAngle, omega, settings);
        boolean blockable = !Double.isNaN(blockingSeconds) || isProtected(converter);
        if (blockable) {
            blocking(devices, converter, model, initial, blockingSeconds, deblockingSeconds, settings);
        }
        String vPole = id + "_VDP";
        String vReturn = id + "_VDR";
        String cos2 = id + "_C2";
        String sin2 = id + "_S2";

        // A grid-forming control: its internal EMF's angle and magnitude, the current through its virtual admittance followed.
        if (converter.getExtension(VscControlModel.class) instanceof GridFormingControl gridForming) {
            EmtpGridFormingControls.write(devices, converter, model, gridForming, initial, v, i, angle0, omega, blockable, !Double.isNaN(deblockingSeconds));
            emfsAndDcCurrent(devices, converter, initial, false, new Sequences(id + "_ID", id + "_IQ", null, null, null, null), cos2, sin2,
                    emfMagnitude, emfAngle, omega, settings);
            if (blockable) {
                boolean restarts = !Double.isNaN(deblockingSeconds);
                freeze(devices, id, List.of("_GXD", "_GXQ", "_UDI", "_UQI"), restarts);
                freeze(devices, id, List.of("_GLHX", "_GLNX"), false);
            }
            return devices;
        }
        GridFollowingControl control = GridFollowingControl.of(converter);
        OuterLoops loops = control.getOuterLoops();
        FaultRideThrough frt = control.getFaultRideThrough();
        double tau = control.getMeasurement().timeConstant();

        // The control: Park transform on the PLL's angle.
        String theta = id + "_TH";
        String vd = id + "_VD";
        String vq = id + "_VQ";
        String idPu = id + "_ID";
        String iqPu = id + "_IQ";
        devices.add(park(id + "_VD", vd, v, theta, "COS", vbpk, initial.terminalVoltage(), false));
        devices.add(park(id + "_VQ", vq, v, theta, "SIN", vbpk, 0.0, true));
        // The source's current is counted from its star point to its EMF, out of the converter: negated, the current from the bus into it.
        // Blocked, its currents read on its meter, from the bus into it; otherwise on its sources, counted out of it.
        boolean metered = blockable;
        String[] read = metered ? new String[] {id + "_IMa", id + "_IMb", id + "_IMc"} : i;
        double currentBase = metered ? ibpk : -ibpk;
        devices.add(park(id + "_ID", idPu, read, theta, "COS", currentBase, initial.directCurrent(), false));
        devices.add(park(id + "_IQ", iqPu, read, theta, "SIN", currentBase, initial.quadratureCurrent(), true));
        // The PLL - on the positive sequence's decoupled q-axis voltage alone, a DDSRF's (named ahead: EMTP's devices are in no order).
        pll(devices, id, control, control.getSynchronization() instanceof DdsrfPll ? id + "_BE2" : vq, theta, angle0, omega);

        // The sequences, if the control separates them (a DDSRF): each sequence's voltage and current, the other's twice-frequency ripple
        // taken off - or the whole measurement, read in the positive frame, as the positive sequence.
        Sequences voltage = new Sequences(vd, vq, null, null, null, null);
        Sequences current = new Sequences(idPu, iqPu, null, null, null, null);
        if (control.getSequenceSeparation() instanceof Ddsrf ddsrf) {
            devices.add(function(cos2, cos2, List.of(theta), Math.cos(2.0 * angle0), "COS(2*u[1])"));
            devices.add(function(sin2, sin2, List.of(theta), Math.sin(2.0 * angle0), "SIN(2*u[1])"));
            voltage = ddsrf(devices, ddsrf, id + "_B", vd, vq, v, theta, vbpk, cos2, sin2, initial.terminalVoltage(), 0.0);
            current = ddsrf(devices, ddsrf, id + "_Z", idPu, iqPu, read, theta, currentBase, cos2, sin2, initial.directCurrent(), initial.quadratureCurrent());
        }
        String pd = voltage.positiveD();
        String pq = voltage.positiveQ();
        String cd = current.positiveD();
        String cq = current.positiveQ();

        // The measurements, through their lag - on the positive sequence, once the control separates them.
        String p = measurement(devices, id + "_P", List.of(pd, pq, cd, cq), "u[1]*u[3]+u[2]*u[4]", tau, initial.activePower());
        String q = measurement(devices, id + "_Q", List.of(pd, pq, cd, cq), "u[2]*u[3]-u[1]*u[4]", tau, initial.reactivePower());
        String vac = measurement(devices, id + "_VAC", List.of(pd, pq), "SQRT(u[1]*u[1]+u[2]*u[2])", tau, initial.terminalVoltage());
        String vdc = measurement(devices, id + "_VDC", List.of(vPole, vReturn), "(u[1]-u[2])/" + number(dcBaseVolts), tau,
                initial.dcVoltage() / initial.dcNominalVoltage());

        // The fault ride-through: its weight, 1 below its threshold, and its reactive current.
        String w = id + "_FRT";
        String frtCurrent = id + "_IQFRT";
        if (frt.voltageThreshold() > 0.0) {
            devices.add(function(id + "_FRT", w, List.of(vac), 0.0, step(number(frt.voltageThreshold()) + "-u[1]")));
            devices.add(function(id + "_IQFRT", frtCurrent, List.of(vac), 0.0, "(" + number(frt.reactiveCurrentGain()) + ")*0.5*((" + number(frt.voltageThreshold())
                    + "-u[1])+ABS(" + number(frt.voltageThreshold()) + "-u[1]))"));
        } else {
            devices.add(function(id + "_FRT", w, List.of(vac), 0.0, "0*u[1]"));
            devices.add(function(id + "_IQFRT", frtCurrent, List.of(vac), 0.0, "0*u[1]"));
        }

        // The outer loops' errors.
        double maxCurrent = control.getCurrentLimit().maxCurrent();
        boolean ramp = initial.activePowerControl() && frt.hasActivePowerRecoveryRamp();
        String dLimitedRef = id + "_IDLIM";
        String dError = id + "_IDE";
        String qError = id + "_IQE";
        double trackingGain = 1.0 / Math.max(tau, MIN_TRACKING_TIME_CONSTANT);
        if (initial.activePowerControl()) {
            activePowerError(devices, converter, initial, frt, p, vac, vdc, trackingGain);
        } else {
            devices.add(function(id + "_IDE", dError, List.of(vdc), 0.0, number(initial.dcVoltage() / initial.dcNominalVoltage()) + "-u[1]"));
        }
        if (initial.acVoltageDroop(loops)) {
            String reference = "(" + number(initial.reactivePower()) + "+(u[2]-" + number(initial.terminalVoltage()) + ")*" + number(1.0 / loops.acVoltageDroop()) + ")";
            String floored = "0.5*((" + reference + "+" + number(initial.reactivePowerMin()) + ")+ABS(" + reference + "-" + number(initial.reactivePowerMin()) + "))";
            String limited = "0.5*((" + floored + "+" + number(initial.reactivePowerMax()) + ")-ABS(" + floored + "-" + number(initial.reactivePowerMax()) + "))";
            devices.add(function(id + "_IQE", qError, List.of(q, vac), 0.0, "(" + number(loops.acVoltageDroop()) + ")*(u[1]-" + limited + ")"));
        } else if (initial.acVoltageControl()) {
            devices.add(function(id + "_IQE", qError, List.of(vac), 0.0, number(initial.terminalVoltage()) + "-u[1]"));
        } else {
            devices.add(function(id + "_IQE", qError, List.of(q), 0.0, "u[1]-" + number(initial.reactivePower())));
        }
        double dKp = initial.activePowerControl() ? loops.activePowerProportionalGain() : loops.dcVoltageProportionalGain();
        double dKi = initial.activePowerControl() ? loops.activePowerIntegralGain() : loops.dcVoltageIntegralGain();
        double qKp = initial.reactiveAxisProportionalGain(loops);
        double qKi = initial.reactiveAxisIntegralGain(loops);

        // The raw references, then the current limit: the d axis first, or the q axis first through a ride-through.
        String dIntegral = id + "_IDI";
        String qIntegral = id + "_IQI";
        String dRaw = id + "_IDR";
        String qRaw = id + "_IQR";
        devices.add(function(id + "_IDR", dRaw, List.of(dError, dIntegral), initial.directCurrent(), "(" + number(dKp) + ")*u[1]+u[2]"));
        devices.add(function(id + "_IQR", qRaw, List.of(qError, qIntegral, frtCurrent), initial.quadratureCurrent(), "(" + number(qKp) + ")*u[1]+u[2]+u[3]"));
        String dLimit = id + "_IDMAX";
        String qLimit = id + "_IQMAX";
        String imax = number(maxCurrent);
        // Each axis first, then what it leaves the other: sqrt(max(Imax^2 - first^2, eps)).
        String dFirst = id + "_IDA";
        String qFirst = id + "_IQB";
        devices.add(function(id + "_IDA", dFirst, List.of(dRaw), initial.directCurrent(), clampExpression("u[1]", imax)));
        devices.add(function(id + "_IQB", qFirst, List.of(qRaw), initial.quadratureCurrent(), clampExpression("u[1]", imax)));
        String squared = "(" + number(maxCurrent * maxCurrent) + "-u[1]*u[1])";
        String headroom = "SQRT(0.5*(" + squared + "+" + number(MEASUREMENT_EPS) + "+ABS(" + squared + "-" + number(MEASUREMENT_EPS) + ")))";
        // A negative-sequence injection: its current, through the ride-through, within the crest the q axis leaves; the d axis's after both.
        boolean dualSequence = control.getCurrentControl() instanceof DualSequenceCurrentControl;
        NegativeSequenceInjection injection = control.getCurrentControl() instanceof DualSequenceCurrentControl dual
                && dual.strategy() instanceof NegativeSequenceInjection k ? k : null;
        String[] negativeReference = null;
        if (injection != null) {
            negativeReference = negativeSequenceReference(devices, id, injection, maxCurrent, w, qFirst, voltage);
            String crest = "(" + imax + "-u[3]*u[4])";
            String crestSquared = "(" + crest + "*" + crest + "-u[1]*u[1])";
            String afterBoth = "SQRT(0.5*(" + crestSquared + "+" + number(MEASUREMENT_EPS) + "+ABS(" + crestSquared + "-" + number(MEASUREMENT_EPS) + ")))";
            devices.add(function(id + "_IDMAX", dLimit, List.of(qFirst, w, id + "_YG", id + "_YE"), maxCurrent, "(1-u[2])*" + imax + "+u[2]*" + afterBoth));
        } else {
            devices.add(function(id + "_IDMAX", dLimit, List.of(qFirst, w), maxCurrent, "(1-u[2])*" + imax + "+u[2]*" + headroom));
        }
        devices.add(function(id + "_IQMAX", qLimit, List.of(dFirst, w), maxCurrent, "(1-u[2])*" + headroom + "+u[2]*" + imax));
        String qRef = id + "_IQLIM";
        devices.add(function(id + "_IDLIM", dLimitedRef, List.of(dRaw, dLimit), initial.directCurrent(), clampExpression("u[1]", "u[2]")));
        devices.add(function(id + "_IQLIM", qRef, List.of(qRaw, qLimit), initial.quadratureCurrent(), clampExpression("u[1]", "u[2]")));

        // The outer integrals, stopped at the limits; the reactive one held through a ride-through.
        String dRate = id + "_IDIR";
        String dIntegrand = "(" + number(dKi) + ")*u[1]*" + windupGate("u[2]", "u[3]", "u[1]");
        if (ramp) {
            dIntegrand = "(1-u[4])*" + dIntegrand + "+u[4]*(" + number(trackingGain) + ")*(u[5]-u[6])";
            devices.add(function(id + "_IDIR", dRate, List.of(dError, dRaw, dLimit, w, dLimitedRef, dIntegral), 0.0, dIntegrand));
        } else {
            devices.add(function(id + "_IDIR", dRate, List.of(dError, dRaw, dLimit), 0.0, dIntegrand));
        }
        devices.add(integrator(id + "_IDI", dIntegral, dRate, 1.0, initial.directCurrent()));
        String qRate = id + "_IQIR";
        devices.add(function(id + "_IQIR", qRate, List.of(qError, qRaw, qLimit, w), 0.0,
                "(" + number(qKi) + ")*u[1]*" + windupGate("u[2]", "u[3]", "u[1]") + "*(1-u[4])"));
        devices.add(integrator(id + "_IQI", qIntegral, qRate, 1.0, initial.quadratureCurrent()));

        // The inner current loops, decoupled: e = v + jX i - u - on the positive sequence, a dual-sequence loop's, its decoupled voltage
        // fed forward (the negative sequence's filtered one in its own loop: each part of the bus's once in the EMF, as ATP's).
        String loopD = dualSequence ? cd : idPu;
        String loopQ = dualSequence ? cq : iqPu;
        String feedD = dualSequence ? pd : vd;
        String feedQ = dualSequence ? pq : vq;
        String ud = innerPi(devices, id + "_UD", dLimitedRef, loopD, control, r * initial.directCurrent());
        String uq = innerPi(devices, id + "_UQ", qRef, loopQ, control, r * initial.quadratureCurrent());
        String ed = id + "_ED";
        String eq = id + "_EQ";
        devices.add(function(id + "_ED", ed, List.of(feedD, loopQ, ud), initial.directEmf(), "u[1]+(" + number(x) + ")*u[2]-u[3]"));
        devices.add(function(id + "_EQ", eq, List.of(feedQ, loopD, uq), initial.quadratureEmf(), "u[1]-(" + number(x) + ")*u[2]-u[3]"));
        // The negative sequence's loop, in its frame turning the other way: e- = LPF(v-*) - (R - jX) i- - u-, on zero or the injection's.
        String edNegative = id + "_EDN";
        String eqNegative = id + "_EQN";
        if (dualSequence) {
            negativeSequenceLoop(devices, id, control, x, voltage, current, negativeReference);
        }
        emfsAndDcCurrent(devices, converter, initial, dualSequence, current, cos2, sin2, emfMagnitude, emfAngle, omega, settings);
        if (blockable) {
            freeze(devices, id, List.of("_IDI", "_IQI", "_PREF", "_UDI", "_UQI", "_UDNI", "_UQNI"), !Double.isNaN(deblockingSeconds) || restartsAutomatically(converter));
        }
        return devices;
    }

    /**
     * The EMFs, rebuilt from {@code <id>_ED}, {@code <id>_EQ} - and the negative sequence's, {@code dualSequence} - on {@code <id>_TH}, and
     * the DC current carrying their power, less a chopper's: what a grid-following and a grid-forming control share.
     */
    static void emfsAndDcCurrent(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedVscInitialState initial,
                                 boolean dualSequence, Sequences current, String cos2, String sin2, double emfMagnitude, double emfAngle, double omega,
                                 EmtpSimulationSettings settings) {
        String id = sanitize(converter.getId());
        String theta = id + "_TH";
        String ed = id + "_ED";
        String eq = id + "_EQ";
        String edNegative = id + "_EDN";
        String eqNegative = id + "_EQN";
        String vPole = id + "_VDP";
        String vReturn = id + "_VDR";
        String dcCurrent = id + "_IDCS";
        double vbpk = initial.peakPhaseVoltageBase();
        double dcBaseVolts = initial.dcNominalVoltage() * 1.0e3;
        boolean blockable = devices.stream().anyMatch(d -> d.lines().get(0).startsWith("_c_fu;" + id + "_RUN;"));
        // The EMFs a step ahead of the angle the PLL tracks: EMTP's network takes a control signal a step after the control computes it,
        // as ATP's does - its EMFs rebuilt a step ahead too.
        String ahead = "+" + number(omega * settings.timeStepSeconds());
        // The negative sequence's frame turns the other way: phase b's angle the positive frame's phase c's, and c's b's.
        String third = number(2.0 * Math.PI / 3.0);
        List<String> emfInputs = new ArrayList<>(dualSequence ? List.of(ed, eq, theta, edNegative, eqNegative) : List.of(ed, eq, theta));
        // A blockable converter's EMFs, on earth, centred on its DC midpoint - from earth over NumericalAids.MIDPOINT_RAMP_SECONDS -, as a
        // two-level converter's AC side is: its diode bridge idle while the DC voltage holds above the AC side's crest (ATP's).
        String centre = "";
        String alternating = "";
        if (blockable) {
            emfInputs.add(vPole);
            emfInputs.add(vReturn);
            emfInputs.add(id + "_RUN");
            int k = emfInputs.size() - 1;
            alternating = "*u[" + (k + 1) + "]";
            centre = "+0.5*(1+(t/" + number(NumericalAids.MIDPOINT_RAMP_SECONDS) + ")-ABS(1-(t/" + number(NumericalAids.MIDPOINT_RAMP_SECONDS) + ")))*0.5*(u["
                    + (k - 1) + "]+u[" + k + "])";
        }
        for (int k = 0; k < 3; k++) {
            String shift = ahead + (k == 0 ? "" : k == 1 ? "-" + third : "+" + third);
            String negative = ahead + (k == 0 ? "" : k == 1 ? "+" + third : "-" + third);
            devices.add(function(id + "_EMF" + "abc".charAt(k), id + "_EMF" + "abc".charAt(k), emfInputs, emfMagnitude * Math.cos(emfAngle - 2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k)),
                    "(" + number(vbpk) + ")*(u[1]*COS(u[3]" + shift + ")-u[2]*SIN(u[3]" + shift + ")"
                            + (dualSequence ? "+u[4]*COS(u[3]" + negative + ")+u[5]*SIN(u[3]" + negative + ")" : "") + ")" + alternating + centre));
        }

        // The DC current: the EMFs' power over the DC voltage, less the chopper's. With a sequence each, the power of E = E+ e^j theta +
        // E- e^-j theta into I alike: each sequence's own and the twice-frequency ripple of each with the other, in the frame the EMF
        // was computed in.
        String emfPower = emfPower(devices, id, initial, dualSequence, current, cos2, sin2);
        String chopper = "";
        // Over its capacitor's voltage, where the power is exchanged - not over its DC terminal's, which an MMC's controls read beyond its
        // arms: there, the drop across them would be power the AC side has not given (upstream's 4cb857ab, every backend alike).
        String capacitorSide = ((AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class)).measuresDcVoltageAtTerminal() ? id + "_VDK" : vPole;
        List<String> dcInputs = new ArrayList<>(List.of(emfPower, capacitorSide, vReturn));
        String running = "";
        if (blockable) {
            dcInputs.add(id + "_RUN");
            running = "*u[4]";
        }
        if (converter.getExtension(DcChopperModel.class) instanceof AveragedDcChopperModel averaged) {
            double span = averaged.getFullConductionVoltage() - averaged.getActivationVoltage();
            String duty = id + "_DCH";
            devices.add(function(id + "_DCH", duty, List.of(vPole, vReturn), 0.0, clamp01("((u[1]-u[2])/" + number(dcBaseVolts) + "-" + number(averaged.getActivationVoltage())
                    + ")/" + number(span))));
            dcInputs.add(duty);
            chopper = "-u[" + dcInputs.size() + "]*(u[2]-u[3])/" + number(averaged.resistanceOhm(initial.dcNominalVoltage()));
        }
        String floor = number(DC_VOLTAGE_FLOOR_PU * dcBaseVolts);
        devices.add(function(id + "_IDCF", dcCurrent, dcInputs, initial.dcCurrent() * 1.0e3, "u[1]*" + number(initial.baseApparentPower() * 1.0e6)
                + "/(0.5*((u[2]-u[3])+" + floor + "+ABS((u[2]-u[3])-" + floor + ")))" + running + chopper));
    }

    /**
     * {@code converter} blocked at {@code blockingSeconds}, as upstream's backends block it: its EMFs cut from their reactors by a switch
     * opened at the order whatever its current - the valves' switches off, the reactors' current taken over by their diodes -; its valves
     * left to a diode bridge, each diode through {@link NumericalAids#FAULT_ON_RESISTANCE_OHM} from each phase's EMF side of the reactor
     * towards the DC node the load flow has higher and from the other back - the pole's rail behind the bridge inductance, if any -, behind
     * a switch closed at the order - ATP's gate -, its snubber
     * and grading resistor switched across it then; its EMFs on earth, centred on its DC midpoint; its currents read on a meter between its
     * bus and its reactors - a controlled source held at 0 V, its current observed -, the bridge's with them once blocked, as PSCAD's and
     * ATP's. The running flag {@code <id>_RUN}, 1 then 0 from the order: its EMFs' alternating part, its DC current and its controls'
     * integrals - but the PLL's - stopped with it. (Cut at its currents' zeros instead, the EMFs gone at the order, the bus was shorted
     * through the reactors for half a period: VSC1's power 0.9 pu off PSCAD's; 2026-11-15.) Deblocked at {@code deblockingSeconds}: the
     * running flag back to 1 and the deblocked flag {@code <id>_DEB} up, the cut made again, the valves' switches opened whatever their
     * current.
     */
    private static void blocking(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedConverterModel model,
                                 AveragedVscInitialState initial, double blockingSeconds, double deblockingSeconds, EmtpSimulationSettings settings) {
        String id = sanitize(converter.getId());
        runningFlag(devices, converter, initial, blockingSeconds, deblockingSeconds, settings);
        // The bridge's pole rail on the converter's own side: its capacitor's node, behind its DC reactor or its meter, as ATP's.
        String pole = model.hasDcReactor() || isProtected(converter) ? id + "_CP" : dcNode(converter.getDcTerminal1().getDcNode());
        String ret = dcNode(converter.getDcTerminal2().getDcNode());
        boolean poleHigher = converter.getDcTerminal1().getDcNode().getV() >= converter.getDcTerminal2().getDcNode().getV();
        if (model.blocksCapacitorDischarge()) {
            capacitorBehindDiode(devices, id, pole, ret, converter.getExtension(DcEarthingModel.class) instanceof DcMidpointEarthing);
        }
        // The bridge inductance, if any, from the pole to the bridge's rail, 16L/Dt across it, as ATP's.
        if (model.hasBridgeInductance()) {
            String rail = id + "_BR";
            devices.add(element("_RLC", id + "_LB", List.of(pole, rail), "0," + number(model.getBridgeInductance()) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LBD", List.of(pole, rail),
                    number(NumericalAids.seriesInductanceDampingOhm(model.getBridgeInductance(), settings.timeStepSeconds())) + ",0,0,0,0,"));
            pole = rail;
        }
        String high = poleHigher ? pole : ret;
        String low = poleHigher ? ret : pole;
        // Every switch on the running flag, whatever its current: the cut closed while it is up, the valves' gates and snubbers while it is
        // down ({@code <id>_GATE}) - opened at a deblocking, as Xyce's and Kestrel's valves are behind switches their gate opens.
        String closedFirst = "1,0,0,0,0,1e6,0,";
        String openFirst = "0,0,0,0,0,1e6,0,";
        String[] meterBus = new String[3];
        for (char phase : "abc".toCharArray()) {
            String emfSide = id + "_K" + phase;
            String valveSide = id + "_W" + phase;
            for (int k = 0; k < devices.size(); k++) {
                List<String> lines = devices.get(k).lines();
                // The reactor from the valve side, with the cutting switch's resistance; the EMF on earth.
                if (lines.get(0).equals("_RLC;" + id + "_R" + phase + ";2;2;" + emfSide + "," + id + "_J" + phase + ",")) {
                    devices.set(k, element("_RLC", id + "_R" + phase, List.of(valveSide, id + "_J" + phase), number(initial.phaseReactorResistanceOhm(model)
                            + NumericalAids.FAULT_ON_RESISTANCE_OHM) + ",0,0,0,0,"));
                } else if (lines.get(0).startsWith("_RLC;" + id + "_L" + phase + ";") || lines.get(0).startsWith("_RLC;" + id + "_LD" + phase + ";")) {
                    // The reactor's bus end on its meter's node.
                    String header = lines.get(0);
                    String busNode = header.substring(header.lastIndexOf(',', header.length() - 2) + 1, header.length() - 1);
                    devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of(header.substring(0, header.length() - busNode.length() - 1) + id + "_X" + phase
                            + ",", lines.get(1))));
                    meterBus[phase - 'a'] = busNode;
                } else if (lines.get(0).startsWith("_cV;" + id + "_CV" + phase + ";")) {
                    devices.set(k, new EmtpSynchronousMachines.Device("_cV", List.of(lines.get(0).replace("," + id + "_N,", ",,"), lines.get(1))));
                }
            }
            devices.add(element("_cSw", id + "_CUT" + phase, List.of(emfSide, valveSide, id + "_RUN"), closedFirst));
            // The meter: a controlled source held at 0 V, its current - from the bus into the converter, through the bridge too once blocked -
            // observed, what the controls read (PSCAD's and ATP's meter).
            devices.add(element("_cV", id + "_MET" + phase, List.of(id + "_X" + phase, meterBus[phase - 'a'], id + "_ZERO", id + "_MV" + phase, id + "_IM" + phase,
                    id + "_MP" + phase), "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            // Each diode through its resistance, behind its gate's switch; its snubber and grading resistor across it from the order.
            for (boolean upper : new boolean[] {true, false}) {
                String tag = (upper ? "H" : "L") + phase;
                String rail = upper ? high : low;
                String gate = id + "_G" + tag;
                String diodeEnd = id + "_D" + tag;
                devices.add(element("_cSw", id + "_SG" + tag, List.of(valveSide, gate, id + "_GATE"), openFirst));
                // Between an open switch and a diode off, the gate's node is held by 1 Gohm to earth, as a bus is.
                devices.add(element("_RLC", id + "_GR" + tag, List.of(gate), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,"));
                devices.add(element("_D0", id + "_V" + tag, upper ? List.of(gate, diodeEnd) : List.of(diodeEnd, gate), "0.001,0,0,"));
                devices.add(element("_RLC", id + "_VR" + tag, List.of(diodeEnd, rail), number(NumericalAids.FAULT_ON_RESISTANCE_OHM) + ",0,0,0,0,"));
                String snubber = id + "_S" + tag;
                devices.add(element("_cSw", id + "_SS" + tag, List.of(valveSide, snubber, id + "_GATE"), openFirst));
                devices.add(element("_RLC", id + "_SR" + tag, List.of(snubber, rail), number(NumericalAids.VALVE_SNUBBER_OHM) + ",0,"
                        + number(NumericalAids.VALVE_SNUBBER_F) + ",0,0,"));
                devices.add(element("_RLC", id + "_SD" + tag, List.of(snubber, rail), number(NumericalAids.VALVE_GRADING_OHM) + ",0,0,0,0,"));
            }
        }
    }

    /**
     * A blockable MMC's equivalent capacitor - its DC current with it - on a node of its own, {@code <id>_CQ}, behind a diode from its pole,
     * as ATP's: blocked half-bridge submodules charge, never discharge. Bypassed while it runs - a switch on its running flag, closed in the
     * steady state, through {@link NumericalAids#FAULT_ON_RESISTANCE_OHM} -; the diode through the same resistance, its snubber and grading
     * resistor switched across it once blocked; the blocked pole's RC to its return, {@link NumericalAids#MMC_POLE_SNUBBER_F}.
     *
     * <p>Earthing its midpoint, a symmetric monopole's ({@code midpoint}): its return-side plate {@code <id>_CE} behind a diode of its own
     * too, into the return, with its bypass, snubber and grading resistor, as upstream's backends since 9f5e6ac0. Behind the pole's diode
     * alone, its return-side half held the negative pole on the midpoint's earth while the other floated; cut off on both sides, as a
     * blocked MMC's submodules charge through both arms, it holds neither, and the valve winding's earthed star point, through the
     * bridge's diodes, holds the DC side to earth ({@link DcMidpointEarthing#valveWindingEarthed}).
     */
    private static void capacitorBehindDiode(List<EmtpSynchronousMachines.Device> devices, String id, String pole, String ret, boolean midpoint) {
        String capacitor = id + "_CQ";
        String capacitorReturn = midpoint ? id + "_CE" : ret;
        for (int k = 0; k < devices.size(); k++) {
            List<String> lines = devices.get(k).lines();
            String header = lines.get(0);
            if (("_RLC;" + id + "_CDC;2;2;" + pole + "," + ret + ",").equals(header)) {
                devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of("_RLC;" + id + "_CDC;2;2;" + capacitor + "," + ret + ",", lines.get(1))));
            } else if (("_RLC;" + id + "_CDC1;2;2;" + pole + "," + id + "_MID,").equals(header)) {
                devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of("_RLC;" + id + "_CDC1;2;2;" + capacitor + "," + id + "_MID,", lines.get(1))));
            } else if (("_RLC;" + id + "_CDC2;2;2;" + id + "_MID," + ret + ",").equals(header)) {
                devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of("_RLC;" + id + "_CDC2;2;2;" + id + "_MID," + capacitorReturn + ",", lines.get(1))));
            } else if (("_RLC;" + id + "_TP;2;2;" + id + "_DCP," + pole + ",").equals(header)) {
                devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of("_RLC;" + id + "_TP;2;2;" + id + "_DCP," + capacitor + ",", lines.get(1))));
            } else if (midpoint && ("_RLC;" + id + "_TR;2;2;" + id + "_DCR," + ret + ",").equals(header)) {
                devices.set(k, new EmtpSynchronousMachines.Device("_RLC", List.of("_RLC;" + id + "_TR;2;2;" + id + "_DCR," + capacitorReturn + ",", lines.get(1))));
            }
        }
        String fault = number(NumericalAids.FAULT_ON_RESISTANCE_OHM) + ",0,0,0,0,";
        if (midpoint) {
            devices.add(element("_cSw", id + "_BYPE", List.of(ret, id + "_CEW", id + "_RUN"), "1,0,0,0,0,1e6,0,"));
            devices.add(element("_RLC", id + "_BYRE", List.of(id + "_CEW", capacitorReturn), fault));
            devices.add(element("_D0", id + "_CDIE", List.of(id + "_CL", ret), "0.001,0,0,"));
            devices.add(element("_RLC", id + "_CDRE", List.of(capacitorReturn, id + "_CL"), fault));
            devices.add(element("_cSw", id + "_CSSE", List.of(ret, id + "_CS", id + "_GATE"), "0,0,0,0,0,1e6,0,"));
            devices.add(element("_RLC", id + "_CSRE", List.of(id + "_CS", capacitorReturn), number(NumericalAids.VALVE_SNUBBER_OHM) + ",0,"
                    + number(NumericalAids.VALVE_SNUBBER_F) + ",0,0,"));
            devices.add(element("_RLC", id + "_CSGE", List.of(id + "_CS", capacitorReturn), number(NumericalAids.VALVE_GRADING_OHM) + ",0,0,0,0,"));
        }
        devices.add(element("_cSw", id + "_BYP", List.of(pole, id + "_CW", id + "_RUN"), "1,0,0,0,0,1e6,0,"));
        devices.add(element("_RLC", id + "_BYR", List.of(id + "_CW", capacitor), fault));
        devices.add(element("_D0", id + "_CDIO", List.of(pole, id + "_CY"), "0.001,0,0,"));
        devices.add(element("_RLC", id + "_CDR", List.of(id + "_CY", capacitor), fault));
        devices.add(element("_cSw", id + "_CSS", List.of(pole, id + "_CZ", id + "_GATE"), "0,0,0,0,0,1e6,0,"));
        devices.add(element("_RLC", id + "_CSR", List.of(id + "_CZ", capacitor), number(NumericalAids.VALVE_SNUBBER_OHM) + ",0," + number(NumericalAids.VALVE_SNUBBER_F)
                + ",0,0,"));
        devices.add(element("_RLC", id + "_CSG", List.of(id + "_CZ", capacitor), number(NumericalAids.VALVE_GRADING_OHM) + ",0,0,0,0,"));
        devices.add(element("_RLC", id + "_PSN", List.of(pole, ret), number(NumericalAids.MMC_POLE_SNUBBER_OHM) + ",0," + number(NumericalAids.MMC_POLE_SNUBBER_F)
                + ",0,0,"));
    }

    /** Whether {@code converter}'s protection restarts it automatically. */
    private static boolean restartsAutomatically(VoltageSourceConverter converter) {
        return converter.getExtension(VscProtectionModel.class) instanceof OvercurrentProtection protection && protection.getAutomaticRestart().isPresent();
    }

    /** Whether {@code converter} carries an {@link OvercurrentProtection}: its running flag then its protection's too. */
    static boolean isProtected(VoltageSourceConverter converter) {
        return converter.getExtension(VscProtectionModel.class) instanceof OvercurrentProtection;
    }

    /**
     * The running flag {@code <id>_RUN} - 1 while the converter runs - and its complement, the valves' gate {@code <id>_GATE}: down from
     * its blocking event's order, if any, and from its {@link OvercurrentProtection}'s trip, if it has one; with a deblocking, the
     * deblocked flag {@code <id>_DEB} up from it, the running flag back to 1, and the protection re-armed then - a trip from then on
     * blocking it again, for good -, as ATP's.
     */
    private static void runningFlag(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedVscInitialState initial,
                                    double blockingSeconds, double deblockingSeconds, EmtpSimulationSettings settings) {
        String id = sanitize(converter.getId());
        List<String> inputs = new ArrayList<>(List.of(id + "_ZERO"));
        String before = Double.isNaN(blockingSeconds) ? "1"
                : step("(" + number(NumericalAids.switchingInstant(blockingSeconds, settings.timeStepSeconds())) + ")-t");
        boolean deblocked = !Double.isNaN(deblockingSeconds);
        String after = "1";
        if (deblocked) {
            String release = number(NumericalAids.switchingInstant(deblockingSeconds, settings.timeStepSeconds()));
            devices.add(function(id + "_DEB", id + "_DEB", List.of(id + "_ZERO"), 0.0, step("t-(" + release + ")") + "+0*u[1]"));
        }
        if (converter.getExtension(VscProtectionModel.class) instanceof OvercurrentProtection protection) {
            protection(devices, id, protection, initial, settings);
            dcCurrentRecorded(devices, id, initial);
            inputs.add(protectionLatch(devices, id + "_PT", id + "_PTI", protection));
            if (protection.getAutomaticRestart().isPresent()) {
                inputs.set(1, automaticRestart(devices, id, protection, settings));
            }
            before = before + "*u[2]";
            if (deblocked) {
                devices.add(function(id + "_PRI", id + "_PRI", List.of(id + "_PTI", id + "_DEB"), 0.0, "u[1]*u[2]"));
                inputs.add(protectionLatch(devices, id + "_PR", id + "_PRI", protection));
                after = "u[3]";
            }
        }
        String running = before;
        if (deblocked) {
            inputs.add(id + "_DEB");
            String d = "u[" + inputs.size() + "]";
            running = "(1-" + d + ")*" + before + "+" + d + "*" + after;
        }
        // Recorded, as the other backends record it.
        devices.add(scoped(function(id + "_RUN", id + "_RUN", inputs, 1.0, running + "+0*u[1]")));
        devices.add(function(id + "_GATE", id + "_GATE", List.of(id + "_RUN"), 0.0, "1-u[1]"));
    }

    /**
     * An {@link AutomaticRestart}'s ladder, as ATP's, a stage per attempt: tripped - by its protection, or after the stage before's restart
     * - and not yet restarted, the stage counts how long it has been blocked and how long its DC voltage, as its controls measure it, has
     * stood above its threshold on end - that count bled away, {@link NumericalAids#RESTART_RESET_TIME_CONSTANT_SECONDS}, while it is
     * below -; past both times it restarts, latched, its protection re-armed. Returns the running flag the ladder leaves, {@code <id>_R<n>}:
     * the protection's flag, plus each stage's restart times its re-armed protection's. The first restart is the deblocked flag {@code
     * <id>_DEB} the integrals restart from 0 on ({@link #freeze}). Each latch the free integral of its rate within 1 - never falling, its rate
     * no less than 0 -, as the protection's ({@link #protectionLatch}).
     */
    private static String automaticRestart(List<EmtpSynchronousMachines.Device> devices, String id, OvercurrentProtection protection,
                                           EmtpSimulationSettings settings) {
        AutomaticRestart restart = protection.getAutomaticRestart().orElseThrow();
        String up = id + "_RVU";
        devices.add(function(up, up, List.of(id + "_VDC"), 1.0, step("u[1]-" + number(restart.dcVoltageThreshold()))));
        String bleed = number(1.0 / NumericalAids.RESTART_RESET_TIME_CONSTANT_SECONDS);
        String rate = number(2.0 / settings.timeStepSeconds());
        String previousFlag = id + "_PTF";
        String previousRestart = null;
        String running = previousFlag;
        for (int stage = 1; stage <= restart.maxAttempts(); stage++) {
            String prefix = id + "_R" + stage;
            List<String> trippedInputs = previousRestart == null ? List.of(previousFlag) : List.of(previousFlag, previousRestart);
            String tripped = previousRestart == null ? "(1-u[1])" : "u[2]*(1-u[1])";
            devices.add(function(prefix + "T", prefix + "T", trippedInputs, 0.0, tripped));
            devices.add(function(prefix + "B", prefix + "B", List.of(prefix + "T", prefix + "D"), 0.0, "u[1]*(1-u[2])"));
            devices.add(integrator(prefix + "S", prefix + "S", prefix + "B", 1.0, 0.0));
            devices.add(function(prefix + "HR", prefix + "HR", List.of(prefix + "B", up, prefix + "H"), 0.0, "u[1]*u[2]-(1-u[2])*u[3]*" + bleed));
            devices.add(integrator(prefix + "H", prefix + "H", prefix + "HR", 1.0, 0.0));
            devices.add(function(prefix + "K", prefix + "K", List.of(prefix + "T", up, prefix + "S", prefix + "H"), 0.0, "u[1]*u[2]*"
                    + step("u[3]-" + number(restart.minimumBlockedTime())) + "*" + step("u[4]-" + number(restart.holdTime())) + "*" + rate));
            devices.add(integrator(prefix + "X", prefix + "X", prefix + "K", 1.0, 0.0));
            devices.add(function(prefix + "D", prefix + "D", List.of(prefix + "X"), 0.0, "0.5*(u[1]+1-ABS(u[1]-1))"));
            devices.add(function(prefix + "U", prefix + "U", List.of(id + "_PTI", prefix + "D"), 0.0, "u[1]*u[2]"));
            String flag = protectionLatch(devices, prefix + "P", prefix + "U", protection);
            devices.add(function(prefix + "R", prefix + "R", List.of(running, prefix + "D", flag), 1.0, "u[1]+u[2]*u[3]"));
            running = prefix + "R";
            previousFlag = flag;
            previousRestart = prefix + "D";
        }
        devices.add(function(id + "_DEB", id + "_DEB", List.of(id + "_R1D"), 0.0, "u[1]"));
        return running;
    }

    /** A protected converter's DC current, {@code control:<id>_IDC}, per unit of its base, unfiltered - as the other backends record it. */
    private static void dcCurrentRecorded(List<EmtpSynchronousMachines.Device> devices, String id, AveragedVscInitialState initial) {
        devices.add(scoped(function(id + "_IDC", id + "_IDC", List.of(id + "_IDCM"), initial.dcCurrent() * 1.0e3 / initial.dcCurrentBase(),
                "u[1]/" + number(initial.dcCurrentBase()))));
    }

    /** An {@link OvercurrentProtection}'s detection {@code <id>_PTI}: the worst arm current, {@code |i_dc| / 3 + |i_k| / 2}, past its
     * threshold - times {@code 2 / Dt}, the latch's rate, as ATP's. */
    private static void protection(List<EmtpSynchronousMachines.Device> devices, String id, OvercurrentProtection protection, AveragedVscInitialState initial,
                                   EmtpSimulationSettings settings) {
        String threshold = number(protection.getThreshold() * OvercurrentProtection.ratedArmCurrentAmps(initial));
        String trips = "";
        for (int k = 2; k <= 4; k++) {
            trips += (k > 2 ? "+" : "") + step("ABS(u[1])/3+0.5*ABS(u[" + k + "])-" + threshold);
        }
        devices.add(function(id + "_PTI", id + "_PTI", List.of(id + "_IDCM", id + "_IMa", id + "_IMb", id + "_IMc"), 0.0,
                "(" + trips + ")*" + number(2.0 / settings.timeStepSeconds())));
    }

    /** A protection's latch on {@code input}: its integral - never falling, {@code input} being no less than 0 - within 1, then its delay's
     * timer, as ATP's - its flag {@code <prefix>F}, 1 until the delay has run from the trip. (The integral held at 1 by a gate on itself,
     * EMTP's control loop solved it at 0 for good; 2026-11-15.) */
    private static String protectionLatch(List<EmtpSynchronousMachines.Device> devices, String prefix, String input, OvercurrentProtection protection) {
        String state = prefix + "X";
        devices.add(integrator(state, state, input, 1.0, 0.0));
        devices.add(function(prefix + "L", prefix + "L", List.of(state), 0.0, "0.5*(u[1]+1-ABS(u[1]-1))"));
        String flag = prefix + "F";
        if (protection.getDelay() > 0.0) {
            devices.add(integrator(prefix + "T", prefix + "T", prefix + "L", 1.0, 0.0));
            devices.add(function(flag, flag, List.of(prefix + "T"), 1.0, step(number(protection.getDelay()) + "-u[1]")));
        } else {
            devices.add(function(flag, flag, List.of(prefix + "L"), 1.0, "1-u[1]"));
        }
        return flag;
    }

    /**
     * A blocked converter's integrals {@code <id><suffix>} held while it is blocked: each integrator's input times the running flag. Deblocked
     * ({@code restarts}), each restarted from 0, as ATP's: the integral read {@code (1 - D) X + X'}, {@code X'} the integral from 0 of its
     * input times {@code D}, the deblocked flag - the held integral renamed {@code <id><suffix>O}, the fresh one {@code <id><suffix>N}.
     */
    static void freeze(List<EmtpSynchronousMachines.Device> devices, String id, List<String> suffixes, boolean restarts) {
        List<EmtpSynchronousMachines.Device> gates = new ArrayList<>();
        for (int k = 0; k < devices.size(); k++) {
            EmtpSynchronousMachines.Device device = devices.get(k);
            String header = device.lines().get(0);
            for (String suffix : suffixes) {
                String prefix = "_c_fs;" + id + suffix + ";2;2;" + id + suffix + ",";
                if (header.startsWith(prefix)) {
                    String input = header.substring(prefix.length(), header.length() - 1);
                    String integral = id + suffix;
                    List<String> lines = new ArrayList<>(device.lines());
                    if (!restarts) {
                        lines.set(0, prefix + input + "F,");
                        devices.set(k, new EmtpSynchronousMachines.Device(device.part(), lines));
                        gates.add(function(input + "F", input + "F", List.of(input, id + "_RUN"), 0.0, "u[1]*u[2]"));
                        continue;
                    }
                    lines.set(0, "_c_fs;" + integral + "O;2;2;" + integral + "O," + input + "F,");
                    devices.set(k, new EmtpSynchronousMachines.Device(device.part(), lines));
                    gates.add(function(input + "F", input + "F", List.of(input, id + "_RUN"), 0.0, "u[1]*u[2]"));
                    List<String> fresh = new ArrayList<>(device.lines());
                    String data = fresh.get(1);
                    fresh.set(0, "_c_fs;" + integral + "N;2;2;" + integral + "N," + input + "D,");
                    fresh.set(1, "0" + data.substring(data.indexOf(',')));
                    gates.add(new EmtpSynchronousMachines.Device(device.part(), fresh));
                    // Restarting automatically, the fresh integral bled away while blocked: each restart from 0, as ATP's.
                    gates.add(function(input + "D", input + "D", List.of(input, id + "_DEB", id + "_RUN", integral + "N"), 0.0,
                            "u[1]*u[2]*u[3]-(1-u[3])*u[4]*" + number(1.0 / NumericalAids.RESTART_RESET_TIME_CONSTANT_SECONDS)));
                    double initialValue = Double.parseDouble(data.substring(0, data.indexOf(',')));
                    gates.add(function(integral, integral, List.of(integral + "O", integral + "N", id + "_DEB"), initialValue, "(1-u[3])*u[1]+u[2]"));
                }
            }
        }
        devices.addAll(gates);
    }

    /** {@code converter}'s power stage: AC side, phase by phase, its EMF, its stand-in, its reactor and its bus's probe - {@code v} and {@code
     * i} filled with the probes' and the EMFs' current signals -; DC side, its current source, its capacitor, its probes, its pole's
     * stand-in. */
    private static void powerStage(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedConverterModel model,
                                   AveragedVscInitialState initial, String[] phaseNodes, String[] v, String[] i, double emfMagnitude, double emfAngle,
                                   double omega, EmtpSimulationSettings settings) {
        String id = sanitize(converter.getId());
        String pole = dcNode(converter.getDcTerminal1().getDcNode());
        String ret = dcNode(converter.getDcTerminal2().getDcNode());
        String star = id + "_N";
        String zero = id + "_ZERO";
        // The power stage, AC side, phase by phase: the EMF, its stand-in, the reactor; the bus voltage's probe.
        double resistanceOhm = initial.phaseReactorResistanceOhm(model);
        double inductanceH = initial.phaseReactorInductanceH(model, omega);
        for (int k = 0; k < 3; k++) {
            char phase = "abc".charAt(k);
            double offset = -2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k);
            String reactorStart = id + "_K" + phase;
            String standIn = id + "_M" + phase;
            String junction = id + "_J" + phase;
            v[k] = id + "_V" + phase;
            i[k] = id + "_I" + phase;
            devices.add(element("_RLC", id + "_R" + phase, List.of(reactorStart, junction), number(resistanceOhm) + ",0,0,0,0,"));
            devices.add(element("_RLC", id + "_L" + phase, List.of(junction, phaseNodes[k]), "0," + number(inductanceH) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LD" + phase, List.of(junction, phaseNodes[k]),
                    number(NumericalAids.seriesInductanceDampingOhm(inductanceH, settings.timeStepSeconds())) + ",0,0,0,0,"));
            // The EMF's stand-in, in the steady state only.
            devices.add(element("_Vsine", id + "_STD" + phase, List.of(reactorStart, standIn), number(emfMagnitude) + "," + number(settings.nominalFrequencyHz()) + ","
                    + number(Math.toDegrees(emfAngle + offset)) + ",-1,1E-15,"));
            devices.add(element("_cV", id + "_CV" + phase, List.of(standIn, star, id + "_EMF" + phase, id + "_CVV" + phase, i[k], id + "_CVP" + phase),
                    "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            devices.add(element("_cI", id + "_PR" + phase, List.of(phaseNodes[k], "", zero, v[k], id + "_PRI" + phase, id + "_PRP" + phase),
                    "-1,1E15,0,0,0,0,>v,0,0,"));
        }
        devices.add(element("_RLC", id + "_NREF", List.of(star), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,"));
        devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + id + "_ZERO;1;1;" + zero + ",", "0,S1,")));

        // The DC side: the current source between its ties, the capacitor, the DC voltages' probes, the pole's stand-in - on the equivalent
        // capacitor's node, behind the DC series resistance and inductance (an MMC's two thirds of an arm, a DC reactor) if any; the DC
        // voltage measured there, or at the DC terminal, an MMC's.
        String terminal = pole;
        double seriesOhm = 0.0;
        if (model.hasDcReactor()) {
            double baseKv = converter.getTerminal1().getVoltageLevel().getNominalV();
            double seriesH = model.dcSeriesInductanceH(baseKv, omega);
            seriesOhm = model.dcSeriesResistanceOhm(baseKv);
            pole = id + "_CP";
            // Protected, its DC current read beyond its DC series inductance, on a meter at its DC terminal (below), as PSCAD's.
            String reactorEnd = isProtected(converter) ? id + "_DCI" : terminal;
            devices.add(element("_RLC", id + "_DCL", List.of(pole, reactorEnd), number(seriesOhm) + "," + number(seriesH) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_DCLD", List.of(pole, reactorEnd),
                    number(NumericalAids.seriesInductanceDampingOhm(seriesH, settings.timeStepSeconds())) + ",0,0,0,0,"));
            if (isProtected(converter)) {
                devices.add(element("_cV", id + "_DCMET", List.of(terminal, reactorEnd, id + "_ZERO", id + "_DCMV", id + "_IDCM", id + "_DCMP"),
                        "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            }
        }
        if (isProtected(converter) && !model.hasDcReactor()) {
            // Protected, its DC current read on a meter - a controlled source held at 0 V - from its own pole to its DC terminal 1's node.
            pole = id + "_CP";
            devices.add(element("_cV", id + "_DCMET", List.of(terminal, pole, id + "_ZERO", id + "_DCMV", id + "_IDCM", id + "_DCMP"),
                    "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
        }
        String measured = model.measuresDcVoltageAtTerminal() ? terminal : pole;
        String poleTie = id + "_DCP";
        String returnTie = id + "_DCR";
        String dcCurrent = id + "_IDCS";
        // EMTP's controlled current source drives its current out of its first pin into its second one through the network: from the pole.
        devices.add(element("_cI", id + "_DC", List.of(poleTie, returnTie, dcCurrent, id + "_DCV", id + "_DCI", id + "_DCPW"), "-1,1E15,0,0,0,0,0,0,0,"));
        devices.add(element("_RLC", id + "_TP", List.of(poleTie, pole), number(NumericalAids.CURRENT_SOURCE_TIE_RESISTANCE_OHM) + ",0,0,0,0,"));
        devices.add(element("_RLC", id + "_TR", List.of(returnTie, ret), number(NumericalAids.CURRENT_SOURCE_TIE_RESISTANCE_OHM) + ",0,0,0,0,"));
        if (converter.getExtension(DcEarthingModel.class) instanceof DcMidpointEarthing earthing) {
            // A symmetric monopole's converter earthing its DC side itself: its capacitor in two halves of twice its capacitance, their
            // midpoint earthed through its resistance - a solid earthing through a ground connection's 0.01 ohm, as ATP's.
            String midpoint = id + "_MID";
            String twice = number(2.0 * initial.dcCapacitanceF(model));
            devices.add(element("_RLC", id + "_CDC1", List.of(pole, midpoint), "0,0," + twice + ",0,0,"));
            devices.add(element("_RLC", id + "_CDC2", List.of(midpoint, ret), "0,0," + twice + ",0,0,"));
            devices.add(element("_RLC", id + "_MIDG", List.of(midpoint), number(earthing.getResistance() > 0.0 ? earthing.getResistance() : GROUND_CONNECTION_OHM)
                    + ",0,0,0,0,"));
        } else {
            devices.add(element("_RLC", id + "_CDC", List.of(pole, ret), "0,0," + number(initial.dcCapacitanceF(model)) + ",0,0,"));
        }
        String vPole = id + "_VDP";
        String vReturn = id + "_VDR";
        devices.add(element("_cI", id + "_PRDP", List.of(measured, "", zero, vPole, id + "_PRDPI", id + "_PRDPP"), "-1,1E15,0,0,0,0,>v,0,0,"));
        devices.add(element("_cI", id + "_PRDR", List.of(ret, "", zero, vReturn, id + "_PRDRI", id + "_PRDRP"), "-1,1E15,0,0,0,0,>v,0,0,"));
        if (!measured.equals(pole)) {
            devices.add(element("_cI", id + "_PRDK", List.of(pole, "", zero, id + "_VDK", id + "_PRDKI", id + "_PRDKP"), "-1,1E15,0,0,0,0,>v,0,0,"));
        }
        String standInNode = id + "_DCS";
        // Across the converter's terminals, its DC voltage from the load flow - a negative pole's converter has its neutral on terminal 1 -,
        // and the drop of its DC current across the series resistance.
        double dcVolts = (converter.getDcTerminal1().getDcNode().getV() - converter.getDcTerminal2().getDcNode().getV()) * 1.0e3
                + initial.dcCurrent() * 1.0e3 * seriesOhm;
        devices.add(element("_Vp", id + "_DCSTD", List.of(standInNode, ret), number(dcVolts) + ",-1,1E15,"));
        devices.add(element("_Sw0", id + "_DCSW", List.of(standInNode, pole), "-1," + number(settings.timeStepSeconds()) + ",1E9,0,0,,1,.1,.001,5,"));
    }

    /** The EMFs' instantaneous power, {@code <id>_PE}, from {@code <id>_ED}, {@code <id>_EQ} into {@code current}: with a sequence each, the
     * power of {@code E = E+ e^j theta + E- e^-j theta} into {@code I} alike, each sequence's own and the twice-frequency ripple of each
     * with the other, in the frame the EMF was computed in - in five functions, each header within EMTP's line. */
    private static String emfPower(List<EmtpSynchronousMachines.Device> devices, String id, AveragedVscInitialState initial, boolean dualSequence,
                                   Sequences current, String cos2, String sin2) {
        String emfPower = id + "_PE";
        double power0 = initial.directEmf() * initial.directCurrent() + initial.quadratureEmf() * initial.quadratureCurrent();
        String ed = id + "_ED";
        String eq = id + "_EQ";
        if (!dualSequence) {
            devices.add(function(emfPower, emfPower, List.of(ed, eq, current.positiveD(), current.positiveQ()), power0, "u[1]*u[3]+u[2]*u[4]"));
            return emfPower;
        }
        String edNegative = id + "_EDN";
        String eqNegative = id + "_EQN";
        String cd = current.positiveD();
        String cq = current.positiveQ();
        // Each sequence's own on a function of its own: one of eight inputs ran past EMTP's 100 characters under five-character ids.
        devices.add(function(id + "_PE1", id + "_PE1", List.of(ed, eq, cd, cq), power0, "u[1]*u[3]+u[2]*u[4]"));
        devices.add(function(id + "_PE2", id + "_PE2", List.of(edNegative, eqNegative, current.negativeD(), current.negativeQ()), 0.0, "u[1]*u[3]+u[2]*u[4]"));
        devices.add(function(id + "_PEP", id + "_PEP", List.of(ed, eq, current.negativeD(), current.negativeQ(), cos2, sin2), 0.0,
                "(u[1]*u[3]+u[2]*u[4])*u[5]-(u[2]*u[3]-u[1]*u[4])*u[6]"));
        devices.add(function(id + "_PEN", id + "_PEN", List.of(edNegative, eqNegative, cd, cq, cos2, sin2), 0.0,
                "(u[1]*u[3]+u[2]*u[4])*u[5]+(u[2]*u[3]-u[1]*u[4])*u[6]"));
        devices.add(function(emfPower, emfPower, List.of(id + "_PE1", id + "_PE2", id + "_PEP", id + "_PEN"), power0, "u[1]+u[2]+u[3]+u[4]"));
        return emfPower;
    }

    /**
     * The active-power loop's error {@code <id>_IDE}: its reference less the power. The reference its setpoint, or with a recovery ramp a
     * reference following the power the d axis's command delivers while the ride-through acts and back to the setpoint along the ramp;
     * then, in {@code DC_DROOP}, less its {@link DcVoltageDroopLaw}'s reduction {@code sum g_s (clamp(Vdc, s) - clamp(Vdc0, s))} ({@code
     * <id>_VR}, added back to the command the ramp's reference follows), within {@code [minP, maxP]}.
     */
    private static void activePowerError(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedVscInitialState initial,
                                         FaultRideThrough frt, String p, String vac, String vdc, double trackingGain) {
        String id = sanitize(converter.getId());
        Optional<DcVoltageDroopLaw> law = DcVoltageDroopLaw.of(converter, initial);
        String reduction = id + "_VR";
        if (law.isPresent()) {
            List<String> terms = new ArrayList<>();
            for (DcVoltageDroopLaw.Segment segment : law.get().segments()) {
                terms.add("(" + number(segment.gain()) + ")*(" + clampBetween("u[1]", number(segment.minVoltage()), number(segment.maxVoltage())) + "-("
                        + number(segment.clamp(law.get().dcVoltage0())) + "))");
            }
            devices.add(function(reduction, reduction, List.of(vdc), 0.0, String.join("+", terms)));
        }
        String reference = number(initial.activePower());
        List<String> inputs = new ArrayList<>(List.of(p));
        if (frt.hasActivePowerRecoveryRamp()) {
            String pref = id + "_PREF";
            String prefRate = id + "_PREFR";
            String commanded = "u[2]*u[3]" + (law.isPresent() ? "+u[5]" : "");
            String tracking = "(" + number(trackingGain) + ")*(" + commanded + "-u[4])";
            String toward = "(" + number(trackingGain) + ")*(" + number(initial.activePower()) + "-u[4])";
            String rate = number(frt.activePowerRecoveryRate());
            String limited = "0.5*((" + toward + "+" + rate + ")-ABS(" + toward + "-" + rate + "))";
            String clamped = "0.5*((" + limited + "-" + rate + ")+ABS(" + limited + "+" + rate + "))";
            List<String> rampInputs = new ArrayList<>(List.of(id + "_FRT", vac, id + "_IDLIM", pref));
            if (law.isPresent()) {
                rampInputs.add(reduction);
            }
            devices.add(function(prefRate, prefRate, rampInputs, 0.0, "u[1]*" + tracking + "+(1-u[1])*" + clamped));
            devices.add(integrator(pref, pref, prefRate, 1.0, initial.activePower()));
            inputs.add(pref);
            reference = "u[2]";
        }
        if (law.isPresent()) {
            inputs.add(reduction);
            reference = clampBetween(reference + "-u[" + inputs.size() + "]", number(law.get().minPower()), number(law.get().maxPower()));
        }
        devices.add(function(id + "_IDE", id + "_IDE", inputs, 0.0, reference + "-u[1]"));
    }

    /** {@code x} within {@code [low, high]}: {@code min(max(x, low), high)}. */
    static String clampBetween(String x, String low, String high) {
        String floored = "0.5*((" + x + ")+(" + low + ")+ABS((" + x + ")-(" + low + ")))";
        return "0.5*((" + floored + ")+(" + high + ")-ABS((" + floored + ")-(" + high + ")))";
    }

    /** The SRF PLL's PI on {@code input}, a q-axis voltage, its angle {@code theta = angle0 + omega t + int(Kp vq + Ki int vq)}. */
    private static void pll(List<EmtpSynchronousMachines.Device> devices, String id, GridFollowingControl control, String input, String theta,
                            double angle0, double omega) {
        devices.add(integrator(id + "_PLI", id + "_PLI", input, 1.0, 0.0));
        devices.add(function(id + "_PLD", id + "_PLD", List.of(input, id + "_PLI"), 0.0, "(" + number(control.getPllProportionalGain()) + ")*u[1]+("
                + number(control.getPllIntegralGain()) + ")*u[2]"));
        devices.add(integrator(id + "_PLA", id + "_PLA", id + "_PLD", 1.0, 0.0));
        devices.add(function(id + "_TH", theta, List.of(id + "_PLA"), angle0, "(" + number(angle0) + ")+(" + number(omega) + ")*t+u[1]"));
    }

    /** The negative sequence's current loop on {@code reference} - zero when {@code null}, or a negative-sequence injection's -, in its own
     * frame: {@code e- = LPF(v-*) - (R - jX) i- - u-}, {@code <id>_EDN} and {@code <id>_EQN}. */
    private static void negativeSequenceLoop(List<EmtpSynchronousMachines.Device> devices, String id, GridFollowingControl control, double x,
                                             Sequences voltage, Sequences current, String[] reference) {
        String[] negativeReference = reference;
        if (negativeReference == null) {
            String zeroRef = id + "_I0";
            devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + zeroRef + ";1;1;" + zeroRef + ",", "0,S1,")));
            negativeReference = new String[] {zeroRef, zeroRef};
        }
        String udNegative = innerPi(devices, id + "_UDN", negativeReference[0], current.negativeD(), control, 0.0);
        String uqNegative = innerPi(devices, id + "_UQN", negativeReference[1], current.negativeQ(), control, 0.0);
        devices.add(function(id + "_EDN", id + "_EDN", List.of(voltage.negativeFilteredD(), current.negativeQ(), udNegative), 0.0,
                "u[1]-(" + number(x) + ")*u[2]-u[3]"));
        devices.add(function(id + "_EQN", id + "_EQN", List.of(voltage.negativeFilteredQ(), current.negativeD(), uqNegative), 0.0,
                "u[1]+(" + number(x) + ")*u[2]-u[3]"));
    }

    /** A measured quantity's dq components on each sequence: the negative sequence's {@code null} when the control does not separate
     * them, the positive sequence's then the whole measurement in the positive frame. */
    record Sequences(String positiveD, String positiveQ, String negativeD, String negativeQ, String negativeFilteredD, String negativeFilteredQ) {
    }

    /**
     * A {@link Ddsrf} on one measured quantity, {@code prefix} naming its signals: its components in the negative frame ({@code x- = S
     * e^+j theta}), then each sequence less the other's twice-frequency ripple, rebuilt from that sequence's filtered components - with
     * {@code x = d + jq}, {@code x+* = x+ - e^-j2theta LPF(x-*)}, {@code x-* = x- - e^+j2theta LPF(x+*)}. Its filters at rest on the
     * steady state: the positive sequence at {@code positiveD0}, {@code positiveQ0}, the negative at zero.
     */
    private static Sequences ddsrf(List<EmtpSynchronousMachines.Device> devices, Ddsrf ddsrf, String prefix, String positiveD, String positiveQ,
                                   String[] phases, String theta, double base, String cos2, String sin2, double positiveD0, double positiveQ0) {
        String negativeD = prefix + "ND";
        String negativeQ = prefix + "NQ";
        // The negative frame turns the other way: phases b and c swapped in the positive frame's transform, its q axis not negated.
        String[] swapped = {phases[0], phases[2], phases[1]};
        devices.add(park(negativeD, negativeD, swapped, theta, "COS", base, 0.0, false));
        devices.add(park(negativeQ, negativeQ, swapped, theta, "SIN", base, 0.0, false));
        String[] decoupled = {prefix + "E1", prefix + "E2", prefix + "E3", prefix + "E4"};
        String[] filtered = {prefix + "F1", prefix + "F2", prefix + "F3", prefix + "F4"};
        double[] steady = {positiveD0, positiveQ0, 0.0, 0.0};
        devices.add(function(decoupled[0], decoupled[0], List.of(positiveD, filtered[2], filtered[3], cos2, sin2), steady[0], "u[1]-(u[2]*u[4]+u[3]*u[5])"));
        devices.add(function(decoupled[1], decoupled[1], List.of(positiveQ, filtered[2], filtered[3], cos2, sin2), steady[1], "u[1]-(u[3]*u[4]-u[2]*u[5])"));
        devices.add(function(decoupled[2], decoupled[2], List.of(negativeD, filtered[0], filtered[1], cos2, sin2), steady[2], "u[1]-(u[2]*u[4]-u[3]*u[5])"));
        devices.add(function(decoupled[3], decoupled[3], List.of(negativeQ, filtered[0], filtered[1], cos2, sin2), steady[3], "u[1]-(u[3]*u[4]+u[2]*u[5])"));
        double timeConstant = 1.0 / (2.0 * Math.PI * ddsrf.filterFrequency());
        for (int k = 0; k < 4; k++) {
            devices.add(firstOrder(filtered[k], filtered[k], decoupled[k], steady[k], 1.0, 1.0, 0.0, timeConstant));
        }
        return new Sequences(decoupled[0], decoupled[1], decoupled[2], decoupled[3], filtered[2], filtered[3]);
    }

    /**
     * A {@link NegativeSequenceInjection}'s current reference, {@code {d, q}} in the negative frame: {@code k} times the negative
     * sequence's filtered voltage, 90 degrees behind it, drawn - {@code i- = +j k v-} in that frame, which reads the phasors conjugated -,
     * weighted by the ride-through; scaled down, its direction kept, to the crest {@code Imax - |q+|} the positive sequence's reactive
     * current leaves ({@code <id>_YG} the scale, {@code <id>_YE} the unscaled magnitude).
     */
    private static String[] negativeSequenceReference(List<EmtpSynchronousMachines.Device> devices, String id, NegativeSequenceInjection injection,
                                                      double maxCurrent, String frt, String qFirst, Sequences voltage) {
        String k = number(injection.reactiveCurrentGain());
        String rawD = id + "_YC";
        String rawQ = id + "_YD";
        String magnitude = id + "_YE";
        String scale = id + "_YG";
        devices.add(function(rawD, rawD, List.of(frt, voltage.negativeFilteredQ()), 0.0, "-u[1]*" + k + "*u[2]"));
        devices.add(function(rawQ, rawQ, List.of(frt, voltage.negativeFilteredD()), 0.0, "u[1]*" + k + "*u[2]"));
        devices.add(function(magnitude, magnitude, List.of(rawD, rawQ), 0.0, "SQRT(u[1]*u[1]+u[2]*u[2])"));
        String room = "0.5*((" + number(maxCurrent) + "-ABS(u[1]))+ABS(" + number(maxCurrent) + "-ABS(u[1])))";
        String floored = "0.5*(u[2]+" + number(MEASUREMENT_EPS) + "+ABS(u[2]-" + number(MEASUREMENT_EPS) + "))";
        String ratio = "(" + room + ")/(" + floored + ")";
        devices.add(function(scale, scale, List.of(qFirst, magnitude), 1.0, "0.5*(" + ratio + "+1-ABS(" + ratio + "-1))"));
        String referenceD = id + "_YH";
        String referenceQ = id + "_YI";
        devices.add(function(referenceD, referenceD, List.of(scale, rawD), 0.0, "u[1]*u[2]"));
        devices.add(function(referenceQ, referenceQ, List.of(scale, rawQ), 0.0, "u[1]*u[2]"));
        return new String[] {referenceD, referenceQ};
    }

    /** A PI {@code KP (ref - meas) + KI int(ref - meas)}, its integral at {@code initialOutput}: the inner current loop's. */
    private static String innerPi(List<EmtpSynchronousMachines.Device> devices, String name, String reference, String measured, GridFollowingControl control,
                                  double initialOutput) {
        String error = name + "E";
        String integral = name + "I";
        devices.add(function(name + "E", error, List.of(reference, measured), 0.0, "u[1]-u[2]"));
        devices.add(integrator(name + "I", integral, error, control.getCurrentIntegralGain(), initialOutput));
        devices.add(function(name, name, List.of(error, integral), initialOutput, "(" + number(control.getCurrentProportionalGain()) + ")*u[1]+u[2]"));
        return name;
    }

    /** The Park transform of the three phases {@code signals} on {@code theta}: {@code (2/3) sum_k f(theta - k 2pi/3) x_k / base}, negated
     * for the q axis. */
    static EmtpSynchronousMachines.Device park(String device, String output, String[] signals, String theta, String function, double base,
                                                       double history, boolean negated) {
        String third = number(2.0 * Math.PI / 3.0);
        String sum = "u[1]*" + function + "(u[4])+u[2]*" + function + "(u[4]-" + third + ")+u[3]*" + function + "(u[4]+" + third + ")";
        return function(device, output, List.of(signals[0], signals[1], signals[2], theta), history,
                (negated ? "-" : "") + "(2/3)*(" + sum + ")/" + number(base));
    }

    /** {@code expression} through the measurement's lag {@code 1 / (1 + s tau)}, at rest on {@code initial}; itself without one. */
    static String measurement(List<EmtpSynchronousMachines.Device> devices, String name, List<String> inputs, String expression, double tau, double initial) {
        if (tau <= 0.0) {
            devices.add(scoped(function(name, name, inputs, initial, expression)));
            return name;
        }
        devices.add(function(name + "U", name + "U", inputs, initial, expression));
        devices.add(scoped(firstOrder(name, name, name + "U", initial, 1.0, 1.0, 0.0, tau)));
        return name;
    }

    /** {@code device} with its output's scope requested: a converter's measurements, as the other backends record them ({@code
     * control:<id>_P}). */
    static EmtpSynchronousMachines.Device scoped(EmtpSynchronousMachines.Device device) {
        List<String> lines = new ArrayList<>(device.lines());
        lines.set(1, lines.get(1) + "?s,");
        return new EmtpSynchronousMachines.Device(device.part(), lines);
    }

    /** An integrator {@code gain / s} from {@code input} to {@code output}, at {@code initial}. */
    static EmtpSynchronousMachines.Device integrator(String device, String output, String input, double gain, double initial) {
        return new EmtpSynchronousMachines.Device("_c_fs", List.of("_c_fs;" + device + ";2;2;" + output + "," + input + ",", number(initial) + ",1,1,2," + number(gain)
                + ",S1,", "1", ";", "0 1"));
    }

    /** ATP's plain step: 1 for a positive {@code x}, 0 otherwise. */
    static String step(String x) {
        return "0.5*(1+SIGN(" + x + "))";
    }

    /** A conditional integration's gate: 0 while {@code output} is at its limit and {@code error} pushes it past, 1 otherwise. */
    static String windupGate(String output, String limit, String error) {
        return "(1-" + step(output + "-" + limit) + "*" + step(error) + "-" + step("-" + limit + "-" + output) + "*(1-" + step(error) + "))";
    }

    /** {@code x} within {@code [-limit, limit]}: {@code max(min(x, limit), -limit)}. */
    static String clampExpression(String x, String limit) {
        String capped = "0.5*((" + x + ")+(" + limit + ")-ABS((" + x + ")-(" + limit + ")))";
        return "0.5*((" + capped + ")-(" + limit + ")+ABS((" + capped + ")+(" + limit + ")))";
    }

    /** {@code x} within {@code [0, 1]}. */
    private static String clamp01(String x) {
        String floored = "0.5*((" + x + ")+ABS(" + x + "))";
        return "0.5*((" + floored + ")+1-ABS((" + floored + ")-1))";
    }

    /** An electrical device: its header - part, name, its pins twice, its nodes - and its data line. */
    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",", data));
    }

    static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
