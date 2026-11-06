/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Has PSCAD Professional build and run a project, and reads what it reports: PSCAD launched, the project built, compiled and
 * run, EMTDC's output in its build folder. The project is a {@code .pscx} - loaded whole by {@code pscad_run.py}, the driver
 * this writes beside it - or a script {@link PscadPythonScriptWriter} wrote, which draws it component by component. Either
 * way, the console goes to {@code <name>.log} beside the project, and what was done to {@code <name>.result}.
 *
 * <p>A failure is reported, by name, with PSCAD's errors: a missing licence, a compiler that will not link (PSCAD's Intel
 * Fortran needs the Windows SDK's libraries), a run that wrote nothing.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class PscadProRunner {

    /** What the run reported.
     *
     * @param status      {@code ran}, {@code built}, {@code build_failed} or {@code failed}
     * @param buildFolder where EMTDC wrote its output, {@code null} if the project was never built
     * @param errors      PSCAD's errors, or the driver's own
     * @param warnings    what a drawing script left out of the project, and why; none for a {@code .pscx}
     */
    public record Result(String status, Path buildFolder, List<String> errors, List<String> warnings) {

        public Result {
            Objects.requireNonNull(status);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean ran() {
            return "ran".equals(status);
        }
    }

    /** The driver a {@code .pscx} is loaded, built and run by, written beside it. */
    static final String DRIVER = "pscad_run.py";

    private PscadProRunner() {
    }

    /** Builds and runs {@code project}; refused, with PSCAD's errors, unless it ran and wrote its output. */
    public static Result run(Path project, PscadProSettings settings) {
        Result result = execute(project, settings, false);
        if (!result.ran()) {
            throw failure(project, result);
        }
        String name = projectName(project);
        if (!Files.exists(result.buildFolder().resolve(name + ".infx"))) {
            throw new PowsyblException("SimuEmt Plus: PSCAD ran project " + name + " but wrote no output in " + result.buildFolder()
                    + logTail(project));
        }
        return result;
    }

    /** Builds {@code project} without running it - what a check of the circuit PSCAD compiles takes. */
    public static Result build(Path project, PscadProSettings settings) {
        Result result = execute(project, settings, true);
        if (!"built".equals(result.status())) {
            throw failure(project, result);
        }
        return result;
    }

    /**
     * Has PSCAD turn {@code project} into EMTDC's Fortran - {@code Main.f}, {@code Main.dta} in its build
     * folder - whether or not an executable is then linked: what the circuit PSCAD was given is, checked without a linker.
     * Refused only if the project never got as far as its build folder.
     */
    public static Result compile(Path project, PscadProSettings settings) {
        Result result = execute(project, settings, true);
        if (result.buildFolder() == null || !Files.exists(result.buildFolder().resolve("Main.f"))) {
            throw failure(project, result);
        }
        return result;
    }

    private static Result execute(Path project, PscadProSettings settings, boolean buildOnly) {
        Path directory = project.toAbsolutePath().getParent();
        String name = projectName(project);
        List<String> command = new ArrayList<>(settings.pythonCommand());
        if (isPscx(project)) {
            command.add(driver(directory).toString());
        }
        command.add(project.toAbsolutePath().toString());
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
                throw new PowsyblException("SimuEmt Plus: project " + name + " took longer than " + settings.timeout() + logTail(project));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not run " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PowsyblException("SimuEmt Plus: interrupted while PSCAD ran project " + name, e);
        }
        if (!Files.exists(resultFile)) {
            throw new PowsyblException("SimuEmt Plus: PSCAD project " + name + " reported nothing - is mhi.pscad installed in "
                    + settings.pythonCommand() + "?" + logTail(project));
        }
        return read(resultFile);
    }

    private static boolean isPscx(Path project) {
        return project.getFileName().toString().endsWith(".pscx");
    }

    /** {@link #DRIVER}, written into {@code directory}. */
    private static Path driver(Path directory) {
        Path driver = directory.resolve(DRIVER);
        try (InputStream in = PscadProRunner.class.getResourceAsStream(DRIVER)) {
            if (in == null) {
                throw new IllegalStateException("SimuEmt Plus: " + DRIVER + " is missing from the classpath");
            }
            Files.copy(in, driver, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not write " + driver, e);
        }
        return driver;
    }

    static Result read(Path resultFile) {
        String status = null;
        Path buildFolder = null;
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
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
                    case "warning" -> warnings.add(value);
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
        return new Result(status, buildFolder, errors, warnings);
    }

    private static PowsyblException failure(Path project, Result result) {
        return new PowsyblException("SimuEmt Plus: PSCAD project " + projectName(project) + " " + result.status().replace('_', ' ')
                + (result.errors().isEmpty() ? "" : ": " + String.join(" | ", result.errors())) + logTail(project));
    }

    private static String projectName(Path project) {
        String file = project.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    /** The end of the project's console, for a failure's message. */
    private static String logTail(Path project) {
        Path log = project.toAbsolutePath().getParent().resolve(projectName(project) + ".log");
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
