/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;
import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two Bergeron lines' snapshot PSCAD Professional split at each, patched by upstream with their steady state: {@link
 * PscadProBergeronLinesTest}'s template as PSCAD Professional wrote it on 2026-11-17, at 0.02 s ({@code bergeron-lines-professional/},
 * its {@code .snp} and {@code Main.dta}), kept here since upstream keeps no Professional run. Three subsystems - B2 and its load in 1, B3
 * and its load in 2, B1 in 3 -; the lines' data after subsystem 3, its Modes block a column per mode, L1_2's three then L2_3's, their
 * Basic info's columns {@code 3 1} and {@code 1 2}, L2_3's TI from its 9th value. Patched, it is to the line the snapshot the live run
 * restarted from, steady from its first step ({@code two_bus_steady.snp}); a line whose ends the circuit has no line between is refused.
 *
 * <p>No PSCAD needed: runs with {@code mvn test}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProBergeronLinesSnapshotTest {

    /** The Modes block's sending ends' row: L1_2's B1 in subsystem 3, L2_3's B2 in subsystem 1, each numbered in its own. */
    private static final String SENDING_ENDS = "          1           2           3           4           5           6";

    @Test
    void thePatchedSnapshotIsTheOneThatRestartedSteady(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        String snapshot = Files.readString(template.resolve("two_bus.snp"));
        assertThat(snapshot.lines().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).hasSize(3);
        Path patched = tempDir.resolve("two_bus_steady.snp");

        PscadSnapshot.patch(circuit(tempDir), PscadProBergeronLinesTest.SETTINGS, template, patched);

        assertThat(Files.readAllLines(patched)).isEqualTo(Files.readAllLines(template.resolve("two_bus_steady.snp")));
        assertThat(Files.readAllLines(patched)).isNotEqualTo(snapshot.lines().toList());
    }

    /** L2_3's sending end moved onto B2's load, node 1 of subsystem 1: no line of the circuit runs from there. */
    @Test
    void aLineBetweenEndsTheCircuitHasNoLineBetweenIsRefused(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        Path moved = Files.createDirectories(tempDir.resolve("template"));
        List<String> lines = Files.readAllLines(template.resolve("two_bus.snp"));
        assertThat(lines).containsOnlyOnce(SENDING_ENDS);
        lines.set(lines.indexOf(SENDING_ENDS), SENDING_ENDS.replace("4           5           6", "1           2           3"));
        Files.write(moved.resolve("two_bus.snp"), lines);
        Files.copy(template.resolve("Main.dta"), moved.resolve("Main.dta"));
        PscadCircuit circuit = circuit(tempDir);

        assertThatThrownBy(() -> PscadSnapshot.patch(circuit, PscadProBergeronLinesTest.SETTINGS, moved, tempDir.resolve("patched.snp")))
                .isInstanceOf(PowsyblException.class).hasMessageContaining("its transmission line 2 runs from").hasMessageContaining("none of the circuit's");
    }

    /** The circuit the template was written from. */
    private static PscadCircuit circuit(Path tempDir) throws IOException {
        return PscadFileWriter.writeTemplate(PscadProBergeronLinesTest.bench(), Files.createDirectories(tempDir.resolve("written")),
                PscadProBergeronLinesTest.SETTINGS, List.of(), PscadProjectSerializer.PSCX).circuit();
    }

    private static Path template() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(PscadProBergeronLinesSnapshotTest.class.getResource("/bergeron-lines-professional/two_bus.snp"))
                .toURI()).getParent();
    }
}
