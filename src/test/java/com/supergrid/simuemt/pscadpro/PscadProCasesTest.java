/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case {@link PscadProBuildTest} has PSCAD build is written without it: no case the upstream writer refuses.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProCasesTest {

    static Stream<PscadProCases.Case> cases() {
        return PscadProCases.all();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void everyCaseIsWritten(PscadProCases.Case pscadCase, @TempDir Path tempDir) {
        PscadFileWriter.PscadProject pscx = pscadCase.write(tempDir, PscadProjectSerializer.PSCX);

        assertThat(pscx.file()).exists().hasFileName(pscx.circuit().name() + ".pscx");
    }

    @Test
    void theCasesHaveDistinctNames() {
        assertThat(PscadProCases.all().map(PscadProCases.Case::name)).doesNotHaveDuplicates().hasSize(71);
    }
}
