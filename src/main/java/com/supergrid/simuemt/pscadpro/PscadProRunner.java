/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runs a project script {@link PscadPythonScriptWriter} wrote, and reads what it reports: PSCAD launched, the project built,
 * compiled and run, EMTDC's output in its build folder. Its console goes to {@code <name>.log} beside it.
 *
 * <p>A failure is reported, by name, with PSCAD's errors: a missing licence, a compiler that will not link (PSCAD's Intel
 * Fortran needs the Windows SDK's libraries), a run that wrote nothing.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class PscadProRunner {

    /** What a script reported.
     *
     * @param status      {@code ran}, {@code built}, {@code build_failed} or {@code failed}
     * @param buildFolder where EMTDC wrote its output, {@code null} if the project was never built
     * @param errors      PSCAD's errors, or the script's own
     */
    public record Result(String status, Path buildFolder, List<String> errors) {

        public Result {
            Objects.requireNonNull(status);
            errors = List.copyOf(errors);
        }

        public boolean ran() {
            return "ran".equals(status);
        }
    }

    private PscadProRunner() {
    }

    /** Builds and runs the project of {@code script}; refused, with PSCAD's errors, unless it ran and wrote its output. */
    public static Result run(Path script, PscadProSettings settings) {
        Result result = execute(script, settings, false);
        if (!result.ran()) {
            throw failure(script, result);
        }
        String name = projectName(script);
        if (!Files.exists(result.buildFolder().resolve(name + ".infx"))) {
            throw new PowsyblException("SimuEmt Plus: PSCAD ran project " + name + " but wrote no output in " + result.buildFolder()
                    + logTail(script));
        }
        return result;
    }

    /** Builds the project of {@code script} without running it - what a check of the circuit PSCAD compiles takes. */
    public static Result build(Path script, PscadProSettings settings) {
        Result result = execute(script, settings, true);
        if (!"built".equals(result.status())) {
            throw failure(script, result);
        }
        return result;
    }

    private static Result execute(Path script, PscadProSettings settings, boolean buildOnly) {
        Path directory = script.toAbsolutePath().getParent();
        String name = projectName(script);
        List<String> command = new ArrayList<>(settings.pythonCommand());
        command.add(script.toAbsolutePath().toString());
        command.add("--version");
        command.add(settings.pscadVersion());
        if (settings.fortranCompiler() != null) {
            command.add("--fortran");
            command.add(settings.fortranCompiler());
        }
        if (buildOnly) {
            command.add("--no-run");
        }
        Path log = directory.resolve(name + ".log");
        Path resultFile = directory.resolve(PscadPythonScriptWriter.resultFileName(name));
        try {
            Files.deleteIfExists(resultFile);
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!process.waitFor(settings.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new PowsyblException("SimuEmt Plus: project " + name + " took longer than " + settings.timeout() + logTail(script));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PowsyblException("SimuEmt Plus: interrupted while PSCAD ran project " + name, e);
        }
        if (!Files.exists(resultFile)) {
            throw new PowsyblException("SimuEmt Plus: the script of project " + name + " reported nothing - is mhi.pscad installed in "
                    + settings.pythonCommand() + "?" + logTail(script));
        }
        return read(resultFile);
    }

    static Result read(Path resultFile) {
        String status = null;
        Path buildFolder = null;
        List<String> errors = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = line.substring(0, equals);
                String value = line.substring(equals + 1);
                switch (key) {
                    case "status" -> status = value;
                    case "build_folder" -> buildFolder = value.isBlank() ? null : Path.of(value);
                    case "error" -> errors.add(value);
                    default -> {
                        // a key of a later script, nothing to act on
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (status == null) {
            throw new PowsyblException("SimuEmt Plus: no status in " + resultFile);
        }
        return new Result(status, buildFolder, errors);
    }

    private static PowsyblException failure(Path script, Result result) {
        return new PowsyblException("SimuEmt Plus: PSCAD project " + projectName(script) + " " + result.status().replace('_', ' ')
                + (result.errors().isEmpty() ? "" : ": " + String.join(" | ", result.errors())) + logTail(script));
    }

    private static String projectName(Path script) {
        String file = script.getFileName().toString();
        return file.endsWith(".py") ? file.substring(0, file.length() - 3) : file;
    }

    /** The end of the script's console, for a failure's message. */
    private static String logTail(Path script) {
        Path log = script.toAbsolutePath().getParent().resolve(projectName(script) + ".log");
        try {
            if (!Files.exists(log)) {
                return "";
            }
            // In the console's code page, whatever it is: read byte for byte, never refused.
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return System.lineSeparator() + "--- " + log + System.lineSeparator()
                    + String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 20), lines.size()));
        } catch (IOException | UncheckedIOException e) {
            return "";
        }
    }
}
