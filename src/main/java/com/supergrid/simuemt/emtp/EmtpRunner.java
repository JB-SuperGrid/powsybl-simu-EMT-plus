/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an EMTP netlist ({@code <name>.net}) with EMTP's solver alone, {@code emtpopt.exe}, without EMTPWorks: as EMTPWorks starts it
 * once it has written a design's netlist, with no progress panel. The solver writes everything into the netlist's project folder,
 * {@code <name>_pj} beside it - which it does not create: missing, it reports the error on its console only and exits with code 0,
 * having written nothing. So this creates it, removes the outputs of an earlier run, and refuses a run that did not end in EMTP's
 * {@code EMTP terminated} or that reported an error ({@code !!Error}), with the solver's console and its log's end.
 *
 * <p>The outputs ({@link EmtpOutput}, {@link EmtpSteadyState}): {@code <name>.out}, the log; {@code <name>m.m} and {@code <name>.mda},
 * the scopes; {@code <name>ss.xml}, the steady-state solution the time-domain run starts from.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class EmtpRunner {

    /** What EMTP prints last on a run that went through. */
    static final String TERMINATED = "EMTP terminated";
    /** How EMTP flags an error, on its console and in its log. */
    static final String ERROR = "!!Error";

    /** A run: the netlist's name, its project folder and what the solver printed on its console. */
    public record Run(String name, Path projectDirectory, List<String> console) {

        public Run {
            console = List.copyOf(console);
        }

        /** The scopes the run recorded. */
        public EmtpOutput output() {
            return EmtpOutput.read(projectDirectory, name);
        }

        /** The steady-state solution the run started from. */
        public EmtpSteadyState steadyState() {
            return EmtpSteadyState.read(projectDirectory, name);
        }
    }

    private EmtpRunner() {
    }

    /** Runs {@code netlist}; refused, with EMTP's messages, unless it ran through. */
    public static Run run(Path netlist, EmtpSettings settings) {
        Path file = netlist.toAbsolutePath();
        String name = name(file);
        Path project = file.resolveSibling(name + "_pj");
        Path console = project.resolve(name + ".console");
        try {
            Files.createDirectories(project);
            for (String output : List.of(name + ".out", name + "m.m", name + ".mda", name + "ss.xml", name + ".console")) {
                Files.deleteIfExists(project.resolve(output));
            }
            Process process = new ProcessBuilder(command(file, settings)).directory(file.getParent().toFile()).redirectErrorStream(true)
                    .redirectOutput(console.toFile()).start();
            if (!process.waitFor(settings.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new PowsyblException("SimuEmt Plus: EMTP took longer than " + settings.timeout() + " on " + file + messages(project, name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not run EMTP on " + file, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PowsyblException("SimuEmt Plus: interrupted while EMTP ran " + file, e);
        }
        List<String> printed = lines(console);
        List<String> log = lines(project.resolve(name + ".out"));
        if (log.stream().noneMatch(line -> line.contains(TERMINATED)) || printed.stream().anyMatch(line -> line.contains(ERROR))
                || log.stream().anyMatch(line -> line.contains(ERROR))) {
            throw new PowsyblException("SimuEmt Plus: EMTP did not run " + file + " through" + messages(project, name));
        }
        return new Run(name, project, printed);
    }

    /**
     * The solver's command line: its one argument the fields EMTPWorks gives it, separated by semicolons - the state file twice, the
     * netlist, {@code 0} for no progress window, {@code 1}, and the progress panel's identifier, none here. Unquoted: Java quotes the
     * whole argument when a path has a space in it, which the solver reads as EMTPWorks' quoted paths.
     */
    static List<String> command(Path netlist, EmtpSettings settings) {
        return List.of(settings.emtpopt().toString(), settings.stateFile() + ";" + settings.stateFile() + ";" + netlist + ";0;1;;");
    }

    /** {@code <name>.net}'s {@code <name>}; refused for a file of another extension. */
    static String name(Path netlist) {
        String file = netlist.getFileName().toString();
        if (!file.toLowerCase(java.util.Locale.ROOT).endsWith(".net")) {
            throw new PowsyblException("SimuEmt Plus: an EMTP netlist is a .net file, not " + file);
        }
        return file.substring(0, file.length() - ".net".length());
    }

    private static String messages(Path project, String name) {
        List<String> printed = lines(project.resolve(name + ".console"));
        List<String> log = lines(project.resolve(name + ".out"));
        return "\n--- EMTP's console:\n" + String.join("\n", tail(printed)) + "\n--- " + project.resolve(name + ".out") + ":\n"
                + String.join("\n", tail(log));
    }

    private static List<String> tail(List<String> lines) {
        return lines.subList(Math.max(0, lines.size() - 20), lines.size());
    }

    private static List<String> lines(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1).stream().map(String::stripTrailing).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
