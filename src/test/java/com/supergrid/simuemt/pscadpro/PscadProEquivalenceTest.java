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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The script draws, in the real PSCAD, the circuit the {@code .pscx} is: each case of {@link EquivalenceCases} written both
 * ways - the {@code .pscx} loaded whole, as {@link PscadProSimulation} runs it, and the script drawing it component by component
 * through PSCAD's documented library - both built by PSCAD, and every file EMTDC is compiled from the same - {@code Main.f}, {@code Main.dta} its network
 * data, and those of the project's own components. It stops short of linking an executable, so it needs no linker: what holds the two routes to each other where a run cannot
 * be made, what says a difference in a run is not the file's, and
 * what tells, on a new PSCAD, that the file is still read as the library would draw it.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}, on a machine with PSCAD 5.1 Professional licensed and its
 * Fortran compiler.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProEquivalenceTest {

    private static final PscadProSettings PRO = PscadProSettings.defaults();
    private static final String STATION = "Station.";
    private static final String DEFAULT_STATION = "DS.";

    /** Every case, or those named in {@code -Dequivalence.cases=two-bus,machine-ac1a}: each takes two PSCAD builds. */
    static Stream<EquivalenceCases.Case> cases() {
        String only = System.getProperty("equivalence.cases", "");
        List<String> names = only.isBlank() ? List.of() : List.of(only.split(","));
        return EquivalenceCases.all().filter(c -> names.isEmpty() || names.contains(c.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theScriptBuildsWhatThePscxBuilds(EquivalenceCases.Case equivalenceCase, @TempDir Path tempDir) throws Exception {
        Path pscxDir = Files.createDirectories(tempDir.resolve("pscx"));
        Path scriptDir = Files.createDirectories(tempDir.resolve("script"));
        PscadFileWriter.PscadProject pscx = equivalenceCase.write(pscxDir, PscadProjectSerializer.PSCX);
        PscadFileWriter.PscadProject script = equivalenceCase.write(scriptDir, PscadPythonScriptWriter.INSTANCE);

        Path reference = PscadProRunner.compile(pscx.file(), PRO).buildFolder();
        PscadProRunner.Result compiled = PscadProRunner.compile(script.file(), PRO);
        Path built = compiled.buildFolder();

        // Every file EMTDC is compiled from: Main's, and those of the project's own components
        List<String> sources;
        try (Stream<Path> files = Files.list(reference)) {
            sources = files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".f") || f.endsWith(".dta")).sorted().toList();
        }
        assertThat(sources).contains("Main.f", "Main.dta");
        Path kept = Files.createDirectories(Path.of("target", "equivalence", equivalenceCase.name()));
        // What the script left out - a parameter a component's form does not have, which the .pscx carried and PSCAD ignored
        Files.write(kept.resolve("warnings.txt"), compiled.warnings());
        for (String file : sources) {
            // Kept, normalized, for whoever has to see a difference: target/equivalence/<case>/
            String expected = normalized(reference.resolve(file), pscxDir);
            Files.writeString(kept.resolve("pscx-" + file), expected);
            // The project's top page: its Station in a .pscx, DS ("default station") in a project the library creates
            String builtFile = file.startsWith(STATION) ? DEFAULT_STATION + file.substring(STATION.length()) : file;
            if (!Files.exists(built.resolve(builtFile)) && file.endsWith(".dta") && !holdsData(reference.resolve(file))) {
                // A station's data file with nothing in it, which only a project PSCAD loads is given
                continue;
            }
            assertThat(built.resolve(builtFile)).as("%s of %s", builtFile, equivalenceCase).exists();
            String actual = normalized(built.resolve(builtFile), scriptDir);
            Files.writeString(kept.resolve("script-" + file), actual);
            assertThat(actual).as("%s of %s", file, equivalenceCase).isEqualTo(expected);
        }
    }

    /** Whether a data file holds any data: anything but comments, its sections' headings and its end. */
    private static boolean holdsData(Path dta) throws IOException {
        return Files.readAllLines(dta, StandardCharsets.ISO_8859_1).stream().map(String::strip)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith("!") && !line.endsWith(":") && !"ENDPAGE".equals(line));
    }

    /** A build's file, the folder it was built in and the instant it was built at left out. */
    private static String normalized(Path file, Path projectDir) throws IOException {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        return PscadBuildNormalizer.normalize(text, projectDir);
    }
}
