/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.svc.StaticVarCompensatorModel;
import com.powsybl.simuemt.extensions.svc.Svc1InitialState;
import com.powsybl.simuemt.extensions.svc.Svc1Model;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * An {@link Svc1Model} in EMTP, as a real SVC is built and as upstream's backends model it: a fixed capacitor bank carrying its capacitive
 * range, {@link Svc1InitialState#fixedCapacitorSusceptance}, and a controlled reactor carrying the rest of the regulator's susceptance
 * {@code B}, never positive - an EMF {@code e = v (1 + Xc (B - Bfc))} behind a coupling reactance {@code Xc}, which draws {@code -j(B -
 * Bfc) v}. The regulator: the bus's voltage magnitude, {@code sqrt(2/3 (va^2 + vb^2 + vc^2))} - exact for a balanced set -, through its
 * transducer's lag; its error {@code Vref - V - Xs B V}, the droop on the regulator's own reactive current; {@code B} its gain-lag {@code
 * Kr / (1 + sT1)} within {@code [Bmin, Bmax]}. Every block at rest on the load flow, {@code Vref} the one holding it there ({@link
 * Svc1InitialState}).
 *
 * <p>EMTP's network takes a control signal a step after the control computes it: each phase's EMF is built from that phase's voltage
 * advanced a step from its own two last samples, {@code v(t + Dt) = 2 cos(w Dt) v(t) - v(t - Dt)}, exact for any sinusoid at the network's
 * frequency whatever its sequence - the last sample read off a lag {@code 1 / (1 + s Dt/2)}, which EMTP's trapezoidal rule makes {@code
 * (v(t) + v(t - Dt)) / 2}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpStaticVarCompensators {

    /** The coupling reactance, per unit on {@link Svc1InitialState#BASE_APPARENT_POWER_MVA}: an implementation detail of this average-value
     * model, as upstream's backends size theirs. */
    static final double COUPLING_REACTANCE_PU = 0.1;

    private static final char[] PHASES = {'a', 'b', 'c'};

    private EmtpStaticVarCompensators() {
    }

    /** Why {@code svc} cannot be written yet - empty if it can. */
    static List<String> unsupportedReasons(StaticVarCompensator svc) {
        return svc.getExtension(StaticVarCompensatorModel.class) instanceof Svc1Model ? List.of()
                : List.of("its model but SVC1 is not written yet");
    }

    /** {@code svc}'s devices on its bus's phases {@code phaseNodes}, its bus displaced by {@code offsetDegrees} - its vector group's. */
    static List<EmtpSynchronousMachines.Device> svc(StaticVarCompensator svc, String[] phaseNodes, double offsetDegrees, EmtpSimulationSettings settings) {
        Svc1Model model = (Svc1Model) svc.getExtension(StaticVarCompensatorModel.class);
        Svc1InitialState initial = Svc1InitialState.of(svc, model);
        Bus bus = svc.getTerminal().getBusBreakerView().getBus();
        double nominalKv = bus.getVoltageLevel().getNominalV();
        double omega = 2.0 * Math.PI * settings.nominalFrequencyHz();
        double dt = settings.timeStepSeconds();
        double vbpk = nominalKv * 1.0e3 * Math.sqrt(2.0 / 3.0);
        double crest = initial.terminalVoltage() * vbpk;
        double angle = Math.toRadians(bus.getAngle() + offsetDegrees);
        double fixed = Svc1InitialState.fixedCapacitorSusceptance(model);
        double controlled0 = initial.susceptance() - fixed;
        String id = EmtpVoltageSourceConverters.sanitize(svc.getId());
        String zero = id + "_ZERO";
        String controlled = id + "_BC";
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        devices.add(new EmtpSynchronousMachines.Device("_c_cst", List.of("_c_cst;" + zero + ";1;1;" + zero + ",", "0,S1,")));

        double couplingH = COUPLING_REACTANCE_PU * nominalKv * nominalKv / Svc1InitialState.BASE_APPARENT_POWER_MVA / omega;
        double capacitance = Svc1InitialState.siemens(fixed, nominalKv) / omega;
        String star = id + "_N";
        String[] v = new String[3];
        for (int k = 0; k < 3; k++) {
            char phase = PHASES[k];
            double phaseAngle = angle - 2.0 * Math.PI / 3.0 * (k == 2 ? -1 : k);
            String node = phaseNodes[k];
            v[k] = id + "_V" + phase;
            // The bus voltage's probe; its last sample; the EMF a step ahead.
            devices.add(element("_cI", id + "_PR" + phase, List.of(node, "", zero, v[k], id + "_PRI" + phase, id + "_PRP" + phase), "-1,1E15,0,0,0,0,>v,0,0,"));
            double last = 0.5 * crest * (Math.cos(phaseAngle) + Math.cos(phaseAngle - omega * dt));
            devices.add(firstOrder(id + "_H" + phase, id + "_H" + phase, v[k], last, 1.0, 1.0, 0.0, dt / 2.0));
            double emf0 = crest * (1.0 + COUPLING_REACTANCE_PU * controlled0);
            devices.add(function(id + "_E" + phase, id + "_E" + phase, List.of(v[k], id + "_H" + phase, controlled), emf0 * Math.cos(phaseAngle),
                    "(" + number(2.0 * Math.cos(omega * dt) + 1.0) + "*u[1]-2*u[2])*(1+(" + number(COUPLING_REACTANCE_PU) + ")*u[3])"));
            // The controlled reactor: its EMF - its stand-in in the steady state - behind the coupling reactance.
            String inner = id + "_K" + phase;
            String standIn = id + "_M" + phase;
            devices.add(element("_RLC", id + "_LC" + phase, List.of(inner, node), "0," + number(couplingH) + ",0,0,0,"));
            devices.add(element("_RLC", id + "_LCD" + phase, List.of(inner, node), number(NumericalAids.seriesInductanceDampingOhm(couplingH, dt)) + ",0,0,0,0,"));
            devices.add(element("_Vsine", id + "_STD" + phase, List.of(inner, standIn), number(emf0) + "," + number(settings.nominalFrequencyHz()) + ","
                    + number(Math.toDegrees(phaseAngle)) + ",-1,1E-15,"));
            devices.add(element("_cV", id + "_CV" + phase, List.of(standIn, star, id + "_E" + phase, id + "_CVV" + phase, id + "_CVI" + phase, id + "_CVP" + phase),
                    "-1,1E15,0,?v,?i,?p,>v,>i,>p,"));
            // The fixed capacitor bank, Dt/16C in series with it as with a shunt's.
            if (capacitance > 0.0) {
                devices.add(element("_RLC", id + "_C" + phase, List.of(node), number(NumericalAids.shuntCapacitanceDampingOhm(capacitance, dt)) + ",0,"
                        + number(capacitance) + ",0,0,"));
            }
        }
        devices.add(element("_RLC", id + "_NREF", List.of(star), number(NumericalAids.BUS_EARTH_REFERENCE_OHM) + ",0,0,0,0,"));

        // The regulator.
        String measured = id + "_VM";
        String filtered = id + "_VF";
        String error = id + "_VE";
        String susceptance = id + "_B";
        double vt0 = initial.terminalVoltage();
        double b0 = initial.susceptance();
        devices.add(EmtpVoltageSourceConverters.scoped(function(measured, measured, List.of(v[0], v[1], v[2]), vt0,
                "SQRT((u[1]*u[1]+u[2]*u[2]+u[3]*u[3])*2/3)/" + number(vbpk))));
        devices.add(EmtpVoltageSourceConverters.scoped(firstOrder(filtered, filtered, measured, vt0, 1.0, 1.0, 0.0, model.getTransducerTimeConstant())));
        devices.add(function(error, error, List.of(filtered, susceptance), b0 / model.getRegulatorGain(),
                number(initial.voltageReference()) + "-u[1]-(" + number(model.getDroopReactance()) + ")*u[2]*u[1]"));
        devices.add(EmtpVoltageSourceConverters.scoped(new EmtpSynchronousMachines.Device("_c_fslim", List.of("_c_fslim;" + susceptance + ";4;4;" + susceptance + ","
                + error + ",0,0,", number(b0) + "," + number(model.getSusceptanceMax()) + "," + number(model.getSusceptanceMin()) + ",1,1,2,"
                + number(model.getRegulatorGain()) + ",S1,", "1", ";", "1 " + number(model.getRegulatorTimeConstant())))));
        devices.add(function(controlled, controlled, List.of(susceptance), controlled0, "u[1]-(" + number(fixed) + ")"));
        return devices;
    }

    /** An electrical device: its header - part, name, its pins twice, its nodes - and its data line. */
    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",", data));
    }
}
