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
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.DcSwitchOperation;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.hvdc.AveragedDcChopperModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.DcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.DcChopperModel;
import com.powsybl.simuemt.extensions.hvdc.DcEarthingModel;
import com.powsybl.simuemt.extensions.hvdc.DcLineModel;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.HybridDcBreakerModel;
import com.powsybl.simuemt.extensions.hvdc.LumpedDcLineModel;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.extensions.hvdc.control.Ddsrf;
import com.powsybl.simuemt.extensions.hvdc.control.DdsrfPll;
import com.powsybl.simuemt.extensions.hvdc.control.DualSequenceCurrentControl;
import com.powsybl.simuemt.extensions.hvdc.control.FaultRideThrough;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.NegativeSequenceInjection;
import com.powsybl.simuemt.extensions.hvdc.control.OuterLoops;
import com.powsybl.simuemt.extensions.hvdc.control.SrfPll;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;

import java.util.ArrayList;
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
 * {@link NumericalAids#CURRENT_SOURCE_TIE_RESISTANCE_OHM}; and the DC capacitor across the two DC nodes.
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
 * <p>Refused, until written: an MMC, a DC reactor, a grid-forming control, a PLL but the SRF and DDSRF ones, a protection, a DC
 * midpoint earthing, a chopper but the averaged one.
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
        if (!(converter.getExtension(VoltageSourceConverterModel.class) instanceof AveragedVscModel model)) {
            reasons.add("its model is not an averaged two-level VSC");
            return reasons;
        }
        if (model.hasDcReactor()) {
            reasons.add("its DC reactor is not written yet");
        }
        if (!(converter.getExtension(VscControlModel.class) == null || converter.getExtension(VscControlModel.class) instanceof GridFollowingControl)) {
            reasons.add("its grid-forming control is not written yet");
            return reasons;
        }
        GridFollowingControl control = GridFollowingControl.of(converter);
        if (!(control.getSynchronization() instanceof SrfPll || control.getSynchronization() instanceof DdsrfPll)) {
            reasons.add("its PLL but the SRF and DDSRF ones are not written yet");
        }
        if (converter.getExtension(VscProtectionModel.class) != null) {
            reasons.add("its protection is not written yet");
        }
        if (converter.getExtension(DcEarthingModel.class) != null) {
            reasons.add("its DC earthing is not written yet");
        }
        DcChopperModel chopper = converter.getExtension(DcChopperModel.class);
        if (chopper != null && !(chopper instanceof AveragedDcChopperModel)) {
            reasons.add("its chopper but the averaged one is not written yet");
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

    /** Why the DC events cannot be written yet - empty if they can: a DC switch operated but a hybrid breaker's opening, once. */
    static List<String> unsupportedDcEventReasons(Network network, List<? extends EmtEvent> events) {
        List<String> reasons = new ArrayList<>();
        Map<String, Integer> operations = new HashMap<>();
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
                        || operation.action() != BreakerAction.OPEN || operations.merge(dcSwitch.getId(), 1, Integer::sum) > 1) {
                    reasons.add("DC switch '" + dcSwitch.getId() + "' is operated but as a closed hybrid breaker opened once, which is not written yet");
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
            Optional<DcSwitchOperation> opening = events.stream().filter(e -> e instanceof DcSwitchOperation o && o.switchId().equals(dcSwitch.getId()))
                    .map(DcSwitchOperation.class::cast).findFirst();
            dcSwitch(devices, dcSwitch, opening.map(DcSwitchOperation::timeSeconds).orElse(Double.NaN), settings);
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
     * nothing. A hybrid breaker opened at {@code openingSeconds}: its reactor, if any, from DC node 1 to a node of its own, then its main
     * branch - that resistance and a switch closed from the steady state on, opened at the order whatever its current - and across it its
     * energy absorber, an EMTP ZnO column (its first segment from {@link #ZNO_FIRST_SEGMENT_PU} at the least: EMTP joins it to the origin
     * linearly, at currents nothing reads); with a reactor, the breaker's RC across them ({@link NumericalAids#DC_BREAKER_SNUBBER_F}).
     */
    private static void dcSwitch(List<EmtpSynchronousMachines.Device> devices, DcSwitch dcSwitch, double openingSeconds, EmtpSimulationSettings settings) {
        String id = sanitize(dcSwitch.getId());
        String node1 = dcNode(dcSwitch.getDcNode1());
        String node2 = dcNode(dcSwitch.getDcNode2());
        double resistance = Math.max(dcSwitch.getR(), NumericalAids.FAULT_ON_RESISTANCE_OHM);
        HybridDcBreakerModel model = dcSwitch.getExtension(DcBreakerModel.class) instanceof HybridDcBreakerModel hybrid ? hybrid : null;
        double reactor = model == null ? 0.0 : model.getReactorInductance();
        if (Double.isNaN(openingSeconds)) {
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
        devices.add(element("_Sw0", id, List.of(inner, node2), "-1," + number(NumericalAids.switchingInstant(openingSeconds, settings.timeStepSeconds())) + ","
                + number(ANY_CURRENT_AMPERES) + ",0,0,,1,.1,.001,5,?i,"));
        ZnOArrester arrester = model.getArrester();
        List<String> lines = new ArrayList<>(List.of("_ZnO;" + id + "_ZNO;2;2;" + side + "," + node2 + ",",
                number(arrester.referenceVoltage()) + ",,,1e-06,15,5,1,?i,"));
        for (int k = 0; k < arrester.segments().size(); k++) {
            ZnOSegment segment = arrester.segments().get(k);
            double minimum = k == 0 ? Math.max(segment.minVoltage(), ZNO_FIRST_SEGMENT_PU) : segment.minVoltage();
            lines.add(number(segment.coefficient()) + " " + number(segment.exponent()) + " " + number(minimum));
        }
        devices.add(new EmtpSynchronousMachines.Device("_ZnO", lines));
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
        AveragedVscModel model = (AveragedVscModel) converter.getExtension(VoltageSourceConverterModel.class);
        AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
        GridFollowingControl control = GridFollowingControl.of(converter);
        OuterLoops loops = control.getOuterLoops();
        FaultRideThrough frt = control.getFaultRideThrough();
        double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
        double vbpk = initial.peakPhaseVoltageBase();
        double ibpk = initial.peakPhaseCurrentBase();
        double x = model.getPhaseReactorReactance();
        double r = model.getPhaseReactorResistance();
        double angle0 = Math.toRadians(initial.terminalAngle() + offsetDegrees);
        double dcBaseVolts = initial.dcNominalVoltage() * 1.0e3;
        double tau = control.getMeasurement().timeConstant();
        String id = sanitize(converter.getId());
        String pole = dcNode(converter.getDcTerminal1().getDcNode());
        String ret = dcNode(converter.getDcTerminal2().getDcNode());
        String star = id + "_N";
        String zero = id + "_ZERO";
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();

        double emfMagnitude = Math.hypot(initial.directEmf(), initial.quadratureEmf()) * vbpk;
        double emfAngle = angle0 + Math.atan2(initial.quadratureEmf(), initial.directEmf());
        String[] v = new String[3];
        String[] i = new String[3];
        powerStage(devices, converter, model, initial, phaseNodes, v, i, emfMagnitude, emfAngle, omega, settings);
        String dcCurrent = id + "_IDC";
        String vPole = id + "_VDP";
        String vReturn = id + "_VDR";

        // The control: Park transform on the PLL's angle.
        String theta = id + "_TH";
        String vd = id + "_VD";
        String vq = id + "_VQ";
        String idPu = id + "_ID";
        String iqPu = id + "_IQ";
        devices.add(park(id + "_VD", vd, v, theta, "COS", vbpk, initial.terminalVoltage(), false));
        devices.add(park(id + "_VQ", vq, v, theta, "SIN", vbpk, 0.0, true));
        // The source's current is counted from its star point to its EMF, out of the converter: negated, the current from the bus into it.
        devices.add(park(id + "_ID", idPu, i, theta, "COS", -ibpk, initial.directCurrent(), false));
        devices.add(park(id + "_IQ", iqPu, i, theta, "SIN", -ibpk, initial.quadratureCurrent(), true));
        // The PLL - on the positive sequence's decoupled q-axis voltage alone, a DDSRF's (named ahead: EMTP's devices are in no order).
        pll(devices, id, control, control.getSynchronization() instanceof DdsrfPll ? id + "_BE2" : vq, theta, angle0, omega);

        // The sequences, if the control separates them (a DDSRF): each sequence's voltage and current, the other's twice-frequency ripple
        // taken off - or the whole measurement, read in the positive frame, as the positive sequence.
        Sequences voltage = new Sequences(vd, vq, null, null, null, null);
        Sequences current = new Sequences(idPu, iqPu, null, null, null, null);
        String cos2 = id + "_C2";
        String sin2 = id + "_S2";
        if (control.getSequenceSeparation() instanceof Ddsrf ddsrf) {
            devices.add(function(cos2, cos2, List.of(theta), Math.cos(2.0 * angle0), "COS(2*u[1])"));
            devices.add(function(sin2, sin2, List.of(theta), Math.sin(2.0 * angle0), "SIN(2*u[1])"));
            voltage = ddsrf(devices, ddsrf, id + "_B", vd, vq, v, theta, vbpk, cos2, sin2, initial.terminalVoltage(), 0.0);
            current = ddsrf(devices, ddsrf, id + "_Z", idPu, iqPu, i, theta, -ibpk, cos2, sin2, initial.directCurrent(), initial.quadratureCurrent());
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
        // The EMFs a step ahead of the angle the PLL tracks: EMTP's network takes a control signal a step after the control computes it,
        // as ATP's does - its EMFs rebuilt a step ahead too.
        String ahead = "+" + number(omega * settings.timeStepSeconds());
        // The negative sequence's frame turns the other way: phase b's angle the positive frame's phase c's, and c's b's.
        String third = number(2.0 * Math.PI / 3.0);
        List<String> emfInputs = dualSequence ? List.of(ed, eq, theta, edNegative, eqNegative) : List.of(ed, eq, theta);
        for (int k = 0; k < 3; k++) {
            String shift = ahead + (k == 0 ? "" : k == 1 ? "-" + third : "+" + third);
            String negative = ahead + (k == 0 ? "" : k == 1 ? "+" + third : "-" + third);
            devices.add(function(id + "_EMF" + "abc".charAt(k), id + "_EMF" + "abc".charAt(k), emfInputs, emfMagnitude * Math.cos(emfAngle - 2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k)),
                    "(" + number(vbpk) + ")*(u[1]*COS(u[3]" + shift + ")-u[2]*SIN(u[3]" + shift + ")"
                            + (dualSequence ? "+u[4]*COS(u[3]" + negative + ")+u[5]*SIN(u[3]" + negative + ")" : "") + ")"));
        }

        // The DC current: the EMFs' power over the DC voltage, less the chopper's. With a sequence each, the power of E = E+ e^j theta +
        // E- e^-j theta into I alike: each sequence's own and the twice-frequency ripple of each with the other, in the frame the EMF
        // was computed in.
        String emfPower = emfPower(devices, id, initial, dualSequence, current, cos2, sin2);
        String chopper = "";
        List<String> dcInputs = new ArrayList<>(List.of(emfPower, vPole, vReturn));
        if (converter.getExtension(DcChopperModel.class) instanceof AveragedDcChopperModel averaged) {
            double span = averaged.getFullConductionVoltage() - averaged.getActivationVoltage();
            String duty = id + "_DCH";
            devices.add(function(id + "_DCH", duty, List.of(vPole, vReturn), 0.0, clamp01("((u[1]-u[2])/" + number(dcBaseVolts) + "-" + number(averaged.getActivationVoltage())
                    + ")/" + number(span))));
            dcInputs.add(duty);
            chopper = "-u[4]*(u[2]-u[3])/" + number(averaged.resistanceOhm(initial.dcNominalVoltage()));
        }
        String floor = number(DC_VOLTAGE_FLOOR_PU * dcBaseVolts);
        devices.add(function(id + "_IDCF", dcCurrent, dcInputs, initial.dcCurrent() * 1.0e3, "u[1]*" + number(initial.baseApparentPower() * 1.0e6)
                + "/(0.5*((u[2]-u[3])+" + floor + "+ABS((u[2]-u[3])-" + floor + ")))" + chopper));
        return devices;
    }

    /** {@code converter}'s power stage: AC side, phase by phase, its EMF, its stand-in, its reactor and its bus's probe - {@code v} and {@code
     * i} filled with the probes' and the EMFs' current signals -; DC side, its current source, its capacitor, its probes, its pole's
     * stand-in. */
    private static void powerStage(List<EmtpSynchronousMachines.Device> devices, VoltageSourceConverter converter, AveragedVscModel model,
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

        // The DC side: the current source between its ties, the capacitor, the DC voltages' probes, the pole's stand-in.
        String poleTie = id + "_DCP";
        String returnTie = id + "_DCR";
        String dcCurrent = id + "_IDC";
        // EMTP's controlled current source drives its current out of its first pin into its second one through the network: from the pole.
        devices.add(element("_cI", id + "_DC", List.of(poleTie, returnTie, dcCurrent, id + "_DCV", id + "_DCI", id + "_DCPW"), "-1,1E15,0,0,0,0,0,0,0,"));
        devices.add(element("_RLC", id + "_TP", List.of(poleTie, pole), number(NumericalAids.CURRENT_SOURCE_TIE_RESISTANCE_OHM) + ",0,0,0,0,"));
        devices.add(element("_RLC", id + "_TR", List.of(returnTie, ret), number(NumericalAids.CURRENT_SOURCE_TIE_RESISTANCE_OHM) + ",0,0,0,0,"));
        devices.add(element("_RLC", id + "_CDC", List.of(pole, ret), "0,0," + number(initial.dcCapacitanceF(model)) + ",0,0,"));
        String vPole = id + "_VDP";
        String vReturn = id + "_VDR";
        devices.add(element("_cI", id + "_PRDP", List.of(pole, "", zero, vPole, id + "_PRDPI", id + "_PRDPP"), "-1,1E15,0,0,0,0,>v,0,0,"));
        devices.add(element("_cI", id + "_PRDR", List.of(ret, "", zero, vReturn, id + "_PRDRI", id + "_PRDRP"), "-1,1E15,0,0,0,0,>v,0,0,"));
        String standInNode = id + "_DCS";
        // Across the converter's terminals, its DC voltage from the load flow: a negative pole's converter has its neutral on terminal 1.
        double dcVolts = (converter.getDcTerminal1().getDcNode().getV() - converter.getDcTerminal2().getDcNode().getV()) * 1.0e3;
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
    private static String clampBetween(String x, String low, String high) {
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
    private record Sequences(String positiveD, String positiveQ, String negativeD, String negativeQ, String negativeFilteredD, String negativeFilteredQ) {
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
    private static EmtpSynchronousMachines.Device park(String device, String output, String[] signals, String theta, String function, double base,
                                                       double history, boolean negated) {
        String third = number(2.0 * Math.PI / 3.0);
        String sum = "u[1]*" + function + "(u[4])+u[2]*" + function + "(u[4]-" + third + ")+u[3]*" + function + "(u[4]+" + third + ")";
        return function(device, output, List.of(signals[0], signals[1], signals[2], theta), history,
                (negated ? "-" : "") + "(2/3)*(" + sum + ")/" + number(base));
    }

    /** {@code expression} through the measurement's lag {@code 1 / (1 + s tau)}, at rest on {@code initial}; itself without one. */
    private static String measurement(List<EmtpSynchronousMachines.Device> devices, String name, List<String> inputs, String expression, double tau, double initial) {
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
    private static EmtpSynchronousMachines.Device scoped(EmtpSynchronousMachines.Device device) {
        List<String> lines = new ArrayList<>(device.lines());
        lines.set(1, lines.get(1) + "?s,");
        return new EmtpSynchronousMachines.Device(device.part(), lines);
    }

    /** An integrator {@code gain / s} from {@code input} to {@code output}, at {@code initial}. */
    private static EmtpSynchronousMachines.Device integrator(String device, String output, String input, double gain, double initial) {
        return new EmtpSynchronousMachines.Device("_c_fs", List.of("_c_fs;" + device + ";2;2;" + output + "," + input + ",", number(initial) + ",1,1,2," + number(gain)
                + ",S1,", "1", ";", "0 1"));
    }

    /** ATP's plain step: 1 for a positive {@code x}, 0 otherwise. */
    private static String step(String x) {
        return "0.5*(1+SIGN(" + x + "))";
    }

    /** A conditional integration's gate: 0 while {@code output} is at its limit and {@code error} pushes it past, 1 otherwise. */
    private static String windupGate(String output, String limit, String error) {
        return "(1-" + step(output + "-" + limit) + "*" + step(error) + "-" + step("-" + limit + "-" + output) + "*(1-" + step(error) + "))";
    }

    /** {@code x} within {@code [-limit, limit]}: {@code max(min(x, limit), -limit)}. */
    private static String clampExpression(String x, String limit) {
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

    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
