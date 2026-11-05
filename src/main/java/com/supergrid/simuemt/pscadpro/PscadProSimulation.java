/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An IIDM network simulated in PSCAD Professional, end to end: written as a project script ({@link PscadPythonScriptWriter}),
 * built, compiled and run by PSCAD ({@link PscadProRunner}), and its curves read back ({@link PscadOutput}) - what
 * {@code powsybl-simu-emt-pscad} leaves to whoever opens its {@code .pscx} in PSCAD Free.
 *
 * <p>Everything about the circuit and its run is {@link PscadFileWriter}'s, as for the {@code .pscx}: the same circuit, the
 * same events, and in steady state the same template and restart - this only writes them as scripts and runs them.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class PscadProSimulation {

    /** A run: the script and the circuit it was written from, where EMTDC wrote its output, and that output. */
    public record PscadProRun(PscadFileWriter.PscadProject project, Path buildFolder, PscadOutput output) {
    }

    private PscadProSimulation() {
    }

    /** {@code network} through {@code events}, from rest, in {@code workDir}. */
    public static PscadProRun run(Network network, Path workDir, PscadSimulationSettings settings, List<? extends EmtEvent> events,
                                  PscadProSettings pro) {
        PscadFileWriter.PscadProject project = PscadFileWriter.write(network, directory(workDir), settings, events,
                PscadPythonScriptWriter.INSTANCE);
        return execute(project, pro);
    }

    /**
     * {@code network} through {@code events}, from its steady state - as ATP, Xyce and Kestrel start: the template run from
     * rest in {@code workDir/template}, which writes its snapshot, then the restart from it, patched with the circuit's steady
     * state, in {@code workDir/restart} (see {@link PscadFileWriter#writeTemplate} and {@link PscadFileWriter#writeRestart}).
     */
    public static PscadProRun runFromSteadyState(Network network, Path workDir, PscadSimulationSettings settings,
                                                 List<? extends EmtEvent> events, PscadProSettings pro) {
        PscadFileWriter.PscadProject template = PscadFileWriter.writeTemplate(network, directory(workDir.resolve("template")), settings, events,
                PscadPythonScriptWriter.INSTANCE);
        PscadProRun templateRun = execute(template, pro);
        PscadFileWriter.PscadProject restart = PscadFileWriter.writeRestart(network, directory(workDir.resolve("restart")), settings, events,
                templateRun.buildFolder(), PscadPythonScriptWriter.INSTANCE);
        return execute(restart, pro);
    }

    private static PscadProRun execute(PscadFileWriter.PscadProject project, PscadProSettings pro) {
        Objects.requireNonNull(pro);
        PscadProRunner.Result result = PscadProRunner.run(project.file(), pro);
        return new PscadProRun(project, result.buildFolder(), PscadOutput.read(result.buildFolder(), project.circuit().name()));
    }

    private static Path directory(Path directory) {
        try {
            return Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not create " + directory, e);
        }
    }
}
