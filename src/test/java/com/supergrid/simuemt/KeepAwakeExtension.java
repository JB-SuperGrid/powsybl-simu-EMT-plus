/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Keeps Windows awake while the tests run: on 2026-11-18 a full {@code -Ppscad,emtp} run lost 8 h 19 to one EMTP run, frozen from 00:06
 * to 08:25 by the PC's modern standby - Kernel-Power's events 506 and 507 to the second. Once per test JVM, a PowerShell process of its
 * own holds {@code SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED)} - the display too: under modern
 * standby the system goes to sleep as its display goes off - and lets go when the JVM ends, its parent gone. A lid closed or a power
 * button pressed still put it to sleep. Nothing elsewhere than on Windows.
 *
 * <p>Registered for every test ({@code META-INF/services/org.junit.jupiter.api.extension.Extension}, its autodetection on in {@code
 * junit-platform.properties}).
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public class KeepAwakeExtension implements BeforeAllCallback {

    /** ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED. */
    static final String EXECUTION_STATE = "0x80000003";

    private static Process guard;

    /** The guard process, if started: {@code null} elsewhere than on Windows. */
    static synchronized Process guard() {
        return guard;
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        start();
    }

    /** The guard, started once - nothing elsewhere than on Windows, nor if PowerShell cannot be started. */
    static synchronized void start() {
        if (guard != null || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        try {
            // Encoded, as PowerShell takes it whole: no quoting of the script's own quotes on Windows' command line.
            String encoded = Base64.getEncoder().encodeToString(script(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_16LE));
            guard = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            System.err.println("KeepAwakeExtension: could not keep Windows awake - " + e.getMessage());
        }
    }

    /** The guard's script: the execution state held by its own thread until process {@code pid} ends - out at once, 1, if Windows
     * refuses it. */
    static String script(long pid) {
        return "Add-Type -Name P -Namespace W -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);';"
                + " if ([W.P]::SetThreadExecutionState([uint32]" + EXECUTION_STATE + ") -eq 0) { exit 1 };"
                + " Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue";
    }
}
