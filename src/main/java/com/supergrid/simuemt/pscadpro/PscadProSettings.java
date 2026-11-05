/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How a project script is run: with which Python - the one {@code mhi.pscad} is installed in - which PSCAD, which Fortran
 * compiler, and for how long at most.
 *
 * @param pythonCommand  the command that runs a Python script, the script's path appended: {@code py} on Windows, or a
 *                       Python's full path
 * @param pscadVersion   the PSCAD version launched, as {@code mhi.pscad.launch} takes it
 * @param fortranCompiler the Fortran compiler, as PSCAD's settings name it ({@code fortran_version}) - {@code null} for the
 *                       one PSCAD is set to
 * @param timeout        how long a script may take, PSCAD's start, build and run all included
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public record PscadProSettings(List<String> pythonCommand, String pscadVersion, String fortranCompiler, Duration timeout) {

    /** The 64-bit Intel Fortran Classic that PSCAD 5.1 detects with oneAPI 2024.2. */
    public static final String INTEL_FORTRAN_CLASSIC_64 = "Intel Fortran Compiler Classic 2021.12.0 (64-bit), v19.2.1083";

    public PscadProSettings {
        pythonCommand = List.copyOf(pythonCommand);
        if (pythonCommand.isEmpty()) {
            throw new IllegalArgumentException("SimuEmt Plus: no Python command");
        }
        Objects.requireNonNull(pscadVersion);
        Objects.requireNonNull(timeout);
    }

    /** {@code py}, PSCAD 5.1.0, the 64-bit Intel Fortran Classic, ten minutes. */
    public static PscadProSettings defaults() {
        return new PscadProSettings(List.of("py"), "5.1.0", INTEL_FORTRAN_CLASSIC_64, Duration.ofMinutes(10));
    }

    public PscadProSettings withFortranCompiler(String compiler) {
        return new PscadProSettings(pythonCommand, pscadVersion, compiler, timeout);
    }

    public PscadProSettings withPythonCommand(List<String> command) {
        return new PscadProSettings(command, pscadVersion, fortranCompiler, timeout);
    }

    public PscadProSettings withTimeout(Duration duration) {
        return new PscadProSettings(pythonCommand, pscadVersion, fortranCompiler, duration);
    }
}
