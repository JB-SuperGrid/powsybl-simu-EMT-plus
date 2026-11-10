/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.PowerFrequencyDroop;
import com.powsybl.simuemt.extensions.hvdc.control.VirtualSynchronousMachine;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleCatalogue;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.within;

/**
 * Every example of upstream's catalogue ({@code ExampleCatalogue}, its {@code bd32eb0d}) that only PSCAD Professional of PSCAD's two
 * editions covers - past PSCAD Free's fifteen electrical nodes, every model supported, which upstream checks and no more, nothing
 * generated there for Professional - written for PSCAD Professional, built and run live from rest, and held to its load flow at the
 * end: every AC bus's magnitude, every converter's active and reactive power and DC voltage. Driven by the catalogue: an example upstream
 * tags Professional's alone is run here with nothing to add.
 *
 * <p>From rest over 3 s - machines and converters let go at 2 s, as a template's are -, the last 40 ms held: each AC bus within 2e-3
 * pu, each converter's powers within 5e-3 pu of its rating - a V_DC converter carries its DC grid's other converters' phase reactor
 * losses besides, some 0.3% of its rating ({@link PscadProSimulationTest}) -, its DC voltage within 1e-3 pu. A grid-forming converter's
 * power off the load flow's along its swing law at the speed its virtual machine ends on, the network's frequency a little off 50 Hz,
 * a lone V_DC converter taking the rest, as upstream's {@code XyceSolverIntegrationTest} holds them - over 7 s rather than 3. The scenarios' physics are
 * held tighter elsewhere: {@link PscadProBeyondFreeTest}, {@link PscadProConvertersTest}, {@link PscadProCigreDcs2Test}, {@link
 * PscadProSimulationTest}.
 *
 * <p>Run 2026-11-10, the twenty in 12 minutes: the AC benches' buses within 3.3e-5 pu; example 4's and its variants' within 1.7e-4, the
 * converters within 2e-3 pu and 1.7e-4 pu DC; the VSC monopole's within 8.8e-4, VSC2 grid-forming 0.026 pu off the load flow's at 7 s
 * on its swing law, the converters within 4.1e-3 of it; CIGRE DCS2 within 4e-5, 1.6e-3 and 1.9e-4.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProExampleCoverageTest {

    private static final double RUN_END = 3.0;
    /** A grid-forming converter's virtual machine swings slower than the rest settles: 4 s more. */
    private static final double GRID_FORMING_RUN_END = 7.0;
    private static final double BUS_TOLERANCE = 2.0e-3;
    private static final double POWER_TOLERANCE = 5.0e-3;
    private static final double DC_VOLTAGE_TOLERANCE = 1.0e-3;

    /** Upstream's catalogue's examples of PSCAD Professional's alone - {@code -Dpscad.examples=vsc-monopole-3,...} runs only those. */
    static List<ExampleCatalogue.Example> professionalOnly() {
        String only = System.getProperty("pscad.examples", "");
        List<String> names = only.isBlank() ? List.of() : List.of(only.split(","));
        return ExampleCatalogue.examples().stream().filter(ExampleCatalogue.Example::professionalOnly)
                .filter(example -> names.isEmpty() || names.contains(example.name())).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("professionalOnly")
    void anExampleOfProfessionalsAloneRunsOnItsLoadFlow(ExampleCatalogue.Example example, @TempDir Path tempDir) {
        Network network = ready(example);
        boolean gridForming = network.getVoltageSourceConverterStream().anyMatch(c -> c.getExtension(VscControlModel.class) instanceof GridFormingControl);
        double runEnd = gridForming ? GRID_FORMING_RUN_END : RUN_END;
        PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, runEnd, 50.0, PscadEdition.PROFESSIONAL);
        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings, List.of(), PscadProSettings.defaults());
        PscadCircuit circuit = run.project().circuit();
        PscadOutput out = run.output();
        double from = runEnd - 0.04;
        SoftAssertions softly = new SoftAssertions();
        double worstBus = 0.0;
        for (Bus bus : network.getBusBreakerView().getBuses()) {
            if (Double.isNaN(bus.getV()) || !circuit.busNodes().containsKey(bus.getId())) {
                continue;
            }
            String channel = circuit.voltageChannel(bus.getId(), 'A');
            double nominal = bus.getVoltageLevel().getNominalV();
            double error = out.fundamentalPhasor(channel, 50.0, from, runEnd).magnitude() / (nominal * 1.0e3 * Math.sqrt(2.0 / 3.0)) - bus.getV() / nominal;
            worstBus = Math.max(worstBus, Math.abs(error));
            softly.assertThat(error).as("%s: |%s| at the end against the load flow's, pu", example, bus.getId()).isCloseTo(0.0, within(BUS_TOLERANCE));
        }
        // A grid-forming converter's power off the load flow's along its swing law, at the speed and acceleration its virtual machine
        // ends on; a lone V_DC converter balancing what they deliver beyond the load flow's (upstream's XyceSolverIntegrationTest).
        Map<String, Double> offsets = new HashMap<>();
        double swingTotal = 0.0;
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            if (converter.getExtension(VscControlModel.class) instanceof GridFormingControl control) {
                double offset = swingPowerMw(converter, control, out, from, runEnd);
                offsets.put(converter.getId(), offset);
                swingTotal += out.mean(converter.getId() + "_P", from, runEnd) * rating(converter) - converter.getTerminal1().getP();
            }
        }
        List<VoltageSourceConverter> dcVoltage = network.getVoltageSourceConverterStream()
                .filter(c -> c.getControlMode() == AcDcConverter.ControlMode.V_DC).toList();
        if (!offsets.isEmpty() && dcVoltage.size() == 1) {
            offsets.put(dcVoltage.get(0).getId(), -swingTotal);
        }
        double worstPower = 0.0;
        double worstDc = 0.0;
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            String id = converter.getId();
            double rating = rating(converter);
            double p = out.mean(id + "_P", from, runEnd) - (converter.getTerminal1().getP() + offsets.getOrDefault(id, 0.0)) / rating;
            double q = out.mean(id + "_Q", from, runEnd) - converter.getTerminal1().getQ() / rating;
            double vdc = out.mean(id + "_VDC", from, runEnd)
                    - converter.getDcTerminal1().getDcNode().getV() / converter.getDcTerminal1().getDcNode().getNominalV();
            worstPower = Math.max(worstPower, Math.max(Math.abs(p), Math.abs(q)));
            worstDc = Math.max(worstDc, Math.abs(vdc));
            softly.assertThat(p).as("%s: %s's power at the end against the load flow's, pu", example, id).isCloseTo(0.0, within(POWER_TOLERANCE));
            softly.assertThat(q).as("%s: %s's reactive power at the end against the load flow's, pu", example, id).isCloseTo(0.0, within(POWER_TOLERANCE));
            softly.assertThat(vdc).as("%s: %s's DC voltage at the end against the load flow's, pu", example, id).isCloseTo(0.0, within(DC_VOLTAGE_TOLERANCE));
        }
        System.out.printf(Locale.ROOT, "%s: %d nodes; buses within %.1e pu, converters' powers within %.1e pu, DC voltages within %.1e pu%n", example,
                circuit.nodes().size(), worstBus, worstPower, worstDc);
        softly.assertAll();
    }

    /** {@code converter}'s power off the load flow's along its swing law, MW: its synchronization's damping on its speed's deviation, twice its
     * inertia on its acceleration, its transient damping and its DC voltage droop - the speed and its derivative over the last 40 ms. */
    private static double swingPowerMw(VoltageSourceConverter converter, GridFormingControl control, PscadOutput out, double from, double to) {
        String speedChannel = converter.getId() + "_VSMW";
        double speed = out.mean(speedChannel, from, to);
        double acceleration = (out.mean(speedChannel, to - 0.01, to) - out.mean(speedChannel, from, from + 0.01)) / (to - from - 0.01);
        double[] law = switch (control.getSynchronization()) {
            case VirtualSynchronousMachine vsm -> new double[] {vsm.dampingCoefficient(), vsm.inertiaConstant()};
            case PowerFrequencyDroop droop -> new double[] {1.0 / droop.droop(), droop.filterTimeConstant() / (2.0 * droop.droop())};
        };
        double damping = control.getTransientDamping().map(d -> d.gain() * d.timeConstant() * acceleration).orElse(0.0);
        AveragedVscInitialState initial = AveragedVscInitialState.of(converter, (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class));
        double dcVoltage = out.mean(converter.getId() + "_VDC", from, to);
        double dcDroop = control.getDcVoltageDroop().map(d -> -d.gain() * (dcVoltage - initial.dcVoltage() / initial.dcNominalVoltage())).orElse(0.0);
        return (law[0] * (speed - 1.0) + 2.0 * law[1] * acceleration + damping + dcDroop) * initial.baseApparentPower();
    }

    private static double rating(VoltageSourceConverter converter) {
        return ((AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class)).getRatedApparentPower();
    }

    /** The example on its load flow, as upstream's coverage test readies it: a bench of files, or a network with a DC side, through the
     * pipeline's preprocessing. */
    private static Network ready(ExampleCatalogue.Example example) {
        Network network = example.create(EmtExtensionsXmlReader::readNetwork);
        if (example.fromFiles() || network.getDcNodeCount() > 0) {
            EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
            return network;
        }
        return ExampleNetworks.withLoadFlow(network);
    }
}
