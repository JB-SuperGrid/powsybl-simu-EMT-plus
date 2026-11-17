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
 * A Bergeron line's snapshot PSCAD Professional split at the line, patched by upstream with its steady state: {@link
 * PscadProBergeronSubsystemsTest}'s template as PSCAD Professional wrote it on 2026-11-17, at 0.02 s ({@code
 * bergeron-subsystems-professional/}, its {@code .snp} and {@code Main.dta}), kept here since upstream keeps no Professional run. B2 and
 * the load in subsystem 1, B1 in 2; the line's data after subsystem 2, its Basic info {@code 2 1} - the sending end, B1, in subsystem 2,
 * the receiving one, B2, in 1 -, each end's nodes in its own subsystem's numbering. Patched, it is to the line the snapshot the live run
 * restarted from, steady from its first step on Kestrel's ({@code two_bus_steady.snp}); an end put in a subsystem the snapshot has not is
 * refused.
 *
 * <p>No PSCAD needed: runs with {@code mvn test}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProBergeronSubsystemsSnapshotTest {

    /** The Basic info of the line's data: its file, the sending end's subsystem, the receiving end's. */
    private static final String ENDS = " L1_2.tli";

    @Test
    void thePatchedSnapshotIsTheOneThatRestartedSteady(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        String snapshot = Files.readString(template.resolve("two_bus.snp"));
        assertThat(snapshot.lines().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).hasSize(2);
        Path patched = tempDir.resolve("two_bus_steady.snp");

        PscadSnapshot.patch(circuit(tempDir), PscadProBergeronSubsystemsTest.SETTINGS, template, patched);

        assertThat(Files.readAllLines(patched)).isEqualTo(Files.readAllLines(template.resolve("two_bus_steady.snp")));
        assertThat(Files.readAllLines(patched)).isNotEqualTo(snapshot.lines().toList());
    }

    @Test
    void anEndInASubsystemTheSnapshotHasNotIsRefused(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        Path moved = Files.createDirectories(tempDir.resolve("template"));
        List<String> lines = Files.readAllLines(template.resolve("two_bus.snp"));
        int info = lines.indexOf(lines.stream().filter(line -> line.startsWith(ENDS)).findFirst().orElseThrow());
        assertThat(lines.get(info + 1).trim()).isEqualTo("2");
        lines.set(info + 1, lines.get(info + 1).replace("2", "3"));
        Files.write(moved.resolve("two_bus.snp"), lines);
        Files.copy(template.resolve("Main.dta"), moved.resolve("Main.dta"));
        PscadCircuit circuit = circuit(tempDir);

        assertThatThrownBy(() -> PscadSnapshot.patch(circuit, PscadProBergeronSubsystemsTest.SETTINGS, moved, tempDir.resolve("patched.snp")))
                .isInstanceOf(PowsyblException.class).hasMessageContaining("puts an end in subsystem 3, the snapshot has 2");
    }

    /** The circuit the template was written from. */
    private static PscadCircuit circuit(Path tempDir) throws IOException {
        return PscadFileWriter.writeTemplate(PscadProBergeronSubsystemsTest.bench(), Files.createDirectories(tempDir.resolve("written")),
                PscadProBergeronSubsystemsTest.SETTINGS, List.of(), PscadProjectSerializer.PSCX).circuit();
    }

    private static Path template() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(PscadProBergeronSubsystemsSnapshotTest.class.getResource("/bergeron-subsystems-professional/two_bus.snp"))
                .toURI()).getParent();
    }
}
