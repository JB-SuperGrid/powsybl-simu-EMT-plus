/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A passive network's snapshot PSCAD Professional split into subsystems, patched by upstream with its steady state: {@link
 * PscadProTwoIslandsTest}'s template as PSCAD Professional wrote it on 2026-11-13, at 0.02 s, one period from rest ({@code
 * two-islands-professional/}, its {@code .snp} and {@code Main.dta}), kept here since upstream keeps no Professional run. Each island is a
 * subsystem: B1 and B2's in 1, B3, B4 and B5's in 2, with its transformer's three units, headed {@code 2 -2} - two windings, ideal, in
 * subsystem 2 -, which upstream reads since its {@code 9a95cf57}. Patched, it is to the line the snapshot the live run restarted from,
 * steady from its first step to 8.1e-6 pu ({@code two_islands_steady.snp}); a unit headed with another subsystem's number is refused.
 *
 * <p>No PSCAD needed: runs with {@code mvn test}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProTwoIslandsSnapshotTest {

    private static final String UNIT_HEADER = "          2          -2";

    @Test
    void thePatchedSnapshotIsTheOneThatRestartedSteady(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        String snapshot = Files.readString(template.resolve("two_islands.snp"));
        assertThat(snapshot.lines().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).hasSize(2);
        assertThat(snapshot.lines().filter(UNIT_HEADER::equals)).hasSize(3);
        Path patched = tempDir.resolve("two_islands_steady.snp");

        PscadSnapshot.patch(circuit(tempDir), scenario().settings(), template, patched);

        assertThat(Files.readAllLines(patched)).isEqualTo(Files.readAllLines(template.resolve("two_islands_steady.snp")));
        assertThat(Files.readAllLines(patched)).isNotEqualTo(snapshot.lines().toList());
    }

    @Test
    void aUnitHeadedWithAnotherSubsystemsNumberIsRefused(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        Path moved = Files.createDirectories(tempDir.resolve("template"));
        Files.writeString(moved.resolve("two_islands.snp"), Files.readString(template.resolve("two_islands.snp"))
                .replace(UNIT_HEADER, UNIT_HEADER.replace("-2", "-1")));
        Files.copy(template.resolve("Main.dta"), moved.resolve("Main.dta"));
        PscadCircuit circuit = circuit(tempDir);

        assertThatThrownBy(() -> PscadSnapshot.patch(circuit, scenario().settings(), moved, tempDir.resolve("patched.snp")))
                .isInstanceOf(PowsyblException.class).hasMessageContaining("its TRANSFORMER DATA block holds 0 units, not 3");
    }

    private static PscadProCases.Case scenario() {
        return PscadProCases.all().filter(c -> c.name().equals("template-two-islands")).findFirst().orElseThrow();
    }

    /** The circuit the template was written from. */
    private static PscadCircuit circuit(Path tempDir) throws IOException {
        return scenario().write(Files.createDirectories(tempDir.resolve("written")), PscadProjectSerializer.PSCX).circuit();
    }

    private static Path template() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(PscadProTwoIslandsSnapshotTest.class.getResource("/two-islands-professional/two_islands.snp"))
                .toURI()).getParent();
    }
}
