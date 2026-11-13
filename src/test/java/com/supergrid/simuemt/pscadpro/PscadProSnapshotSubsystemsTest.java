/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A snapshot PSCAD Professional split into subsystems, read by upstream's restart: the template of {@link PscadProMetallicReturnTest}'s
 * bench as PSCAD Professional wrote it on 2026-11-13 ({@code vsc-metallic-return-professional/}, its {@code .snp} and {@code Main.dta}),
 * kept here since upstream keeps no Professional run - its own test of it, tagged {@code professional}, is left out of its builds. PSCAD
 * Professional splits decoupled networks ("Split Decoupled Networks into Separate Matrices", ticked in the projects upstream writes),
 * and the DC side, joined to the AC side through the converters' controlled sources alone, is a subsystem of its own: twelve AC nodes in
 * subsystem 1, DC1P, DC2G and DC2P numbered 1 to 3 in subsystem 2. Upstream reads such a snapshot since its {@code 199f37e0}, each
 * converter's bus in subsystem 1 and its DC pole in subsystem 2. Restarted from it live, the bench ran through the fault as with the split
 * unticked, to every digit printed ({@link PscadProMetallicReturnTest}).
 *
 * <p>No PSCAD needed: runs with {@code mvn test}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class PscadProSnapshotSubsystemsTest {

    private static final String SNAPSHOT = "vsc_monopole_infinite_buses.snp";
    /** The DC side's node voltages in the snapshot's subsystem 2, kV: DC1P, DC2G, DC2P. */
    private static final String DC_SIDE = "  500.0000009313043        2.019577622626855        497.9804233274202";
    private static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(20.0e-6, 0.8, 50.0, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);

    @Test
    void theRestartTakesTheSnapshotAsEmtdcWroteIt(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Path template = template();
        String snapshot = Files.readString(template.resolve(SNAPSHOT));
        assertThat(snapshot.lines().filter(line -> line.startsWith("!*** SUBSYSTEM:"))).hasSize(2);
        assertThat(snapshot.lines().filter(line -> line.trim().endsWith("/ Busses")).map(line -> line.trim().split("\\s+")[0]))
                .containsExactly("12", "3");
        assertThat(snapshot).containsOnlyOnce(DC_SIDE);

        PscadFileWriter.PscadProject restart = PscadFileWriter.writeRestart(bench(), tempDir, SETTINGS, events(), template);

        assertThat(Files.readString(restart.file())).contains("vsc_monopole_infinite_buses_steady.snp");
        assertThat(tempDir.resolve("vsc_monopole_infinite_buses_steady.snp")).hasContent(snapshot);
    }

    /** Each converter's DC pole read in subsystem 2: 1% off its load flow there, the snapshot is refused. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"VSC1, DC1P, 500.0000009313043, 495.0000000000000", "VSC2, DC2P, 497.9804233274202, 493.0000000000000"})
    void aDcPoleOffItsLoadFlowInTheDcSubsystemIsRefused(String converter, String pole, String held, String off, @TempDir Path tempDir)
            throws IOException, URISyntaxException {
        Path template = template();
        Path moved = Files.createDirectories(tempDir.resolve("template"));
        Files.writeString(moved.resolve(SNAPSHOT), Files.readString(template.resolve(SNAPSHOT)).replace(DC_SIDE, DC_SIDE.replace(held, off)));
        Files.copy(template.resolve("Main.dta"), moved.resolve("Main.dta"));
        Network network = bench();
        Path restart = Files.createDirectories(tempDir.resolve("restart"));

        assertThatThrownBy(() -> PscadFileWriter.writeRestart(network, restart, SETTINGS, events(), moved))
                .isInstanceOf(PowsyblException.class)
                .hasMessageContaining("its converter " + converter + " is not on the load flow")
                .hasMessageContaining("its DC pole " + pole);
    }

    private static Path template() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(PscadProSnapshotSubsystemsTest.class.getResource("/vsc-metallic-return-professional/" + SNAPSHOT))
                .toURI()).getParent();
    }

    private static Network bench() {
        return ExampleNetworks.withLoadFlow(EmtExtensionsXmlReader.readNetwork(
                ExampleNetworks.resource("/vsc-monopole-infinite-buses-metallic-return.xiidm"),
                ExampleNetworks.resource("/vsc-monopole-infinite-buses-metallic-return-extensions.xml")));
    }

    private static List<EmtEvent> events() {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b2-a-0.3-0.4.xml"));
    }
}
