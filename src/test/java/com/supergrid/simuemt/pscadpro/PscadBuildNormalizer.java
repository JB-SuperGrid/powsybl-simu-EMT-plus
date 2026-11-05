/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * What of a PSCAD build's files ({@code Main.f}, {@code Main.dta}) says where, when and through which route it was built
 * rather than what: left out, so that two builds of one circuit compare equal.
 *
 * <ul>
 *     <li>the folder it was built in;</li>
 *     <li>each component's id, which PSCAD numbers its own way for a project created through its automation library -
 *     {@code COMPONENT_ID}, and the messages and warnings it tags with it;</li>
 *     <li>the Fortran continuation lines those ids' lengths move, joined back into one;</li>
 *     <li>the {@code Main} page's description, cosmetic.</li>
 * </ul>
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class PscadBuildNormalizer {

    private static final Pattern LINE_ENDS = Pattern.compile("\r\n?");
    /** A free-form continuation: a line ending in {@code &}, the next one starting with blanks and {@code &}. */
    private static final Pattern CONTINUATION = Pattern.compile("&\n {5}&");
    private static final Pattern COMPONENT_IDS = Pattern.compile("(COMPONENT_ID|EMTDC_MESSAGE|EMTDC_WARN|EMTDC_ERROR)\\(ICALL_NO,\\d+");
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^! Description {3}:.*$");

    private PscadBuildNormalizer() {
    }

    static String normalize(String text, Path projectDir) {
        String normalized = LINE_ENDS.matcher(text).replaceAll("\n");
        for (String dir : new String[] {projectDir.toAbsolutePath().toString(), projectDir.toAbsolutePath().toString().replace('\\', '/')}) {
            normalized = normalized.replace(dir, "<project dir>");
        }
        normalized = CONTINUATION.matcher(normalized).replaceAll("");
        normalized = COMPONENT_IDS.matcher(normalized).replaceAll("$1(ICALL_NO,<id>");
        return DESCRIPTION.matcher(normalized).replaceAll("! Description   : <description>");
    }
}
