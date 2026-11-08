/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.AcDcConverter;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.machine.SubtransientMachineModel;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSteadyState;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The real PSCAD Professional, through its automation library: each network written as its {@code .pscx}, loaded, built, compiled, run, and
 * what EMTDC wrote held to the circuit's own phasor steady state ({@link PscadSteadyState}), solved independently of PSCAD -
 * as {@code powsybl-simu-emt-pscad}'s tests hold the runs made by hand in PSCAD Free.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}, on a machine with PSCAD 5.1 Professional licensed, Intel
 * Fortran and the Windows SDK.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProSimulationTest {

    private static final PscadProSettings PRO = PscadProSettings.defaults();

    /** From rest, 0.5 s: the switching-on transient long gone, every bus on its steady state in the last period. */
    @Test
    void aRunFromRestSettlesOnTheSteadyState(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator());
        PscadSimulationSettings settings = new PscadSimulationSettings(50.0e-6, 0.5, 50.0, PscadEdition.PROFESSIONAL);

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings, List.of(), PRO);

        assertThat(run.project().file()).hasFileName("two_bus.pscx");
        assertThat(tempDir.resolve(PscadProRunner.DRIVER)).exists();
        PscadSteadyState steady = PscadSteadyState.solve(run.project().circuit(), settings);
        for (String bus : List.of("B1", "B2")) {
            for (char phase : "ABC".toCharArray()) {
                double crest = steady.voltage(bus, phase).magnitude();
                String channel = run.project().circuit().voltageChannel(bus, phase);
                assertThat(run.output().fundamentalPhasor(channel, 50.0, 0.48, 0.5).magnitude() / crest - 1.0)
                        .as("|%s%s| in the last period", bus, phase).isCloseTo(0.0, within(1.0e-4));
            }
        }
    }

    /**
     * From the steady state: the template run by PSCAD writes its snapshot, the restart starts from it patched - steady from
     * its first period, as ATP, Xyce and Kestrel are. With lumped lines only: a travelling-wave line splits the circuit into
     * subsystems, which the snapshot patching does not handle.
     */
    @Test
    void aRestartIsSteadyFromItsFirstPeriod(@TempDir Path tempDir) throws Exception {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        ExampleNetworks.withLoadFlow(network);
        PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, 0.3, 50.0, PscadEdition.PROFESSIONAL);

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings, List.of(), PRO);

        assertThat(Files.list(tempDir.resolve("template")).anyMatch(p -> p.getFileName().toString().endsWith(".pscx"))).isTrue();
        PscadSteadyState steady = PscadSteadyState.solve(run.project().circuit(), settings);
        for (String bus : List.of("B1", "B2")) {
            double crest = steady.voltage(bus, 'A').magnitude();
            String channel = run.project().circuit().voltageChannel(bus, 'A');
            assertThat(run.output().peakAbsolute(channel, 0.0, 0.02) / crest - 1.0).as("|%s| in the first period", bus)
                    .isCloseTo(0.0, within(1.0e-4));
            assertThat(run.output().fundamentalPhasor(channel, 50.0, 0.28, 0.3).magnitude() / crest - 1.0).as("|%s| in the last period", bus)
                    .isCloseTo(0.0, within(1.0e-4));
        }
    }

    /**
     * The multi-terminal DC networks: example 4's three stations, VSC2 and VSC3 on the DC voltage droop ({@link
     * PscadProCases#dcDroop}); CIGRE B4 DCS2, its DC lines meeting at BMB5, held up by its two onshore droops alone; and CIGRE B4
     * whole, its eleven stations on three DC grids, DCS3 meshed, between two meshed AC systems and four offshore islands; the IEEE
     * 14-bus network with its DC grid, its machines Dynawo's; Kundur's two areas with theirs, its machines the benchmark's, their
     * PSSs in service; the IEEE 39-bus network with its four stations, its ten machines Athay's, on PSSs too - the machines' speed back on the
     * synchronous one within 5e-4 pu. From
     * rest - their AC and DC sides separate subsystems, which a snapshot restart does not patch - over 4 s: the DC side charged
     * while the controls' integrals are held, until 2 s as a template's are, then let go. In the last 40 ms, each droop
     * converter's power where its curve meets the DC voltage it settles at, as Xyce's and Kestrel's runs hold it: the load
     * flow's, less what the curve takes off there; the other converters' the load flow's power, or DC voltage on V_DC; every
     * converter's reactive power and DC voltage the load flow's. A V_DC converter's power is not the load flow's: it takes, besides,
     * its DC grid's other converters' phase reactor losses, which the load flow has not (R 0.003 pu; CIGRE B4's CBA1 13.4 MW of
     * DCS3's, 13.5 MW by R I^2) - its reactive power, holding its AC bus, then their drop across its transformer, within 0.3% of its
     * rating. The other converters' reactive power within 0.3 Mvar, or 0.3% of their rating: holding their AC bus on a 5% droop,
     * 1.5e-4 pu of its voltage, which those losses move the AC flows by, is 0.3% of their rating.
     *
     * <p>Run 2026-11-06 at 50 us: the droops within 0.07 MW of their curves (0.001 on example 4), the powers held within 0.003
     * MW, the V_DC within 1e-6 pu; the reactive powers within 0.15 Mvar and the DC voltages within 2e-4 pu of the load flow's.
     * CIGRE B4 whole, 2026-11-07, 4 s in 54 s: its droops within 0.06 MW of their curves, its powers within 0.08 MW, its V_DC on
     * theirs; the reactive powers within 0.1 Mvar, CBA1's 2.5 Mvar off; the DC voltages within 1.1e-4 pu. IEEE 14 with its DC grid,
     * 2026-11-07, 4 s in 40 s: its droops on their curves within 0.01 MW, CSW within 0.003 MW, CS2 on its V_DC; the reactive powers
     * within 0.1 Mvar; its machines 9e-5 pu fast, the 3 MW its droops, still short of their load flow's power until let go, bring
     * then, which G1's and G2's proportional governors take back. Kundur's two areas with their DC grid, 2026-11-08, 4 s in 37 s:
     * CS7 on its curve within 1e-4 MW, CSW within 0.004 MW, CS9 on its V_DC, 1.75 MW short of the load flow's power; CS7's
     * reactive power 0.7 Mvar off, CS9's 1.0, CSW's within 0.002; the DC voltages within 1.1e-5 pu; its machines 4e-5 pu slow,
     * their TGOV1s' turbines (T3 10 s) still bringing those 1.75 MW back. IEEE 39 with its DC grid, 2026-11-08, 4 s in 59 s: its
     * droops on their curves within 3e-4 MW, CSW within 0.007 MW, CS16 on its V_DC, 2.3 MW short of the load flow's power; the
     * reactive powers within 0.18 Mvar; the DC voltages within 2e-5 pu; its ten machines, on their PSSs, 0.8e-5 to 1.2e-5 pu slow.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vsc-three-terminal-vdc-droop", "cigre-dcs2", "cigre-b4", "ieee14-dc", "kundur-dc", "ieee39-dc"})
    void aMultiTerminalDcNetworkHoldsItsOperatingPoint(String bench, @TempDir Path tempDir) throws Exception {
        Network network = ExampleNetworks.withLoadFlow("vsc-three-terminal-vdc-droop".equals(bench) ? PscadProCases.dcDroop()
                : PscadProCases.bench(bench));
        double runEnd = 4.0;
        PscadSimulationSettings settings = new PscadSimulationSettings(50.0e-6, runEnd, 50.0, PscadEdition.PROFESSIONAL);

        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings, List.of(), PRO);

        double from = runEnd - 0.04;
        SoftAssertions softly = new SoftAssertions();
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            String id = converter.getId();
            AveragedVscModel model = (AveragedVscModel) converter.getExtension(VoltageSourceConverterModel.class);
            AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
            double rating = model.getRatedApparentPower();
            double p = run.output().mean(id + "_P", from, runEnd) * rating;
            double q = run.output().mean(id + "_Q", from, runEnd) * rating;
            double vdc = run.output().mean(id + "_VDC", from, runEnd);
            double lfP = converter.getTerminal1().getP();
            double lfVdc = initial.dcVoltage() / initial.dcNominalVoltage();
            double expectedP = DcVoltageDroopLaw.of(converter, initial)
                    .map(law -> lfP - law.powerReduction(vdc) * initial.baseApparentPower()).orElse(lfP);
            System.out.printf("%s %s (%s): P %.4f MW (expected %.4f, load flow %.4f), Q %.4f Mvar (%.4f), Vdc %.6f pu (%.6f)%n", bench, id,
                    converter.getControlMode(), p, expectedP, lfP, q, converter.getTerminal1().getQ(), vdc, lfVdc);
            if (converter.getControlMode() == AcDcConverter.ControlMode.V_DC) {
                softly.assertThat(vdc).as("%s DC voltage, pu", id).isCloseTo(lfVdc, within(1.0e-4));
            } else {
                softly.assertThat(p).as("%s active power, MW", id).isCloseTo(expectedP, within(0.1));
                softly.assertThat(vdc).as("%s DC voltage, pu", id).isCloseTo(lfVdc, within(5.0e-4));
            }
            double qTolerance = Math.max(0.3, 0.003 * rating);
            softly.assertThat(q).as("%s reactive power, Mvar", id).isCloseTo(converter.getTerminal1().getQ(), within(qTolerance));
        }
        // Its machines, if any: back on the synchronous speed.
        network.getGeneratorStream().filter(g -> g.getExtension(GeneratorModel.class) instanceof SubtransientMachineModel).forEach(g -> {
            double speed = run.output().mean(g.getId() + "_W", from, runEnd);
            System.out.printf("%s %s: speed %.6f pu%n", bench, g.getId(), speed);
            softly.assertThat(speed).as("%s speed, pu", g.getId()).isCloseTo(1.0, within(5.0e-4));
        });
        softly.assertAll();
    }

    /** A Fortran component of the project's own - the WT4B wind turbine's controls - loaded, built and compiled by PSCAD. */
    @Test
    void aFortranComponentCompiles(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createWindTurbineNetwork());
        PscadSimulationSettings settings = new PscadSimulationSettings(50.0e-6, 0.2, 50.0, PscadEdition.PROFESSIONAL);
        Path pscx = PscadFileWriter.write(network, tempDir, settings, List.of(), PscadProjectSerializer.PSCX).file();

        PscadProRunner.Result result = PscadProRunner.build(pscx, PRO);

        assertThat(result.errors()).isEmpty();
        assertThat(result.buildFolder()).isDirectory();
    }
}
