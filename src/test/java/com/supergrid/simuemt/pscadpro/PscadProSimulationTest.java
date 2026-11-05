/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSteadyState;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The real PSCAD Professional, through its automation library: each network written as a script, built, compiled, run, and
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

        assertThat(run.project().file()).hasFileName("two_bus.py");
        assertThat(tempDir.resolve("two_bus.pscx")).exists();
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
     * its first period, as ATP, Xyce and Kestrel are. With a distributed line, which the script creates from its definition.
     */
    @Test
    void aRestartIsSteadyFromItsFirstPeriod(@TempDir Path tempDir) throws Exception {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
        ExampleNetworks.withLoadFlow(network);
        PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, 0.3, 50.0, PscadEdition.PROFESSIONAL);

        PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, tempDir, settings, List.of(), PRO);

        assertThat(Files.list(tempDir.resolve("template")).anyMatch(p -> p.getFileName().toString().endsWith(".py"))).isTrue();
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

    /** A Fortran component of the project's own - the WT4B wind turbine's controls - built and compiled by PSCAD. */
    @Test
    void aFortranComponentCompiles(@TempDir Path tempDir) {
        Network network = ExampleNetworks.withLoadFlow(ExampleNetworks.createWindTurbineNetwork());
        PscadSimulationSettings settings = new PscadSimulationSettings(50.0e-6, 0.2, 50.0, PscadEdition.PROFESSIONAL);
        Path script = com.powsybl.simuemt.pscad.PscadFileWriter.write(network, tempDir, settings, List.of(), PscadPythonScriptWriter.INSTANCE).file();

        PscadProRunner.Result result = PscadProRunner.build(script, PRO);

        assertThat(result.errors()).isEmpty();
        assertThat(result.buildFolder()).isDirectory();
    }
}
