/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.simuemt.extensions.machine.ClassicalDefinitions.RotorCircuit;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;

/**
 * A loaded machine shorted at the far end of its line, from Park's equations integrated here: the circuit the machine's data describe
 * under the exact definitions ({@link RotorCircuit#fromExact}), its speed and its field voltage held, per unit on its own base,
 * generator convention, the q axis 90 degrees ahead of the d axis.
 *
 * <p>The line, a series {@code Re + jXe} rotating with the stator's frame, is in the dq frame a leakage like the stator's own: the short
 * at its far end puts it in series with the stator, so the circuit integrated is the machine's with {@code Ra + Re} and {@code Xl + Xe}
 * and nothing at its terminal, and the machine's terminal voltage is the line's drop, {@code Re i + (Xe/w0) di/dt + jXe i}. The states
 * are the six windings' flux linkages, integrated by the classical fourth-order Runge-Kutta method.
 *
 * <p>Before the short the machine is in the steady state of its terminal voltage and current: its q axis along {@code V + (Ra + jXq)
 * I}, no damper current, its field current {@code (psid + Xd id)/Xad} and its field voltage the one that drives it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class ParkShortCircuitReference {

    private final double omega;
    private final double re;
    private final double xe;
    private final double ra;
    private final RotorCircuit d;
    private final RotorCircuit q;
    /** Each axis's currents from its flux linkages - stator, then its two rotor windings. */
    private final double[][] dInverse;
    private final double[][] qInverse;
    private final double fieldVoltage;
    private final double[] preFaultTerminal;
    private double[] state;

    /**
     * The machine {@code machine}, its line {@code re + j xe} per unit, at {@code frequencyHz}, before the short at terminal voltage
     * {@code v} and current {@code i} - generator convention -, each a complex number in any common reference.
     */
    ParkShortCircuitReference(SubtransientMachineModel machine, double re, double xe, double frequencyHz, double[] v, double[] i) {
        this.omega = 2.0 * Math.PI * frequencyHz;
        this.re = re;
        this.xe = xe;
        this.ra = machine.getStatorResistance();
        double xl = machine.getStatorLeakageReactance();
        this.d = RotorCircuit.fromExact(machine.getDirectSynchronousReactance(), xl, machine.getDirectTransientReactance(),
                machine.getDirectSubtransientReactance(), machine.getOpenCircuitTransientTimeConstant(),
                machine.getDirectOpenCircuitSubtransientTimeConstant(), "d");
        this.q = RotorCircuit.fromExact(machine.getQuadratureSynchronousReactance(), xl, machine.getQuadratureTransientReactance(),
                machine.getQuadratureSubtransientReactance(), machine.getQuadratureOpenCircuitTransientTimeConstant(),
                machine.getQuadratureOpenCircuitSubtransientTimeConstant(), "q");
        this.dInverse = invert(inductances(d, xl + xe));
        this.qInverse = invert(inductances(q, xl + xe));

        // The rotor's frame: the q axis along V + (Ra + jXq) I, made the imaginary axis.
        double eqRe = v[0] + ra * i[0] - machine.getQuadratureSynchronousReactance() * i[1];
        double eqIm = v[1] + ra * i[1] + machine.getQuadratureSynchronousReactance() * i[0];
        double turn = Math.PI / 2.0 - Math.atan2(eqIm, eqRe);
        double[] vdq = rotate(v, turn);
        double[] idq = rotate(i, turn);
        this.preFaultTerminal = vdq;
        double id = idq[0];
        double iq = idq[1];
        double psiq = -vdq[0] - ra * id;
        double psid = vdq[1] + ra * iq;
        double xad = d.magnetizingReactance();
        double xaq = q.magnetizingReactance();
        double fieldCurrent = (psid + (xl + xad) * id) / xad;
        this.fieldVoltage = d.fieldResistance() / omega * fieldCurrent;
        // The stator's flux linkages with the line's leakage added, the currents unchanged through the short.
        this.state = new double[] {psid - xe * id, -xad * id + (xad + d.fieldReactance()) * fieldCurrent, -xad * id + xad * fieldCurrent,
            psiq - xe * iq, -xaq * iq, -xaq * iq};
    }

    /** The terminal voltage before the short in the rotor's frame, {@code {vd, vq}}. */
    double[] preFaultTerminalVoltage() {
        return preFaultTerminal.clone();
    }

    /**
     * The terminal voltage in the rotor's frame, {@code {vd, vq}}, at every {@code sampleStep} through {@code duration} seconds after
     * the short, integrated at {@code step}.
     */
    double[][] terminalVoltages(double duration, double sampleStep, double step) {
        int perSample = (int) Math.round(sampleStep / step);
        int samples = (int) Math.round(duration / sampleStep);
        double[][] voltages = new double[samples][];
        for (int n = 0; n < samples; n++) {
            for (int k = 0; k < perSample; k++) {
                advance(step);
            }
            voltages[n] = terminal(state);
        }
        return voltages;
    }

    private void advance(double h) {
        double[] k1 = derivative(state);
        double[] k2 = derivative(add(state, k1, h / 2));
        double[] k3 = derivative(add(state, k2, h / 2));
        double[] k4 = derivative(add(state, k3, h));
        for (int j = 0; j < state.length; j++) {
            state[j] += h / 6 * (k1[j] + 2 * k2[j] + 2 * k3[j] + k4[j]);
        }
    }

    /** {@code d psi / dt}, per second: the stator shorted behind {@code Ra + Re}, at synchronous speed; the field driven, the dampers
     * free. */
    private double[] derivative(double[] psi) {
        double[] di = multiply(dInverse, psi[0], psi[1], psi[2]);
        double[] qi = multiply(qInverse, psi[3], psi[4], psi[5]);
        return new double[] {
            omega * ((ra + re) * di[0] + psi[3]),
            omega * fieldVoltage - d.fieldResistance() * di[1],
            -d.damperResistance() * di[2],
            omega * ((ra + re) * qi[0] - psi[0]),
            -q.fieldResistance() * qi[1],
            -q.damperResistance() * qi[2]};
    }

    /** The line's drop, {@code Re i + (Xe/w0) di/dt + jXe i}. */
    private double[] terminal(double[] psi) {
        double[] rate = derivative(psi);
        double[] di = multiply(dInverse, psi[0], psi[1], psi[2]);
        double[] qi = multiply(qInverse, psi[3], psi[4], psi[5]);
        double idRate = multiply(dInverse, rate[0], rate[1], rate[2])[0];
        double iqRate = multiply(qInverse, rate[3], rate[4], rate[5])[0];
        return new double[] {re * di[0] + xe / omega * idRate - xe * qi[0], re * qi[0] + xe / omega * iqRate + xe * di[0]};
    }

    /** An axis's flux linkages from its currents: the stator's (generator convention, behind {@code statorLeakage}), then its rotor's. */
    private static double[][] inductances(RotorCircuit rotor, double statorLeakage) {
        double xa = rotor.magnetizingReactance();
        return new double[][] {
            {-(statorLeakage + xa), xa, xa},
            {-xa, xa + rotor.fieldReactance(), xa},
            {-xa, xa, xa + rotor.damperReactance()}};
    }

    private static double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] inverse = new double[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                // The cofactor of (c, r) over the determinant.
                int r1 = (c + 1) % 3;
                int r2 = (c + 2) % 3;
                int c1 = (r + 1) % 3;
                int c2 = (r + 2) % 3;
                inverse[r][c] = (m[r1][c1] * m[r2][c2] - m[r1][c2] * m[r2][c1]) / det;
            }
        }
        return inverse;
    }

    private static double[] multiply(double[][] m, double a, double b, double c) {
        return new double[] {m[0][0] * a + m[0][1] * b + m[0][2] * c, m[1][0] * a + m[1][1] * b + m[1][2] * c,
            m[2][0] * a + m[2][1] * b + m[2][2] * c};
    }

    private static double[] add(double[] x, double[] rate, double h) {
        double[] y = new double[x.length];
        for (int j = 0; j < x.length; j++) {
            y[j] = x[j] + h * rate[j];
        }
        return y;
    }

    private static double[] rotate(double[] z, double angle) {
        return new double[] {z[0] * Math.cos(angle) - z[1] * Math.sin(angle), z[0] * Math.sin(angle) + z[1] * Math.cos(angle)};
    }
}
