/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What {@link PscadProRunner} does without PSCAD: the driver it loads a {@code .pscx} with, and the report it reads back.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProRunnerTest {

    /** The driver ships with the runner, and Python compiles it. */
    @Test
    void theDriverShipsWithTheRunnerAndPythonCompilesIt(@TempDir Path tempDir) throws Exception {
        Path driver = tempDir.resolve(PscadProRunner.DRIVER);
        try (InputStream in = PscadProRunner.class.getResourceAsStream(PscadProRunner.DRIVER)) {
            assertThat(in).as("%s on the classpath", PscadProRunner.DRIVER).isNotNull();
            Files.copy(in, driver);
        }
        assertThat(Files.readString(driver, StandardCharsets.UTF_8)).contains("pscad.load(path)", "project.build()", "project.run()");

        assumeTrue(pythonAvailable(), "no Python on this machine");
        Process process = new ProcessBuilder("py", "-I", "-m", "py_compile", driver.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("py_compile %s: %s", driver, output).isZero();
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
