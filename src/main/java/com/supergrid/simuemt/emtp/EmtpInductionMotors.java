/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Load;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.motor.InductionMotorModel;
import com.powsybl.simuemt.extensions.motor.InductionMotorOperatingPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * An {@link InductionMotorModel} load as EMTP's asynchronous machine ({@code _ASM}): a single cage, its equivalent circuit per unit on
 * its rating - {@code Rs}, {@code Xls}, {@code Xm} on both axes, {@code Rr}, {@code Xlr} -, its stator an isolated star on the bus's
 * phases, as ATP's Type-56; started in EMTP's steady state from upstream's {@link InductionMotorOperatingPoint}: the slip at which it
 * draws the load's {@code p0} at its load-flow voltage, in percent.
 *
 * <p>One mass, its {@code H}; its speed observed ({@code Omega_1}, in rad/s - per unit, as a synchronous machine's, it is not) and
 * its torque controlled, per unit ({@code <name>_TLF} scoped), from the start: the driven load's {@code T0 (w / w0)^n}, {@code n} the load torque exponent, and its damping {@code D (w -
 * w0)} - upstream's mechanical equation (Kestrel's {@code KestrelInductionMachineWriter}), the damping on the speed's deviation from the
 * operating point's, not from the synchronous speed EMTP's own speed-deviation damping takes it from. The mass's torque ({@code Tm_1}),
 * positive braking the motor, not the machine's total external torque: controlled, EMTP takes that one a hundred times over and
 * reversed (2026-11-16: the rotor at 7.7e3 rad/s within the run), as it takes a synchronous machine's total mechanical power
 * ({@link EmtpSynchronousMachines}). Its electrical power ({@code Pe_<name>}) and its speed ({@code Speed_<name>}, rad/s) scoped.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpInductionMotors {

    /** The rotor speed EMTP observes on the machine's one mass, in mechanical rad/s: found by its {@code Omega_1}. */
    private static final String ROTOR_SPEED = "Omega_1";
    /** EMTPWorks' asynchronous machine's scope and observe requests, in its form's order; 1 asks for a scope. */
    private static final int OBSERVABLES = 34;
    private static final int SCOPE_PE = 29;
    private static final int SCOPE_SPEED = 31;
    private static final String SCOPED = "1";

    private EmtpInductionMotors() {
    }

    /** Whether {@code load} is an induction motor. */
    static boolean isMotor(Load load) {
        return load.getExtension(LoadModel.class) instanceof InductionMotorModel;
    }

    /** The motor's devices: the machine on {@code phaseNodes}, then the function giving its load torque from its speed. */
    static List<EmtpSynchronousMachines.Device> motor(Load load, String name, String[] phaseNodes, double frequencyHz) {
        InductionMotorModel model = (InductionMotorModel) load.getExtension(LoadModel.class);
        InductionMotorOperatingPoint point = InductionMotorOperatingPoint.of(load, model);
        double nominalKv = load.getTerminal().getVoltageLevel().getNominalV();
        String speed = name + "_" + ROTOR_SPEED;
        String torque = name + "_Tm_1";
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            char phase = "abc".charAt(p);
            // The stator on the bus phase, its star point; a cage rotor has no pins. Then the mass's torque, controlled, and its speed,
            // observed - each three-phase pin counted thrice in the total, the bundles not at all.
            lines.add("_ASM;" + name + phase + ";8;4;" + phaseNodes[p] + "," + name + "_N," + torque + "," + speed + ",");
            if (p == 0) {
                // After phase a's, its winding: an isolated star (1), a single cage (1); its two scopes.
                lines.add("1,1,?m,2,");
            }
        }
        lines.add("R=" + number(model.getRatedApparentPower()) + " MVA 1 " + number(nominalKv) + ",");
        // Its frequency, poles, isolated star, EMTPWorks' default reference frame.
        lines.add("f=" + number(frequencyHz) + " " + model.getNumberOfPoles() + " 1 2,");
        // Steady-state initialization at the operating point's slip, percent; the controlled torque from the start. (Handed over after
        // the first step, the steady state's held torque jolted the rotor by 0.07 rad/s in that step - some 35 pu, a total external
        // torque's hundredfold -, and a machine on the bus swung 4.9e-4 pu off its load flow; 2026-11-16.)
        lines.add("I=2," + number(100.0 * point.slip()) + ",0,");
        // No saturation: neither total nor independent, no characteristic on either axis.
        lines.add("S=0 0 0 0 0,");
        lines.add("Rs=" + number(model.getStatorResistance()) + ",1pu");
        lines.add("Ls=" + number(model.getStatorLeakageReactance()) + ",1pu");
        lines.add("Lmd=" + number(model.getMagnetizingReactance()) + ",1pu");
        lines.add("Lmq=" + number(model.getMagnetizingReactance()) + ",1pu");
        lines.add("RT=1,0");
        lines.add("Lsat=0,1,1pu");
        lines.add("Rr1=" + number(model.getRotorResistance()) + ",1pu");
        lines.add("Lr1=" + number(model.getRotorLeakageReactance()) + ",1pu");
        lines.add("Rr2=0,1pu");
        lines.add("Lr2=0,1pu");
        // Not locked at negative speed, one mass, the rotor's: all the external torque, H, no damping of its own; its speed observed (1),
        // its torque controlled (4).
        lines.add("MS=0 1 1,");
        lines.add("100 " + number(momentOfInertia(model, frequencyHz)) + " 0 0 0 0");
        lines.add("0 1 0 0 4");
        // The moment of inertia, not H; the scopes in SI, the mechanical observed and controlled signals per unit.
        lines.add("MSu=0 0 1,");
        // EMTPWorks' defaults: damping factor, rotor speed and voltage tolerances, iterations, iterating.
        lines.add("PR=100 1e-06 1e-03 30 1,");
        // Its controls per unit; neither its total torque nor its speed controlled.
        lines.add("CTRLu=1,");
        lines.add("CTRL=0 0,");
        // The scopes in SI, the observed signals per unit.
        lines.add("OBSu=0 1,");
        List<String> requests = new ArrayList<>(Collections.nCopies(OBSERVABLES, "0"));
        requests.set(SCOPE_PE, SCOPED);
        requests.set(SCOPE_SPEED, SCOPED);
        lines.add("OBS=" + String.join(" ", requests) + ",");
        // Omega_1 in mechanical rad/s, whatever its pu flag (2026-11-16: 153.4 at 2.34% slip); the torque per unit.
        double synchronous = 2.0 * Math.PI * frequencyHz * 2.0 / model.getNumberOfPoles();
        double w0 = synchronous * (1.0 - point.slip());
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        devices.add(new EmtpSynchronousMachines.Device("_ASM", lines));
        devices.add(EmtpVoltageSourceConverters.scoped(function(name + "_TLF", torque, List.of(speed), point.loadTorque(),
                loadTorque(point.loadTorque(), w0, model.getLoadTorqueExponent()) + "+" + number(model.getDamping() / synchronous) + "*(u[1]-" + number(w0) + ")")));
        return devices;
    }

    /** {@code J = 2 H S / wm^2}, kg m^2: the rotor's inertia upstream's backends give it, {@code wm} the synchronous mechanical speed. */
    private static double momentOfInertia(InductionMotorModel model, double frequencyHz) {
        double synchronous = 2.0 * Math.PI * frequencyHz * 2.0 / model.getNumberOfPoles();
        return 2.0 * model.getInertiaConstant() * model.getRatedApparentPower() * 1.0e6 / (synchronous * synchronous);
    }

    /** {@code T0 (max(w, 0) / w0)^n} of the speed {@code u[1]}: an integer exponent as products, as EMTP's functions surely read them. */
    private static String loadTorque(double t0, double w0, double exponent) {
        if (exponent == 0.0) {
            return number(t0);
        }
        String ratio = "(0.5*(u[1]+ABS(u[1]))/" + number(w0) + ")";
        if (exponent == Math.rint(exponent) && exponent > 0.0 && exponent <= 4.0) {
            return number(t0) + ("*" + ratio).repeat((int) exponent);
        }
        return number(t0) + "*" + ratio + "^" + number(exponent);
    }
}
