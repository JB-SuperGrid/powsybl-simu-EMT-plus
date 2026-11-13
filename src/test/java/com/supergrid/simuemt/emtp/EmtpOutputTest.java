/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.commons.PowsyblException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * EMTP's outputs of a run read back, no EMTP needed: {@code emtp/simple-short/}, EMTPWorks' {@code simple} example's netlist (a 69 kV
 * 60 Hz source feeding two RL branches, each to a capacitor, a fault switch on one of them from 10 ms) run by {@code emtpopt.exe} on
 * 2026-11-13 at 10 us for 50 ms - its plot file, binary file and steady-state solution kept as EMTP wrote them.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class EmtpOutputTest {

    private static final double OMEGA = 2.0 * Math.PI * 60.0;

    @Test
    void readsEveryScopeUnderItsGroupAndTheTimeFirst() throws URISyntaxException {
        EmtpOutput output = EmtpOutput.read(project(), "simple_short");

        assertThat(output.columnNames()).containsExactly("TIME", "is:SWfault", "vb:C1", "ib:C1");
        // 5001 steps of 10 us, and the half steps EMTP restarts on: past the start, past the switch's closing.
        assertThat(output.sampleCount()).isBetween(5001, 5010);
        assertThat(output.curves()).containsOnlyKeys("is:SWfault", "vb:C1", "ib:C1");
    }

    @Test
    void startsFromTheSteadyStateItsSolutionGives() throws URISyntaxException {
        EmtpOutput output = EmtpOutput.read(project(), "simple_short");
        EmtpSteadyState steadyState = EmtpSteadyState.read(project(), "simple_short");

        assertThat(steadyState.frequencyHz()).isEqualTo(60.0);
        assertThat(steadyState.voltages()).containsOnlyKeys("SOURCE", "s5", "s6");
        EmtpSteadyState.Phasor s5 = steadyState.voltage("s5").orElseThrow();
        assertThat(s5.magnitude()).isCloseTo(56798.25289, within(1.0e-4));
        // C1, from s5 to earth, at its steady state until the fault at 10 ms: its first sample m cos(p) - to the phasor's ten printed
        // digits -, its crest m, its current's w C m (i = C dv/dt) - sampled every 10 us, within 2e-6 of the crest.
        assertThat(output.mean("vb:C1", 0.0, 0.0)).isCloseTo(s5.magnitude() * Math.cos(Math.toRadians(s5.angleDegrees())), within(1.0e-4));
        assertThat(output.peakAbsolute("vb:C1", 0.0, 0.0099) / s5.magnitude() - 1.0).isCloseTo(0.0, within(1.0e-5));
        assertThat(output.peakAbsolute("ib:C1", 0.0, 0.0099) / (OMEGA * 0.57e-6 * s5.magnitude()) - 1.0).isCloseTo(0.0, within(1.0e-5));
    }

    @Test
    void theFaultShortsTheCapacitorFromItsClosing() throws URISyntaxException {
        EmtpOutput output = EmtpOutput.read(project(), "simple_short");

        assertThat(output.peakAbsolute("is:SWfault", 0.0, 0.0099)).isZero();
        assertThat(output.peakAbsolute("is:SWfault", 0.0101, 0.05)).isGreaterThan(100.0);
        assertThat(output.peakAbsolute("vb:C1", 0.02, 0.05)).isLessThan(1.0e-3 * output.peakAbsolute("vb:C1", 0.0, 0.0099));
    }

    @Test
    void refusesARecordFramedOtherwise(@TempDir Path tempDir) throws IOException, URISyntaxException {
        Files.copy(project().resolve("simple_shortm.m"), tempDir.resolve("simple_shortm.m"));
        byte[] binary = Files.readAllBytes(project().resolve("simple_short.mda"));
        ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 24);
        Files.write(tempDir.resolve("simple_short.mda"), binary);

        assertThatThrownBy(() -> EmtpOutput.read(tempDir, "simple_short")).isInstanceOf(PowsyblException.class)
                .hasMessageContaining("record 2").hasMessageContaining("framed by 24");
    }

    @Test
    void refusesAPlotFileOfAFrequencyScan(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("scanm.m"), "filn='scan.mda';\nf=1;\nprecision='float64';\nn_scopes=1;\n");

        assertThatThrownBy(() -> EmtpOutput.read(tempDir, "scan")).isInstanceOf(PowsyblException.class).hasMessageContaining("not a time-domain plot file");
    }

    /** The stored run's project folder. */
    static Path project() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(EmtpOutputTest.class.getResource("/emtp/simple-short/simple_short_pj/simple_shortm.m")).toURI()).getParent();
    }
}
