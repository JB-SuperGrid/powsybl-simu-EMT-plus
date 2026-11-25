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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Has PSCAD Professional build and run a project, and reads what it reports: PSCAD launched, the project built, compiled and
 * run, EMTDC's output in its build folder. The project is a {@code .pscx}, loaded whole by {@code pscad_run.py}, the driver
 * this writes beside it. The console goes to {@code <name>.log} beside the project, and what was done to {@code <name>.result}.
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

    /** The driver a {@code .pscx} is loaded, built and run by, written beside it. */
    static final String DRIVER = "pscad_run.py";

    /** How many times a project is run before its timeout is reported. */
    static final int ATTEMPTS = 2;

    /** How long a killed PSCAD is given to be gone, its licence with it, before the next go. */
    private static final long KILLED_WAIT_SECONDS = 30;

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

    /** Builds {@code project} - compiled, linked into EMTDC's executable - without running it. */
    public static Result build(Path project, PscadProSettings settings) {
        Result result = execute(project, settings, true);
        if (!"built".equals(result.status())) {
            throw failure(project, result);
        }
        return result;
    }

    /**
     * Runs the driver; if it takes longer than the settings' timeout, kills it - PSCAD and EMTDC with it - and runs it once more.
     * A PSCAD stuck once, short of memory, ran the same project at the next go; one stuck twice is reported. The first go's
     * console is kept as {@code <name>.timed-out.log}.
     */
    private static Result execute(Path project, PscadProSettings settings, boolean buildOnly) {
        String name = projectName(project);
        Path log = project.toAbsolutePath().getParent().resolve(name + ".log");
        for (int attempt = 1; ; attempt++) {
            try {
                return attempt(project, settings, buildOnly);
            } catch (TimedOut e) {
                if (attempt == ATTEMPTS) {
                    throw new PowsyblException("SimuEmt Plus: project " + name + " took longer than " + settings.timeout() + " "
                            + ATTEMPTS + " times" + logTail(project));
                }
                try {
                    // Copied, not moved: a killed process may hold it open a moment longer.
                    Files.copy(log, log.resolveSibling(name + ".timed-out.log"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // the next go writes over it
                }
            }
        }
    }

    /** The driver took longer than its timeout, and was killed. */
    private static final class TimedOut extends Exception {
        TimedOut() {
            super(null, null, false, false);
        }
    }

    private static Result attempt(Path project, PscadProSettings settings, boolean buildOnly) throws TimedOut {
        Path directory = project.toAbsolutePath().getParent();
        String name = projectName(project);
        List<String> command = new ArrayList<>(settings.pythonCommand());
        command.add(driver(directory).toString());
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
        Path resultFile = directory.resolve(name + ".result");
        try {
            Files.deleteIfExists(resultFile);
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            if (!process.waitFor(settings.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                throw new TimedOut();
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

    /** Kills the driver and all it started - PSCAD, EMTDC - and waits for them to be gone. */
    private static void kill(Process process) throws InterruptedException {
        List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList());
        handles.add(process.toHandle());
        handles.forEach(ProcessHandle::destroyForcibly);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(KILLED_WAIT_SECONDS);
        for (ProcessHandle handle : handles) {
            try {
                handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException e) {
                // still there: the next go will tell
            }
        }
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
                        // a key of a later driver, nothing to act on
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
