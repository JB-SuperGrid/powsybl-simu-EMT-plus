/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.simuemt.pscad.PscadCircuit;
import com.powsybl.simuemt.pscad.PscadDrawing;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadRun;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes a PSCAD project as a Python script driving PSCAD's automation library ({@code mhi.pscad}) - what PSCAD Professional
 * has and Free has not - in place of the {@code .pscx} file: {@code <name>.py}, which launches PSCAD, builds the project
 * {@code <name>.pscx} beside itself, compiles it and runs it.
 *
 * <p>The script builds exactly what the {@code .pscx} writer writes: the same {@link PscadDrawing}, each component of it
 * created at the same point with the same parameters, its labels on the same ports. Its positions are PSCAD's canvas units
 * divided by {@link PscadDrawing#GRID}, the grid units the library counts in. Its parameters are set as PSCAD's form holds
 * them, past the library's codec - {@code "2.0 [ohm]"}, a choice's index - as a {@code .pscx} carries them. The project's own
 * definitions, its transmission lines and its Fortran components, are created from the same XML a {@code .pscx} holds them in.
 *
 * <p>The project's settings go through the library's own names: {@code StartType} {@code FROM_SNAP_FILE} for a restart,
 * {@code SnapType} {@code ONLY_ONCE} for a template, and the three switching boxes of {@link PscadSwitching} as the flags
 * {@code interpolate_switching}, {@code detect_chatter} and {@code remove_chatter}.
 *
 * <p>The script reports what it did in {@code <name>.result} beside itself, one {@code key=value} per line: {@code status}
 * ({@code ran}, {@code built}, {@code build_failed} or {@code failed}), {@code build_folder} - where EMTDC writes its output,
 * named after the compiler - an {@code error} line per error PSCAD reported, and a {@code warning} line per parameter left out
 * because the component's form has none of that name (PSCAD leaves such a parameter out of a {@code .pscx} it loads). {@link PscadProRunner} reads it.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class PscadPythonScriptWriter implements PscadProjectSerializer {

    public static final PscadPythonScriptWriter INSTANCE = new PscadPythonScriptWriter();

    /** The file the script reports in, beside it. */
    public static String resultFileName(String projectName) {
        return projectName + ".result";
    }

    private PscadPythonScriptWriter() {
    }

    @Override
    public Path write(Path outputDir, PscadCircuit circuit, PscadSimulationSettings settings, PscadRun run, String description) throws IOException {
        Path file = outputDir.resolve(circuit.name() + ".py");
        Files.writeString(file, script(PscadDrawing.of(circuit), settings, run, description), StandardCharsets.UTF_8);
        return file;
    }

    /** The script for {@code drawing}. */
    static String script(PscadDrawing drawing, PscadSimulationSettings settings, PscadRun run, String description) {
        StringBuilder py = new StringBuilder();
        py.append("# -*- coding: utf-8 -*-\n")
                .append("# ").append(oneLine(description)).append(" - SimuEmt Plus, SuperGrid Institute.\n")
                .append("#\n")
                .append("# Builds the PSCAD project ").append(drawing.projectName()).append(" through PSCAD's automation library (mhi.pscad,\n")
                .append("# PSCAD Professional), then compiles and runs it. Run it with the Python mhi.pscad is installed in:\n")
                .append("#\n")
                .append("#     python ").append(drawing.projectName()).append(".py [--no-run] [--fortran \"<compiler>\"] [--version 5.1.0]\n")
                .append("#\n")
                .append("# What it did is in ").append(resultFileName(drawing.projectName())).append(" beside it: status, build_folder, errors.\n")
                .append("import argparse\nimport os\nimport re\nimport sys\n\nimport mhi.pscad\n\n")
                .append("TOOL_ERROR = re.compile(r\"fatal error|error [A-Z]+\\d+|error #\\d+|: error\")\n")
                .append("NAME = ").append(literal(drawing.projectName())).append('\n')
                .append("HERE = os.path.dirname(os.path.abspath(__file__))\n")
                .append("GRID = ").append(PscadDrawing.GRID).append('\n')
                .append("PAGE_SIZE = ").append(drawing.pageSize()).append("\n\n");
        appendSettings(py, settings, run, description);
        appendDefinitions(py, drawing);
        appendHelpers(py);
        appendDrawing(py, drawing);
        appendMain(py);
        return py.toString();
    }

    private static void appendSettings(StringBuilder py, PscadSimulationSettings settings, PscadRun run, String description) {
        boolean pscadOwn = settings.switching() == PscadSwitching.INTERPOLATED;
        String step = number(settings.timeStepSeconds() * 1.0e6);
        py.append("# The run: its length and step (us), where it starts and the snapshot it leaves, how EMTDC switches.\n")
                .append("SETTINGS = {\n")
                .append("    \"time_duration\": ").append(number(run.durationSeconds())).append(",\n")
                .append("    \"time_step\": ").append(step).append(",\n")
                .append("    \"sample_step\": ").append(step).append(",\n")
                .append("    \"PlotType\": \"OUT\",\n")
                .append("    \"output_filename\": ").append(literal("$(Namespace).out")).append(",\n")
                .append("    \"StartType\": ").append(literal(run.fromSnapshot() ? "FROM_SNAP_FILE" : "STANDARD")).append(",\n");
        if (run.fromSnapshot()) {
            py.append("    \"startup_filename\": ").append(literal(run.startupFile())).append(",\n");
        }
        py.append("    \"SnapType\": ").append(literal(run.writesSnapshot() ? "ONLY_ONCE" : "NONE")).append(",\n")
                .append("    \"SnapTime\": ").append(number(run.writesSnapshot() ? run.snapshotTimeSeconds() : 0.3)).append(",\n")
                .append("    \"snapshot_filename\": ").append(literal("$(Namespace).snp")).append(",\n")
                .append("    \"interpolate_switching\": ").append(pscadOwn ? "True" : "False").append(",\n")
                .append("    \"detect_chatter\": ").append(pscadOwn ? "True" : "False").append(",\n")
                .append("    \"remove_chatter\": ").append(pscadOwn ? "True" : "False").append(",\n")
                .append("    \"description\": ").append(literal(description)).append(",\n")
                .append("}\n\n");
    }

    private static void appendDefinitions(StringBuilder py, PscadDrawing drawing) {
        py.append("# The project's own definitions - its transmission lines and its Fortran components - as a .pscx holds them.\n")
                .append("DEFINITIONS = [\n");
        int[] next = {1};
        for (PscadDrawing.Definition definition : drawing.definitions()) {
            py.append("    (").append(literal(definition.name())).append(", (\n");
            String xml = definition.xml(() -> next[0]++);
            for (String line : xml.split("(?<=\n)")) {
                py.append("        ").append(literal(line)).append('\n');
            }
            py.append("    )),\n");
        }
        py.append("]\n\n");
    }

    private static void appendHelpers(StringBuilder py) {
        py.append("""
                WARNINGS = []

                def place(canvas, definition, x, y, parameters):
                    \"\"\"A component at (x, y), canvas units; its parameters as PSCAD's form holds them, past the codec.

                    A parameter the component's form does not have is left out, as PSCAD leaves it out of a .pscx it
                    loads - and reported as a warning, where the .pscx would have said nothing.\"\"\"
                    component = canvas.create_component(definition, x // GRID, y // GRID)
                    if component is None:
                        raise RuntimeError(f"PSCAD created no {definition} at ({x}, {y})")
                    if parameters:
                        known = component.parameters().keys()
                        unknown = [name for name in parameters if name not in known]
                        if unknown:
                            WARNINGS.append(f"{definition} at ({x}, {y}): no parameter {', '.join(unknown)} in its form, left out")
                        component._parameters(None, -1, {name: value for name, value in parameters.items() if name in known})
                    return component

                def transmission_line(canvas, definition, x, y, span, parameters):
                    \"\"\"A transmission line's instance, a straight run from (x, y) to (x + span, y).\"\"\"
                    line = place(canvas, definition, x, y, parameters)
                    line.vertices((x // GRID, y // GRID), ((x + GRID) // GRID, y // GRID),
                                  ((x + span - GRID) // GRID, y // GRID), ((x + span) // GRID, y // GRID))
                    return line

                """);
    }

    private static void appendDrawing(StringBuilder py, PscadDrawing drawing) {
        py.append("def draw(canvas):\n")
                .append("    \"\"\"The circuit, as PscadDrawing lays it out.\"\"\"\n");
        boolean empty = true;
        for (PscadDrawing.Element element : drawing.elements()) {
            switch (element) {
                case PscadDrawing.Comment comment -> py.append("    # ").append(oneLine(comment.text())).append('\n');
                case PscadDrawing.Placed placed -> {
                    py.append("    place(canvas, ").append(literal(placed.definition())).append(", ").append(placed.x()).append(", ")
                            .append(placed.y()).append(", ").append(dict(placed.parameters())).append(")\n");
                    empty = false;
                }
                case PscadDrawing.TransmissionLine line -> {
                    py.append("    transmission_line(canvas, ").append(literal(drawing.projectName() + ":" + line.name())).append(", ")
                            .append(line.x()).append(", ").append(line.y()).append(", ").append(line.span()).append(", ")
                            .append(dict(line.parameters())).append(")\n");
                    empty = false;
                }
            }
        }
        if (empty) {
            py.append("    pass\n");
        }
        py.append("\n\n");
    }

    private static void appendMain(StringBuilder py) {
        py.append("""
                def report(status, build_folder, errors):
                    with open(os.path.join(HERE, NAME + ".result"), "w", encoding="utf-8") as result:
                        result.write(f"status={status}\\n")
                        result.write(f"build_folder={build_folder}\\n")
                        for error in errors:
                            result.write("error=" + " ".join(str(error).split()) + "\\n")
                        for warning in WARNINGS:
                            result.write("warning=" + " ".join(str(warning).split()) + "\\n")

                def main():
                    parser = argparse.ArgumentParser(description="Builds and runs PSCAD project " + NAME)
                    parser.add_argument("--no-run", action="store_true", help="build the project, do not run it")
                    parser.add_argument("--fortran", help="the Fortran compiler, as PSCAD's settings name it")
                    parser.add_argument("--version", default="5.1.0", help="the PSCAD version to launch")
                    parser.add_argument("--visible", action="store_true", help="leave PSCAD's window up")
                    args = parser.parse_args()

                    status, build_folder, errors = "failed", "", []
                    report(status, build_folder, ["PSCAD did not start"])
                    pscad = mhi.pscad.launch(minimize=not args.visible, splash=False, version=args.version)
                    try:
                        if not pscad.licensed():
                            raise RuntimeError("PSCAD has no licence: the automation library needs Professional or Educational")
                        if args.fortran:
                            pscad.settings(fortran_version=args.fortran)
                        path = os.path.join(HERE, NAME + ".pscx")
                        if os.path.exists(path):
                            os.remove(path)
                        project = pscad.create_case(path)
                        for _, xml in DEFINITIONS:
                            project.create_definition(xml)
                        project.definition("Main").parameters(desc="Main Page")  # as a .pscx has it
                        canvas = project.canvas("Main")
                        canvas.parameters(size=PAGE_SIZE)
                        draw(canvas)
                        project.parameters(**SETTINGS)
                        project.save()
                        project.build()
                        build_folder = project.temp_folder
                        messages = project.messages()
                        errors = [m.text for m in messages if str(m.status).lower() == "error"]
                        # The compiler's and the linker's own, which PSCAD passes on as plain output: the cause, where
                        # PSCAD's error only says no executable was made.
                        errors += [m.text for m in messages if str(m.status).lower() != "error" and TOOL_ERROR.search(m.text)]
                        if errors:
                            status = "build_failed"
                        elif args.no_run:
                            status = "built"
                        else:
                            project.run()
                            status = "ran"
                    except Exception as exception:  # reported, not raised: the caller reads the result file
                        errors.append(repr(exception))
                    finally:
                        report(status, build_folder, errors)
                        pscad.quit()
                    return 0 if status in ("built", "ran") else 1

                if __name__ == "__main__":
                    sys.exit(main())
                """);
    }

    private static String dict(Map<String, String> parameters) {
        StringBuilder d = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            d.append(first ? "" : ", ").append(literal(parameter.getKey())).append(": ").append(literal(parameter.getValue()));
            first = false;
        }
        return d.append('}').toString();
    }

    /** A Python string literal holding {@code text}. */
    static String literal(String text) {
        StringBuilder s = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> s.append("\\\\");
                case '"' -> s.append("\\\"");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        s.append(String.format("\\x%02x", (int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        return s.append('"').toString();
    }

    /** A Python float literal. */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("SimuEmt Plus: no Python literal for " + value);
        }
        return Double.toString(value);
    }

    private static String oneLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }
}
