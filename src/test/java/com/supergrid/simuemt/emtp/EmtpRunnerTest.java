/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EMTP's solver run on a netlist: its command line, and - tagged {@code emtp}, run with {@code mvn test -Pemtp} on a machine with EMTP
 * 4.6.0 licensed - {@link EmtpOutputTest}'s netlist run live, from a folder whose name has a space in it, to the same outputs.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpRunnerTest {

    @Test
    void givesTheSolverEmtpWorksFieldsSeparatedBySemicolons() {
        EmtpSettings settings = new EmtpSettings(Path.of("C:\\EMTP\\emtpopt.exe"), Path.of("C:\\state\\emtpstate.ini"), Duration.ofMinutes(1));

        assertThat(EmtpRunner.command(Path.of("C:\\cases\\two bus.net"), settings))
                .containsExactly("C:\\EMTP\\emtpopt.exe", "C:\\state\\emtpstate.ini;C:\\state\\emtpstate.ini;C:\\cases\\two bus.net;0;1;;");
    }

    /** EMTP's two ways of saying something went wrong: about its input, and about a network it cannot solve - which it still runs to
     * its end, as it did on an infinite bus written part after part, its log ending in "EMTP terminated" all the same. */
    @Test
    void readsEitherOfEmtpsErrorsAsAnError() {
        assertThat(EmtpRunner.hasError(List.of("->START harmonic steady-state solution", "Error, Problem near signal: EGENa", "EMTP terminated"))).isTrue();
        assertThat(EmtpRunner.hasError(List.of("!!Error: Problem opening file: C:\\cases\\x_pj\\x.out"))).isTrue();
        assertThat(EmtpRunner.hasError(List.of("->END harmonic steady-state solution", "Errors: none", "EMTP terminated"))).isFalse();
    }

    @Test
    void refusesAFileThatIsNoNetlist() {
        assertThatThrownBy(() -> EmtpRunner.name(Path.of("case.ecf"))).isInstanceOf(PowsyblException.class).hasMessageContaining("not case.ecf");
        assertThat(EmtpRunner.name(Path.of("two_bus.NET"))).isEqualTo("two_bus");
    }

    /** Run 2026-11-13: in about a second, every sample as stored, to the last digit. */
    @Test
    @Tag("emtp")
    void runsANetlistToTheOutputsEmtpWroteForIt(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path folder = Files.createDirectories(tempDir.resolve("with space"));
        Path netlist = Files.copy(EmtpOutputTest.project().resolveSibling("simple_short.net"), folder.resolve("simple_short.net"));

        EmtpRunner.Run run = EmtpRunner.run(netlist, EmtpSettings.defaults());

        assertThat(run.projectDirectory()).isEqualTo(folder.resolve("simple_short_pj"));
        assertThat(run.projectDirectory().resolve("simple_short.mda")).hasSameBinaryContentAs(EmtpOutputTest.project().resolve("simple_short.mda"));
        assertThat(run.output().columnNames()).containsExactly("TIME", "is:SWfault", "vb:C1", "ib:C1");
        assertThat(run.steadyState().voltages()).isEqualTo(EmtpSteadyState.read(EmtpOutputTest.project(), "simple_short").voltages());
    }

    /** A netlist EMTP cannot read: refused with its messages. */
    @Test
    @Tag("emtp")
    void refusesARunThatDidNotGoThrough(@TempDir Path tempDir) throws IOException {
        Path netlist = Files.writeString(tempDir.resolve("broken.net"), "Dt=10us;\ntmax=1ms;\n_NoSuchDevice;X1;1;1;a,\n1,\n");

        assertThatThrownBy(() -> EmtpRunner.run(netlist, EmtpSettings.defaults())).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("EMTP did not run").hasMessageContaining("EMTP's console");
    }
}
