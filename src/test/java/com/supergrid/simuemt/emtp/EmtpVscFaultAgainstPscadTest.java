/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.supergrid.simuemt.pscadpro.PscadProSettings;
import com.supergrid.simuemt.pscadpro.PscadProSimulation;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * The converters through a fault at B2, VSC2's bus - the one holding the DC voltage -, through 5 ohm for 100 ms, run in EMTP from its
 * steady state and in PSCAD Professional from rest, the fault 2 s later there, once its converters are let go and settled. Each
 * converter's measured P, Q, AC and DC voltages - the same lag on both sides, on the positive sequence once the control separates
 * them - held to PSCAD's window by window, from 50 ms before the fault to 400 ms after. Every window written to {@code
 * target/emtp/<case>-against-pscad.csv}.
 *
 * <p>Step 1's: {@code vsc-monopole-infinite-buses-2}, single-sequence controls, through a three-phase fault to earth, by 5 ms windows.
 * Step 2's: {@code vsc-monopole-infinite-buses}, its controls separating the sequences (DDSRF, its PLL on the positive sequence, a
 * current loop per sequence suppressing the negative one), through a fault of phase a to earth, by 10 ms windows - a period of the
 * twice-frequency ripple the unbalance leaves on the powers.
 *
 * <p>Run 2026-11-14, 40 s the two: VSC2's AC voltage down to 0.204 pu in both, its power from 0.29 to -1.09 pu and its reactive power
 * to -1.04 pu, its DC voltage up to 1.080 pu; settled, every channel within 2.3e-4 pu of PSCAD's (VSC2's Q), through the fault and the
 * recovery within 1.1e-2 pu (VSC2's Q), 7.4e-3 (VSC2's P), the DC voltages 4.8e-4. The unbalanced fault, 73 s the two cases: VSC2's
 * positive-sequence voltage down to 0.692 pu in both, past its ride-through's 0.9, its power from -0.63 to -0.96 pu; settled within
 * 1.0e-4 pu, through the fault within 2.4e-3 pu (VSC2's P), 1.5e-3 (VSC2's AC voltage), the DC voltages 2.2e-4.
 *
 * <p>Tagged {@code emtp} and {@code pscad}: run with {@code mvn test -Pemtp,pscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("emtp")
@Tag("pscad")
class EmtpVscFaultAgainstPscadTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double EMTP_FAULT = 0.3;
    /** PSCAD runs from rest: its converters let go at 2 s, the fault 2 s on from EMTP's. */
    private static final double PSCAD_SHIFT = 2.0;
    private static final double DURATION = 0.1;
    private static final double OHMS = 5.0;
    private static final List<String> CHANNELS = List.of("VSC1_P", "VSC1_Q", "VSC1_VAC", "VSC1_VDC", "VSC2_P", "VSC2_Q", "VSC2_VAC", "VSC2_VDC");
    /** Per channel, pu: before the fault and in the last 100 ms, then through the fault and the recovery. */
    private static final double SETTLED_TOLERANCE = 1.0e-3;
    private static final double TRANSIENT_TOLERANCE = 0.02;

    @Test
    void aFaultAtB2RunsInEmtpAsInPscadProfessional() throws IOException {
        compare("vsc-monopole-infinite-buses-2", "ABC", "vsc-fault", 5.0e-3, SETTLED_TOLERANCE, TRANSIENT_TOLERANCE);
    }

    @Test
    void anUnbalancedFaultAtB2RunsInEmtpAsInPscadProfessionalThroughTheSequences() throws IOException {
        compare("vsc-monopole-infinite-buses", "A", "vsc-unbalanced-fault", 10.0e-3, SETTLED_TOLERANCE, TRANSIENT_TOLERANCE);
    }

    private static void compare(String bench, String phases, String name, double window, double settledTolerance, double transientTolerance)
            throws IOException {
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(EmtpVoltageSourceConverterTest.bench(bench), Path.of("target", "emtp", name),
                new EmtpSimulationSettings(TIME_STEP, EMTP_FAULT + 0.45, FREQUENCY), List.of(fault(phases, EMTP_FAULT))), EmtpSettings.defaults()).output();
        double pscadFault = EMTP_FAULT + PSCAD_SHIFT;
        PscadOutput pscad = PscadProSimulation.run(EmtpVoltageSourceConverterTest.bench(bench), Path.of("target", "pscad", name),
                new PscadSimulationSettings(TIME_STEP, pscadFault + 0.45, FREQUENCY, PscadEdition.PROFESSIONAL), List.of(fault(phases, pscadFault)),
                PscadProSettings.defaults()).output();

        double[] worstSettled = new double[CHANNELS.size()];
        double[] worstTransient = new double[CHANNELS.size()];
        double fault = NumericalAids.switchingInstant(EMTP_FAULT, TIME_STEP);
        try (Writer csv = Files.newBufferedWriter(Path.of("target", "emtp", name + "-against-pscad.csv"))) {
            csv.write("t," + String.join(",", CHANNELS.stream().flatMap(c -> java.util.stream.Stream.of(c + "_EMTP", c + "_PSCAD")).toList()) + "\n");
            for (double t = fault - 0.05; t + window <= EMTP_FAULT + 0.45 + 1.0e-9; t += window) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.3f", t - fault));
                boolean settled = t + window <= fault || t >= EMTP_FAULT + 0.35;
                for (int k = 0; k < CHANNELS.size(); k++) {
                    double e = emtp.mean("control:" + CHANNELS.get(k), t, t + window);
                    double p = pscad.mean(CHANNELS.get(k), t + PSCAD_SHIFT, t + window + PSCAD_SHIFT);
                    line.append(String.format(Locale.ROOT, ",%.5f,%.5f", e, p));
                    double[] worst = settled ? worstSettled : worstTransient;
                    worst[k] = Math.max(worst[k], Math.abs(e - p));
                }
                csv.write(line + "\n");
            }
        }
        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < CHANNELS.size(); k++) {
            System.out.printf(Locale.ROOT, "%s, %s: settled within %.2e pu, through the fault %.2e pu of PSCAD's%n", name, CHANNELS.get(k), worstSettled[k],
                    worstTransient[k]);
            softly.assertThat(worstSettled[k]).as("%s, %s settled against PSCAD's, pu", name, CHANNELS.get(k)).isCloseTo(0.0, within(settledTolerance));
            softly.assertThat(worstTransient[k]).as("%s, %s through the fault against PSCAD's, pu", name, CHANNELS.get(k))
                    .isCloseTo(0.0, within(transientTolerance));
        }
        softly.assertAll();
    }

    private static BusFault fault(String phases, double start) {
        return new BusFault("F", "B2", phases, true, OHMS, start, start + DURATION, FaultClearing.AT_CURRENT_ZERO);
    }
}
