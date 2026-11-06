/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * What of a PSCAD build's files ({@code Main.f}, {@code Main.dta}, its station's) says where, when and through which route
 * it was built rather than what: left out, so that two builds of one circuit compare equal.
 *
 * <ul>
 *     <li>the folder it was built in;</li>
 *     <li>each component's id, which PSCAD numbers its own way for a project created through its automation library -
 *     every call it is passed to with {@code ICALL_NO} - {@code COMPONENT_ID}, messages, warnings, the faults' logic;</li>
 *     <li>the Fortran continuation lines those ids' lengths move, joined back into one;</li>
 *     <li>the project's top page's name, its {@code Station} in a {@code .pscx}, {@code DS} ("default station") in a project
 *     the library creates - in the station's own files, its name and its data file's.</li>
 * </ul>
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class PscadBuildNormalizer {

    private static final Pattern LINE_ENDS = Pattern.compile("\r\n?");
    /** A free-form continuation: a line ending in {@code &}, the next one starting with blanks and {@code &}. */
    private static final Pattern CONTINUATION = Pattern.compile("&\n {5}&");
    private static final Pattern COMPONENT_IDS = Pattern.compile("\\(ICALL_NO,\\d+");
    private static final Pattern STATION = Pattern.compile("(?m)^(! Component {5}: )(?:Station|DS)$|'(?:Station|DS)\\.dta'");

    private PscadBuildNormalizer() {
    }

    static String normalize(String text, Path projectDir) {
        String normalized = LINE_ENDS.matcher(text).replaceAll("\n");
        for (String dir : new String[] {projectDir.toAbsolutePath().toString(), projectDir.toAbsolutePath().toString().replace('\\', '/')}) {
            normalized = normalized.replace(dir, "<project dir>");
        }
        normalized = CONTINUATION.matcher(normalized).replaceAll("");
        normalized = COMPONENT_IDS.matcher(normalized).replaceAll("(ICALL_NO,<id>");
        return STATION.matcher(normalized).replaceAll(m -> m.group(1) != null ? m.group(1) + "<station>" : "'<station>.dta'");
    }
}
