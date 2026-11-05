/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script builds, in the real PSCAD, the circuit the {@code .pscx} is: each network written both ways, both built by
 * PSCAD, and the Fortran EMTDC is compiled from - {@code Main.f}, and {@code Main.dta}, its network data - the same. It
 * stops short of linking an executable, so it needs no linker: what holds the two routes to each other where a run cannot
 * be made, and what says a difference in a run is not the script's.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}, on a machine with PSCAD 5.1 Professional licensed and its
 * Fortran compiler.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProEquivalenceTest {

    private static final PscadProSettings PRO = PscadProSettings.defaults();
    private static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(50.0e-6, 0.2, 50.0, PscadEdition.PROFESSIONAL);

    static Stream<Arguments> networks() {
        return Stream.of(
                Arguments.of("two-bus", (Supplier<Network>) ExampleNetworks::createTwoBusNetworkWithIdealInfiniteBusGenerator),
                Arguments.of("bergeron-line", (Supplier<Network>) () -> {
                    Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
                    network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
                    return network;
                }),
                Arguments.of("transformer", (Supplier<Network>) ExampleNetworks::createThreeBusNetworkWithTransformerAndEmtExtensions),
                Arguments.of("machine-ac1a", (Supplier<Network>) ExampleNetworks::createTwoBusNetworkWithAc1A),
                Arguments.of("wind-turbine", (Supplier<Network>) ExampleNetworks::createWindTurbineNetwork));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("networks")
    void theScriptBuildsWhatThePscxBuilds(String name, Supplier<Network> networks, @TempDir Path tempDir) throws Exception {
        Path pscxDir = Files.createDirectories(tempDir.resolve("pscx"));
        Path scriptDir = Files.createDirectories(tempDir.resolve("script"));
        PscadFileWriter.PscadProject pscx = PscadFileWriter.write(ExampleNetworks.withLoadFlow(networks.get()), pscxDir, SETTINGS);
        PscadFileWriter.PscadProject script = PscadFileWriter.write(ExampleNetworks.withLoadFlow(networks.get()), scriptDir, SETTINGS, List.of(),
                PscadPythonScriptWriter.INSTANCE);

        Path reference = buildPscx(pscx.file());
        Path built = PscadProRunner.compile(script.file(), PRO).buildFolder();

        for (String file : List.of("Main.f", "Main.dta")) {
            // Kept, normalized, for whoever has to see a difference: target/equivalence/<network>/
            Path kept = Files.createDirectories(Path.of("target", "equivalence", name));
            Files.writeString(kept.resolve("pscx-" + file), normalized(reference.resolve(file), pscxDir));
            Files.writeString(kept.resolve("script-" + file), normalized(built.resolve(file), scriptDir));
            assertThat(normalized(built.resolve(file), scriptDir)).as("%s of %s", file, name)
                    .isEqualTo(normalized(reference.resolve(file), pscxDir));
        }
    }

    /** The build folder PSCAD made of a {@code .pscx}. */
    private static Path buildPscx(Path pscx) throws IOException, InterruptedException {
        Path helper = pscx.getParent().resolve("build_pscx.py");
        try (InputStream in = PscadProEquivalenceTest.class.getResourceAsStream("/build_pscx.py")) {
            Files.copy(in, helper);
        }
        List<String> command = new java.util.ArrayList<>(PRO.pythonCommand());
        command.addAll(List.of(helper.toString(), pscx.toString(), "--fortran", PRO.fortranCompiler(), "--version", PRO.pscadVersion()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(pscx.getParent().resolve("build_pscx.log").toFile()).start();
        assertThat(process.waitFor(10, TimeUnit.MINUTES)).isTrue();
        String name = pscx.getFileName().toString().replace(".pscx", "");
        PscadProRunner.Result result = PscadProRunner.read(pscx.getParent().resolve(name + ".result"));
        assertThat(result.buildFolder()).as("%s built: %s", pscx, result.errors()).isNotNull();
        assertThat(result.buildFolder().resolve("Main.f")).exists();
        return result.buildFolder();
    }

    /** A build's file, the folder it was built in and the instant it was built at left out. */
    private static String normalized(Path file, Path projectDir) throws IOException {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        return PscadBuildNormalizer.normalize(text, projectDir);
    }
}
