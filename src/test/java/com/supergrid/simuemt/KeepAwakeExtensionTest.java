/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeepAwakeExtension}, autodetected: on Windows its guard started before this class's tests, holding the execution state - still
 * running seconds on, where it leaves at once if Windows refuses it - and once only.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class KeepAwakeExtensionTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theGuardHoldsWindowsAwakeWhileTheTestsRun() throws InterruptedException {
        Process guard = KeepAwakeExtension.guard();
        assertThat(guard).as("the guard, started by the autodetected extension").isNotNull();
        assertThat(guard.waitFor(5, TimeUnit.SECONDS)).as("the guard gone within 5 s: Windows refused the execution state").isFalse();
        KeepAwakeExtension.start();
        assertThat(KeepAwakeExtension.guard()).as("started once only").isSameAs(guard);
    }
}
