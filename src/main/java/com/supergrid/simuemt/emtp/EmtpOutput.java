/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import com.powsybl.simuemt.extensions.output.AbstractTimeStepTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scopes of an EMTP time-domain run, from its main plot file ({@code <name>m.m}) and the binary file it points to ({@code
 * <name>.mda}) - EMTP's {@code io_files.pdf}, section 3.
 *
 * <p>The plot file lists the scopes by group, each group a kind of scope: {@code time}, {@code vn} a node's voltage, {@code vb} and
 * {@code ib} a branch's voltage and current, {@code is} a switch's current, and so on - as {@code n_<g>_scopes=N;}, the names {@code
 * N<g>='first';} then {@code N<g>=strvcat(N<g>,'next');}, and the columns {@code <g>=a:1:b;}. A column is named here {@code
 * <g>:<scope>} - {@code vb:C1}, {@code ib:C1} -, a device having scopes of several kinds; the time column {@code TIME}.
 *
 * <p>The binary file is Fortran's sequential unformatted output: one record per time point, its {@code float64} values - the time,
 * then each scope - between two 4-byte lengths. Its time points are the run's steps and, where EMTP takes a half step to restart after
 * a switching, those too (half a step past the start, past a switch's closing, at a current zero): every one strictly after the last.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public final class EmtpOutput extends AbstractTimeStepTable {

    private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*(\\w+)\\s*=\\s*(.*?)\\s*;\\s*$");
    private static final Pattern FIRST_NAME = Pattern.compile("^'(.*)'$");
    private static final Pattern NEXT_NAME = Pattern.compile("^strvcat\\(\\s*(\\w+)\\s*,\\s*'(.*)'\\s*\\)$");
    private static final Pattern COLUMNS = Pattern.compile("^(\\d+):1:(\\d+)$");
    private static final String TIME_GROUP = "time";

    private final List<String> columnNames;

    private EmtpOutput(String[] columnNames, double[][] rows) {
        super(columnNames, rows);
        this.columnNames = List.of(columnNames);
    }

    /** The run of netlist {@code name} whose outputs are in its project folder {@code projectDirectory}. */
    public static EmtpOutput read(Path projectDirectory, String name) {
        Path plot = projectDirectory.resolve(name + "m.m");
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String line : lines(plot)) {
            Matcher assignment = ASSIGNMENT.matcher(line);
            if (!assignment.matches()) {
                continue;
            }
            String variable = assignment.group(1);
            String value = assignment.group(2);
            Matcher first = FIRST_NAME.matcher(value);
            Matcher next = NEXT_NAME.matcher(value);
            if (variable.startsWith("N") && first.matches()) {
                names.put(variable.substring(1), new ArrayList<>(List.of(first.group(1))));
            } else if (variable.startsWith("N") && next.matches() && next.group(1).equals(variable) && names.containsKey(variable.substring(1))) {
                names.get(variable.substring(1)).add(next.group(2));
            } else {
                values.put(variable, value);
            }
        }
        if (!values.containsKey("t") || values.containsKey("f") || values.containsKey("statistical")) {
            throw new PowsyblException("SimuEmt Plus: " + plot + " is not a time-domain plot file");
        }
        if (!"'float64'".equals(values.get("precision"))) {
            throw new PowsyblException("SimuEmt Plus: " + plot + " records its scopes in " + values.get("precision") + ", not float64");
        }
        int scopeCount = Integer.parseInt(values.get("n_scopes"));
        String[] columnNames = new String[scopeCount];
        for (Map.Entry<String, List<String>> group : names.entrySet()) {
            String key = group.getKey();
            Matcher columns = COLUMNS.matcher(values.getOrDefault(key, ""));
            if (!columns.matches()) {
                throw new PowsyblException("SimuEmt Plus: " + plot + " gives scope group " + key + " no columns");
            }
            int from = Integer.parseInt(columns.group(1));
            int to = Integer.parseInt(columns.group(2));
            if (to - from + 1 != group.getValue().size() || from < 1 || to > scopeCount) {
                throw new PowsyblException("SimuEmt Plus: " + plot + " puts " + group.getValue().size() + " scopes of group " + key
                        + " in columns " + from + " to " + to + " of " + scopeCount);
            }
            for (int k = 0; k < group.getValue().size(); k++) {
                columnNames[from - 1 + k] = TIME_GROUP.equals(key) ? "TIME" : key + ":" + group.getValue().get(k);
            }
        }
        for (int c = 0; c < scopeCount; c++) {
            if (columnNames[c] == null) {
                throw new PowsyblException("SimuEmt Plus: " + plot + " names no scope in column " + (c + 1));
            }
        }
        if (!"TIME".equals(columnNames[0])) {
            throw new PowsyblException("SimuEmt Plus: " + plot + " does not record the time first");
        }
        String binary = values.getOrDefault("filn", "'" + name + ".mda'");
        double[][] rows = readRecords(projectDirectory.resolve(binary.substring(1, binary.length() - 1)), scopeCount);
        return new EmtpOutput(columnNames, rows);
    }

    /** The columns, {@code TIME} first, then {@code <group>:<scope>} in the plot file's order. */
    public List<String> columnNames() {
        return columnNames;
    }

    /** Every record of {@code mda}, each {@code width} {@code float64}s between two lengths of {@code 8 width} bytes. */
    static double[][] readRecords(Path mda, int width) {
        ByteBuffer buffer;
        try {
            buffer = ByteBuffer.wrap(Files.readAllBytes(mda)).order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not read " + mda, e);
        }
        int recordBytes = 8 * width;
        if (buffer.remaining() % (recordBytes + 8) != 0) {
            throw new PowsyblException("SimuEmt Plus: " + mda + " holds " + buffer.remaining() + " bytes, not whole records of " + width + " values");
        }
        double[][] rows = new double[buffer.remaining() / (recordBytes + 8)][];
        for (int r = 0; r < rows.length; r++) {
            int head = buffer.getInt();
            double[] row = new double[width];
            for (int c = 0; c < width; c++) {
                row[c] = buffer.getDouble();
            }
            int tail = buffer.getInt();
            if (head != recordBytes || tail != recordBytes) {
                throw new PowsyblException("SimuEmt Plus: record " + (r + 1) + " of " + mda + " is framed by " + head + " and " + tail
                        + " bytes, not " + recordBytes);
            }
            if (r > 0 && row[0] <= rows[r - 1][0]) {
                throw new PowsyblException("SimuEmt Plus: record " + (r + 1) + " of " + mda + " is at " + row[0] + " s, not after " + rows[r - 1][0]);
            }
            rows[r] = row;
        }
        return rows;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("SimuEmt Plus: could not read " + file, e);
        }
    }
}
