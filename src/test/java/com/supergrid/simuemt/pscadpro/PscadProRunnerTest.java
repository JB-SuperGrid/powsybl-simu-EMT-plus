/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What {@link PscadProRunner} does without PSCAD: the driver it loads a {@code .pscx} with, and the report it reads back.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProRunnerTest {

    /** The session script ships with the runner, and Python compiles it. */
    @Test
    void theSessionScriptShipsWithTheRunnerAndPythonCompilesIt(@TempDir Path tempDir) throws Exception {
        Path script = PscadProSession.script(tempDir);
        assertThat(Files.readString(script, StandardCharsets.UTF_8)).contains("pscad.load(path)", "project.build()", "project.run()",
                "project.unload()", PscadProSession.DONE);

        assumeTrue(pythonAvailable(), "no Python on this machine");
        Process process = new ProcessBuilder("py", "-I", "-m", "py_compile", script.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("py_compile %s: %s", script, output).isZero();
    }

    /** The driver's report: its status, its build folder, its errors one per line. */
    @Test
    void aReportIsReadLineByLine(@TempDir Path tempDir) throws IOException {
        Path report = tempDir.resolve("two_bus.result");
        Files.writeString(report, "status=build_failed\nbuild_folder=C:\\work\\two_bus.if18\\\nerror=Input port 'I' is floating.\n"
                + "error=LINK : fatal error LNK1104\n", StandardCharsets.UTF_8);

        PscadProRunner.Result result = PscadProRunner.read(report);

        assertThat(result.status()).isEqualTo("build_failed");
        assertThat(result.ran()).isFalse();
        assertThat(result.buildFolder()).hasFileName("two_bus.if18");
        assertThat(result.errors()).containsExactly("Input port 'I' is floating.", "LINK : fatal error LNK1104");
    }

    /** A project stuck past its timeout has its PSCAD killed, and is run once more in a new one: the second go's result is the run's. */
    @Test
    void aStuckRunIsKilledAndRunOnceMore(@TempDir Path tempDir) throws IOException {
        assumeTrue(pythonAvailable(), "no Python on this machine");
        Path project = Files.createFile(tempDir.resolve("two_bus.pscx"));
        PscadProSettings settings = fakePscad(tempDir, true, false);

        PscadProRunner.Result result = PscadProRunner.run(project, settings);

        assertThat(result.ran()).isTrue();
        assertThat(Files.readAllLines(tempDir.resolve("sessions"))).hasSize(2);
    }

    /** A project stuck at every go is reported once it has had its {@link PscadProRunner#ATTEMPTS}, with the session's console. */
    @Test
    void aRunStuckEveryTimeIsReported(@TempDir Path tempDir) throws IOException {
        assumeTrue(pythonAvailable(), "no Python on this machine");
        Path project = Files.createFile(tempDir.resolve("two_bus.pscx"));
        PscadProSettings settings = fakePscad(tempDir, true, true);

        assertThatThrownBy(() -> PscadProRunner.run(project, settings)).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("two_bus took longer than PT5S 2 times").hasMessageContaining("stuck on two_bus");
        assertThat(Files.readAllLines(tempDir.resolve("sessions"))).hasSize(PscadProRunner.ATTEMPTS);
    }

    /** One PSCAD runs project after project, and is started again after {@link PscadProSession#PROJECTS_PER_SESSION}. */
    @Test
    void oneSessionRunsProjectAfterProject(@TempDir Path tempDir) throws IOException {
        assumeTrue(pythonAvailable(), "no Python on this machine");
        PscadProSettings settings = fakePscad(tempDir, false, false);

        for (int i = 0; i <= PscadProSession.PROJECTS_PER_SESSION; i++) {
            Path project = Files.createFile(Files.createDirectory(tempDir.resolve("case" + i)).resolve("two_bus.pscx"));
            assertThat(PscadProRunner.build(project, settings).status()).isEqualTo("built");
        }

        assertThat(Files.readAllLines(tempDir.resolve("sessions"))).hasSize(2);
    }

    /**
     * A Python script in PSCAD's place, speaking {@link PscadProSession#SCRIPT}'s language: each session it starts is a line of
     * {@code sessions}; it is stuck on a project in its first session if {@code stuckFirst}, in every one if {@code alwaysStuck}.
     */
    private static PscadProSettings fakePscad(Path tempDir, boolean stuckFirst, boolean alwaysStuck) throws IOException {
        Path script = tempDir.resolve("fake_pscad.py");
        Files.writeString(script, """
                import os, sys, time
                sessions = '%s/sessions'
                with open(sessions, 'a') as f:
                    f.write('session\\n')
                with open(sessions) as f:
                    count = len(f.readlines())
                for line in sys.stdin:
                    command, _, path = line.rstrip('\\r\\n').partition('\\t')
                    if command not in ('build', 'run'):
                        break
                    name = os.path.splitext(os.path.basename(path))[0]
                    if %s or (%s and count == 1):
                        print('stuck on ' + name, file=sys.stderr, flush=True)
                        time.sleep(120)
                    directory = os.path.dirname(path)
                    open(os.path.join(directory, name + '.infx'), 'w').close()
                    with open(os.path.join(directory, name + '.result'), 'w') as f:
                        f.write('status=' + ('ran' if command == 'run' else 'built') + '\\nbuild_folder=' + directory + '\\n')
                    print('%s', flush=True)
                """.formatted(tempDir.toString().replace('\\', '/'), alwaysStuck ? "True" : "False", stuckFirst ? "True" : "False",
                PscadProSession.DONE), StandardCharsets.UTF_8);
        return PscadProSettings.defaults().withPythonCommand(List.of("py", "-I", script.toString())).withTimeout(Duration.ofSeconds(5));
    }

    private static boolean pythonAvailable() {
        try {
            Process process = new ProcessBuilder("py", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
