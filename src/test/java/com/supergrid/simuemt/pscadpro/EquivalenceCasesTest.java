/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case {@link PscadProEquivalenceTest} builds in PSCAD is written both ways without it: no case the upstream writer
 * refuses, and the script's file beside the {@code .pscx}'s.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EquivalenceCasesTest {

    static java.util.stream.Stream<EquivalenceCases.Case> cases() {
        return EquivalenceCases.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyCaseIsWrittenBothWays(EquivalenceCases.Case equivalenceCase, @TempDir Path tempDir) throws Exception {
        PscadFileWriter.PscadProject pscx = equivalenceCase.write(Files.createDirectories(tempDir.resolve("pscx")), PscadProjectSerializer.PSCX);
        PscadFileWriter.PscadProject script = equivalenceCase.write(Files.createDirectories(tempDir.resolve("script")), PscadPythonScriptWriter.INSTANCE);

        assertThat(pscx.file()).exists();
        assertThat(script.file()).exists().hasFileName(pscx.circuit().name() + ".py");
        assertThat(script.circuit().components()).hasSameSizeAs(pscx.circuit().components());
    }
}
