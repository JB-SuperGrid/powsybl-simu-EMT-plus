/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * An IIDM network simulated in PSCAD Professional, end to end: written as the {@code .pscx} {@code powsybl-simu-emt-pscad}
 * writes for PSCAD Free ({@link PscadProjectSerializer#PSCX}), loaded, built, compiled and run by PSCAD through its
 * automation library ({@link PscadProRunner}), and its curves read back ({@link PscadOutput}) - what PSCAD Free leaves to
 * whoever opens the {@code .pscx} by hand.
 *
 * <p>Everything about the circuit and its run is {@link PscadFileWriter}'s: the same circuit, the same events, and in steady
 * state the same template and restart - this only has PSCAD run them. The project is loaded whole, not drawn through the
 * library component by component: PSCAD takes each component drawn as an edit of its canvas, some 0.7 s apiece - 8 minutes
 * for a wind turbine's 683 - where it loads the whole file in a second.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class PscadProSimulation {

    /** A run: the project file and the circuit it was written from, where EMTDC wrote its output, and that output. */
    public record PscadProRun(PscadFileWriter.PscadProject project, Path buildFolder, PscadOutput output) {
    }

    private PscadProSimulation() {
    }

    /** {@code network} through {@code events}, from rest, in {@code workDir}. */
    public static PscadProRun run(Network network, Path workDir, PscadSimulationSettings settings, List<? extends EmtEvent> events,
                                  PscadProSettings pro) {
        PscadFileWriter.PscadProject project = PscadFileWriter.write(network, directory(workDir), settings, events,
                PscadProjectSerializer.PSCX);
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
                PscadProjectSerializer.PSCX);
        PscadProRun templateRun = execute(template, pro);
        PscadFileWriter.PscadProject restart = PscadFileWriter.writeRestart(network, directory(workDir.resolve("restart")), settings, events,
                templateRun.buildFolder(), PscadProjectSerializer.PSCX);
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
