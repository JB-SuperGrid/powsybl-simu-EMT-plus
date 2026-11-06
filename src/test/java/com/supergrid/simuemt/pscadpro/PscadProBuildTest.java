/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case of {@link PscadProCases} as {@link PscadProSimulation} runs it: its {@code .pscx} loaded whole by PSCAD
 * Professional, built, compiled and linked into EMTDC's executable, without an error from PSCAD or its compiler. About 15 s a
 * case.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}, on a machine with PSCAD 5.1 Professional licensed, Intel
 * Fortran and the Windows SDK. {@code -Dpscad.cases=two-bus,wind-turbine} builds only those.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProBuildTest {

    private static final PscadProSettings PRO = PscadProSettings.defaults();

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.supergrid.simuemt.pscadpro.PscadProCases#selected")
    void theProjectBuilds(PscadProCases.Case buildCase, @TempDir Path tempDir) {
        PscadFileWriter.PscadProject pscx = buildCase.write(tempDir, PscadProjectSerializer.PSCX);

        PscadProRunner.Result result = PscadProRunner.build(pscx.file(), PRO);

        assertThat(result.errors()).as("%s", buildCase).isEmpty();
        assertThat(result.buildFolder().resolve(pscx.circuit().name() + ".exe")).as("%s", buildCase).exists();
    }
}
