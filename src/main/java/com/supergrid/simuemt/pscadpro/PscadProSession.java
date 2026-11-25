/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One PSCAD Professional kept open from project to project, by {@code pscad_session.py}: Python and PSCAD take some 8 s to
 * start, most of a small project's 11 s build - twelve builds took 181 s instead of 258 s. A project is sent on the script's
 * standard input, and is done when the script answers; how long its load, build and run took is in the session's
 * {@code session.log}, in a {@code simuemt-pscad-session} temporary folder.
 *
 * <p>One session at a time, shared: started at the first project, started again for other settings (Python, PSCAD version,
 * compiler), every {@value #PROJECTS_PER_SESSION} projects - PSCAD's memory grows, and a PSCAD short of memory once hung - and
 * after a project that took longer than its timeout, killed - PSCAD and EMTDC with it. Quit when the JVM exits; the script
 * quits PSCAD itself when its input ends, the JVM killed.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class PscadProSession {

    /** The script that keeps PSCAD open. */
    static final String SCRIPT = "pscad_session.py";

    /** What the script's answer starts with. */
    static final String DONE = "SIMUEMT-PSCAD-DONE";

    /** How many projects one PSCAD builds or runs before it is started again. */
    static final int PROJECTS_PER_SESSION = 20;

    /** How long a PSCAD is given to quit, or to be gone once killed - its licence with it. */
    private static final long WAIT_SECONDS = 30;

    /** The script's standard output ended: it is gone. */
    private static final String ENDED = "";

    /** The project took longer than its timeout: its PSCAD was killed. */
    static final class TimedOut extends Exception {

        private final String console;

        TimedOut(String console) {
            super(null, null, false, false);
            this.console = console;
        }

        /** The end of the killed session's console. */
        String console() {
            return console;
        }
    }

    private static PscadProSession current;
    private static boolean hooked;

    private final PscadProSettings settings;
    private final Path directory;
    private final Process process;
    private final Writer input;
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private int projects;

    private PscadProSession(PscadProSettings settings) throws IOException {
        this.settings = settings;
        directory = Files.createTempDirectory("simuemt-pscad-session");
        List<String> command = new ArrayList<>(settings.pythonCommand());
        command.add(script(directory).toString());
        command.add("--version");
        command.add(settings.pscadVersion());
        if (settings.fortranCompiler() != null) {
            command.add("--fortran");
            command.add(settings.fortranCompiler());
        }
        process = new ProcessBuilder(command).directory(directory.toFile()).redirectError(directory.resolve("session.log").toFile()).start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(this::readAnswers, "pscad-session");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Has the shared session build - and, unless {@code buildOnly}, run - {@code project}, within the settings' timeout. Its
     * {@code .result} is written beside it, unless the script died first.
     */
    static synchronized void execute(Path project, PscadProSettings settings, boolean buildOnly) throws TimedOut, InterruptedException {
        if (current != null && (!current.startedWith(settings) || current.projects >= PROJECTS_PER_SESSION || !current.process.isAlive())) {
            current.quit();
            current = null;
        }
        if (current == null) {
            if (!hooked) {
                Runtime.getRuntime().addShutdownHook(new Thread(PscadProSession::quitCurrent, "pscad-session-quit"));
                hooked = true;
            }
            try {
                current = new PscadProSession(settings);
            } catch (IOException e) {
                throw new UncheckedIOException("SimuEmt Plus: could not start " + settings.pythonCommand() + " " + SCRIPT, e);
            }
        }
        PscadProSession session = current;
        session.projects++;
        try {
            session.input.write((buildOnly ? "build" : "run") + "\t" + project.toAbsolutePath() + "\n");
            session.input.flush();
        } catch (IOException e) {
            // the script is gone: no answer will come, and no result
            current = null;
            session.kill();
            return;
        }
        String answer = session.answers.poll(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
        if (answer == null) {
            current = null;
            session.kill();
            throw new TimedOut(session.console());
        }
        if (answer.equals(ENDED)) {
            current = null;
            session.kill();
        }
    }

    private static synchronized void quitCurrent() {
        if (current != null) {
            current.quit();
            current = null;
        }
    }

    private boolean startedWith(PscadProSettings other) {
        return settings.pythonCommand().equals(other.pythonCommand()) && settings.pscadVersion().equals(other.pscadVersion())
                && Objects.equals(settings.fortranCompiler(), other.fortranCompiler());
    }

    private void readAnswers() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(DONE)) {
                    answers.add(DONE);
                }
            }
        } catch (IOException e) {
            // the script is gone
        }
        answers.add(ENDED);
    }

    /** Asks the script to quit PSCAD, and kills it if it has not within {@link #WAIT_SECONDS}. */
    private void quit() {
        try {
            input.write("quit\n");
            input.close();
        } catch (IOException e) {
            // already gone
        }
        try {
            if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
                kill();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kill();
        }
    }

    /** Kills the script and all it started - PSCAD, EMTDC - and waits for them to be gone. */
    private void kill() {
        List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList());
        handles.add(process.toHandle());
        handles.forEach(ProcessHandle::destroyForcibly);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        for (ProcessHandle handle : handles) {
            try {
                handle.onExit().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException e) {
                // still there: the next session will tell
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** The end of the session's console - Python's and PSCAD's library's warnings and errors. */
    private String console() {
        Path log = directory.resolve("session.log");
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
            return System.lineSeparator() + "--- " + log + System.lineSeparator()
                    + String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 20), lines.size()));
        } catch (IOException | UncheckedIOException e) {
            return "";
        }
    }

    /** {@link #SCRIPT}, written into {@code directory}. */
    static Path script(Path directory) {
        Path script = directory.resolve(SCRIPT);
        try (InputStream in = PscadProSession.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("SimuEmt Plus: " + SCRIPT + " is missing from the classpath");
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not write " + script, e);
        }
        return script;
    }
}
