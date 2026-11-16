/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Load;
import com.powsybl.simuemt.extensions.LoadModel;
import com.powsybl.simuemt.extensions.circuit.LoadBranches;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.load.ConstantPowerLoadModel;

import java.util.ArrayList;
import java.util.List;

import static com.supergrid.simuemt.emtp.EmtpNetlistWriter.number;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.firstOrder;
import static com.supergrid.simuemt.emtp.EmtpSynchronousMachines.function;

/**
 * A constant-power load ({@link ConstantPowerLoadModel}) in EMTP, branch by branch ({@link LoadBranches}), as ATP's TACS has it: its
 * active power drawn by a conductance {@code P / (Vr^2 max(F, Vmin^2))}, {@code F} the branch's squared rms voltage per unit, {@code (u(t)^2
 * + u(t - T/4)^2) / 2} through a lag of one period ({@link LoadBranches#measurementTimeConstantSeconds}), floored ({@link
 * LoadBranches#flooredSquaredVoltagePerUnit}); its reactive power a fixed reactance in parallel, {@code Vref^2 / Q}.
 *
 * <p>EMTP has no resistance a control sets: the conductance where the branch starts, {@code G0}, is a resistance, which EMTP's steady
 * state takes, and a controlled current source draws the rest, {@code (G - G0) u} - nothing at rest. The quarter-period delay's output is
 * read from {@code T/4} on only, {@code F} held at its load-flow value until then.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EmtpConstantPowerLoads {

    private EmtpConstantPowerLoads() {
    }

    /** Whether {@code load} is a constant-power one. */
    static boolean isConstantPower(Load load) {
        return load.getExtension(LoadModel.class) instanceof ConstantPowerLoadModel;
    }

    /** Why {@code load}, a constant-power one, cannot be written: {@link LoadBranches#unsupportedReasons}. */
    static List<String> unsupportedReasons(Load load) {
        return LoadBranches.unsupportedReasons(load);
    }

    /** Its devices, {@code name} its id, {@code busId} its bus's. */
    static List<EmtpSynchronousMachines.Device> load(Load load, String name, String busId, EmtpSimulationSettings settings) {
        ConstantPowerLoadModel model = (ConstantPowerLoadModel) load.getExtension(LoadModel.class);
        double vmin2 = model.getMinVoltagePu() * model.getMinVoltagePu();
        double f0 = LoadBranches.initialSquaredVoltagePerUnit(load);
        double omega = 2 * Math.PI * settings.nominalFrequencyHz();
        double quarter = LoadBranches.quadratureDelaySeconds(settings.nominalFrequencyHz());
        int steps = (int) Math.round(quarter / settings.timeStepSeconds());
        String readFrom = EmtpVoltageSourceConverters.step("t-(" + number(steps * settings.timeStepSeconds()) + ")");
        List<EmtpSynchronousMachines.Device> devices = new ArrayList<>();
        for (LoadBranches.Branch branch : LoadBranches.of(load)) {
            String phase = String.valueOf(Character.toLowerCase(branch.from()));
            String prefix = name + "_CP" + phase;
            String node = EmtpNetlistWriter.node(busId, branch.from());
            String other = branch.isEarthed() ? "" : EmtpNetlistWriter.node(busId, branch.to());
            List<String> pins = branch.isEarthed() ? List.of(node) : List.of(node, other);
            double vr2 = branch.ratedVoltageVolts() * branch.ratedVoltageVolts();
            double ratedConductance = branch.pMw() * 1.0e6 / vr2;
            double g0 = ratedConductance / Math.max(LoadBranches.flooredSquaredVoltagePerUnit(f0), vmin2);
            devices.add(element("_RLC", prefix + "R", pins, number(1.0 / g0) + ",0,0,0,0,"));
            double vref2 = branch.referenceVoltageVolts() * branch.referenceVoltageVolts();
            if (branch.qMvar() > 0.0) {
                devices.add(element("_RLC", prefix + "L", pins, "0," + number(vref2 / (branch.qMvar() * 1.0e6) / omega) + ",0,0,0,"));
            } else if (branch.qMvar() < 0.0) {
                double capacitance = -branch.qMvar() * 1.0e6 / (omega * vref2);
                devices.add(element("_RLC", prefix + "C", pins, number(NumericalAids.shuntCapacitanceDampingOhm(capacitance, settings.timeStepSeconds())) + ",0,"
                        + number(capacitance) + ",0,0,"));
            }
            // The rest of its conductance as a current drawn from the branch, its voltage read on the source's own pins.
            String voltage = prefix + "V";
            String current = prefix + "I";
            devices.add(element("_cI", prefix + "S", List.of(node, other, current, voltage, prefix + "SI", prefix + "SP"), "-1,1E15,0,0,0,0,>v,0,0,"));
            String delayed = prefix + "VD";
            devices.add(new EmtpSynchronousMachines.Device("_c_dlydis", List.of("_c_dlydis;" + delayed + ";2;2;" + delayed + "," + voltage + ",", "0," + steps
                    + ",S1,")));
            String squared = prefix + "F";
            devices.add(function(squared, squared, List.of(voltage, delayed), f0, readFrom + "*(u[1]*u[1]+u[2]*u[2])/" + number(2.0 * vr2) + "+(1-" + readFrom
                    + ")*" + number(f0)));
            String measured = prefix + "FM";
            devices.add(firstOrder(measured, measured, squared, f0, 1.0, 1.0, 0.0, LoadBranches.measurementTimeConstantSeconds(settings.nominalFrequencyHz())));
            // P / (Vr^2 max(sqrt(F^2 + eps^2), Vmin^2)) - G0, times the voltage: drawn, out of the network.
            String floored = "SQRT(u[1]*u[1]+" + number(LoadBranches.SQUARED_VOLTAGE_FLOOR_PU * LoadBranches.SQUARED_VOLTAGE_FLOOR_PU) + ")";
            String bypassed = "0.5*(" + floored + "+" + number(vmin2) + "+ABS(" + floored + "-" + number(vmin2) + "))";
            devices.add(function(current, current, List.of(measured, voltage), 0.0, "-(" + number(ratedConductance) + "/(" + bypassed + ")-" + number(g0)
                    + ")*u[2]"));
        }
        return devices;
    }

    private static EmtpSynchronousMachines.Device element(String part, String name, List<String> nodes, String data) {
        return new EmtpSynchronousMachines.Device(part, List.of(part + ";" + name + ";" + nodes.size() + ";" + nodes.size() + ";" + String.join(",", nodes) + ",",
                data));
    }
}
