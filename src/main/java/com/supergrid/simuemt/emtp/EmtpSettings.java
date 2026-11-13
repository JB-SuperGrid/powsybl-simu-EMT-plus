/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Where EMTP's solver is and how long a run may take: {@code emtpopt.exe}, the simulation engine EMTPWorks starts once it has
 * written a design's netlist, and the {@code emtpstate.ini} it is handed - EMTPWorks' state file, which the engine is given twice,
 * as EMTPWorks gives it.
 *
 * @param emtpopt   the solver, {@code <EMTPWorks>\EMTP\emtpopt.exe}
 * @param stateFile EMTPWorks' {@code emtpstate.ini}, in the user's application data
 * @param timeout   how long a run may take before it is stopped
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public record EmtpSettings(Path emtpopt, Path stateFile, Duration timeout) {

    /** EMTPWorks 4.6.0's default installation folder. */
    public static final Path DEFAULT_INSTALLATION = Path.of("C:\\Program Files (x86)\\EMTPWorks 4.6.0");

    public EmtpSettings {
        Objects.requireNonNull(emtpopt);
        Objects.requireNonNull(stateFile);
        Objects.requireNonNull(timeout);
    }

    /** EMTP 4.6.0 where its installer puts it, its state file in {@code %APPDATA%\EMTP}, ten minutes. */
    public static EmtpSettings defaults() {
        String appData = System.getenv("APPDATA");
        Path roaming = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        return new EmtpSettings(DEFAULT_INSTALLATION.resolve("EMTP").resolve("emtpopt.exe"),
                roaming.resolve("EMTP").resolve("C_Program_Files_x86_EMTPWorks_4_6_0").resolve("emtpstate.ini"), Duration.ofMinutes(10));
    }

    public EmtpSettings withTimeout(Duration duration) {
        return new EmtpSettings(emtpopt, stateFile, duration);
    }
}
