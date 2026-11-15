/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.ConverterDeblocking;
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.DcSwitchOperation;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.supergrid.simuemt.pscadpro.PscadProSettings;
import com.supergrid.simuemt.pscadpro.PscadProSimulation;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

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
    /** A grid-forming bench's PSCAD shift: from rest, its virtual machine settles in 4.5 s. */
    private static final double GRID_FORMING_SHIFT = 4.5;
    private static final double OHMS = 5.0;
    private static final List<Channel> MONOPOLE = converters("VSC1", "VSC2");
    /** Per channel, pu: before the fault and in the last 100 ms, then through the fault and the recovery. */
    private static final double SETTLED_TOLERANCE = 1.0e-3;
    private static final double TRANSIENT_TOLERANCE = 0.02;

    /** A channel compared: its EMTP scope, PSCAD's, the base both are divided by. */
    record Channel(String label, String emtp, String pscad, double base) {
    }

    @Test
    void aFaultAtB2RunsInEmtpAsInPscadProfessional() throws IOException {
        compare("vsc-monopole-infinite-buses-2", start -> List.of(fault("ABC", start)), MONOPOLE, "vsc-fault", 5.0e-3, SETTLED_TOLERANCE,
                TRANSIENT_TOLERANCE, true);
    }

    @Test
    void anUnbalancedFaultAtB2RunsInEmtpAsInPscadProfessionalThroughTheSequences() throws IOException {
        compare("vsc-monopole-infinite-buses", start -> List.of(fault("A", start)), MONOPOLE, "vsc-unbalanced-fault", 10.0e-3, SETTLED_TOLERANCE,
                TRANSIENT_TOLERANCE, true);
    }

    /**
     * Step 3's: {@code vsc-three-terminal-dc-breaker}, its three stations on their DC voltage droops, DC2L - DCL2's end behind the hybrid
     * breaker DCB - faulted to earth through 10 ohm for good, DCB ordered open 3 ms on: its ZnO absorber taking the line's current down,
     * VSC3 left alone on the faulted line, VSC1 and VSC2 on their droops - swinging against each other between 0.3 and 0.8 pu, the DC
     * voltage between 0.83 and 1.16 pu, still 450 ms on in both: held to PSCAD's through to the end as through the fault, settled before
     * it alone. Each station's measurements and the DC nodes' voltages, by 5 ms windows.
     *
     * <p>Run 2026-11-14, 60 s the two: within 1.1e-2 pu of PSCAD's through the fault and the swing (VSC2's P, 1.03e-2), the DC nodes'
     * voltages within 5.3e-3 (DC2L, held near 0.085 pu by the fault, VSC3 at 1.09 pu of power into it on its current limit).
     */
    @Test
    void aDcFaultClearedByTheHybridBreakerRunsInEmtpAsInPscadProfessional() throws IOException {
        List<Channel> channels = new ArrayList<>(converters("VSC1", "VSC2", "VSC3"));
        for (String node : List.of("DC1P", "DC2P", "DC2L", "DC3P")) {
            channels.add(new Channel(node, EmtpVoltageSourceConverters.dcVoltageScope(node), "V_" + node, 500.0e3));
        }
        compare("vsc-three-terminal-dc-breaker", start -> List.of(new DcFault("FDC", "DC2L", 10.0, start, start + 10.0),
                DcSwitchOperation.opening("DCB", start + 3.0e-3)), channels, "vsc-dc-breaker", 5.0e-3, SETTLED_TOLERANCE, TRANSIENT_TOLERANCE, false);
    }

    /**
     * Step 4's first: the same, {@code vsc-three-terminal-dc-breaker-mmc}, its stations averaged MMCs - each equivalent capacitor behind two
     * thirds of an arm, its DC voltage measured at its DC terminal. VSC3, alone on the faulted line, its DC voltage down to its 0.1 pu
     * floor, its terminal DC3P ringing between 0.03 and 0.16 pu window to window behind its arms: held within 2.5e-2 pu there.
     *
     * <p>Run 2026-11-14, 44 s the two: every station within 8.1e-3 pu of PSCAD's (VSC2's P), VSC3's power within 7.9e-4; the DC nodes
     * within 6.7e-3 but DC3P, 2.1e-2, and VSC3's measured DC voltage, 1.9e-2 - the ringing's phase; settled within 1e-4.
     */
    @Test
    void aDcFaultClearedByTheHybridBreakerBetweenMmcsRunsInEmtpAsInPscadProfessional() throws IOException {
        List<Channel> channels = new ArrayList<>(converters("VSC1", "VSC2", "VSC3"));
        for (String node : List.of("DC1P", "DC2P", "DC2L", "DC3P")) {
            channels.add(new Channel(node, EmtpVoltageSourceConverters.dcVoltageScope(node), "V_" + node, 500.0e3));
        }
        compare("vsc-three-terminal-dc-breaker-mmc", start -> List.of(new DcFault("FDC", "DC2L", 10.0, start, start + 10.0),
                DcSwitchOperation.opening("DCB", start + 3.0e-3)), channels, "vsc-dc-breaker-mmc", 5.0e-3, SETTLED_TOLERANCE, 0.025, false);
    }

    /**
     * Step 4's second: {@code vsc-monopole-infinite-buses-3}, VSC2 grid-forming - a virtual synchronous machine (H 5 s, D 20), its swing
     * damped, its power reference less its DC voltage's droop, a steady-state current limit holding its angle to a PLL's - through the
     * three-phase fault at B2, its bus, by 5 ms windows. PSCAD's fault 4.5 s on from EMTP's: from rest, the virtual machine's slow swing
     * is not settled by 2.3 s (VSC2 4e-3 pu off); still recovering at the end in both, held through to it as through the fault.
     *
     * <p>Run 2026-11-14, 38 s the two: settled before the fault within 2.6e-4 pu of PSCAD's; through it and the recovery within 1.24e-2
     * (VSC2's P), 1.04e-2 (VSC2's Q), the DC voltages 1.3e-3.
     */
    @Test
    void aFaultAtAGridFormingConvertersBusRunsInEmtpAsInPscadProfessional() throws IOException {
        compare("vsc-monopole-infinite-buses-3", start -> List.of(fault("ABC", start)), MONOPOLE, "vsc-grid-forming-fault", 5.0e-3, SETTLED_TOLERANCE,
                TRANSIENT_TOLERANCE, false, GRID_FORMING_SHIFT);
    }

    /**
     * Step 4's third: {@code vsc-three-terminal-all-droop}, its three stations on their DC voltage droops behind their converter
     * transformers, VSC1 blocked: its EMFs cut at their currents' zeros, its valves left to a diode bridge idle above the AC side's crest;
     * VSC2 and VSC3 taking its share along their curves. By 5 ms windows.
     *
     * <p>Run 2026-11-15, 47 s the two: within 5.5e-3 pu of PSCAD's through the blocking (VSC1's power), VSC2's and VSC3's within 1.9e-3,
     * the DC voltages 4.2e-4; settled within 1.8e-4. VSC1 blocked draws what its bridge's snubbers and grading resistors do, 2e-4 pu and
     * -2.8e-3 of reactive power, in both.
     */
    @Test
    void aBlockedConverterRunsInEmtpAsInPscadProfessional() throws IOException {
        List<Channel> channels = new ArrayList<>(converters("VSC1", "VSC2", "VSC3"));
        for (String node : List.of("DC1P", "DC2P", "DC3P")) {
            channels.add(new Channel(node, EmtpVoltageSourceConverters.dcVoltageScope(node), "V_" + node, 500.0e3));
        }
        compare("vsc-three-terminal-all-droop", start -> List.of(new ConverterBlocking("VSC1", start)), channels, "vsc-blocking", 5.0e-3,
                SETTLED_TOLERANCE, TRANSIENT_TOLERANCE, false);
    }

    /**
     * The same, VSC1 deblocked 200 ms later: its EMFs made again onto its reactors, its valves' switches opened, its controls' integrals
     * restarted from 0 - its current references from 0, its power back on its droop.
     *
     * <p>Run 2026-11-15, 44 s the two: VSC1's power from 2e-4 pu back to 0.40 within 50 ms in both; within 5.8e-3 pu of PSCAD's through
     * the blocking and the deblocking (VSC1's power), the DC voltages 7e-4; settled within 1.8e-4.
     */
    @Test
    void aDeblockedConverterRunsInEmtpAsInPscadProfessional() throws IOException {
        List<Channel> channels = new ArrayList<>(converters("VSC1", "VSC2", "VSC3"));
        for (String node : List.of("DC1P", "DC2P", "DC3P")) {
            channels.add(new Channel(node, EmtpVoltageSourceConverters.dcVoltageScope(node), "V_" + node, 500.0e3));
        }
        compare("vsc-three-terminal-all-droop", start -> List.of(new ConverterBlocking("VSC1", start), new ConverterDeblocking("VSC1", start + 0.2)),
                channels, "vsc-deblocking", 5.0e-3, SETTLED_TOLERANCE, TRANSIENT_TOLERANCE, false);
    }

    /**
     * Upstream's {@code dc-fault-protected} ({@link EmtpVoltageSourceConverterTest#protectedWithDcReactors}): example 4's stations with their
     * DC reactors, protected at 2 pu, through a solid fault on DC3P from the fault to the end, blocked in turn by their protections and
     * feeding the fault through their bridges - by 200 ms windows, as upstream holds PSCAD, ATP and Kestrel to one another on it: the
     * bridges' conduction a chatter no two solvers share window by 5 ms window. The running flags by the same windows.
     *
     * <p>Run 2026-11-15, 40 s the two: the running flags identical - each protection tripping on the same step, ATP's 0.85, 4.67 and 9.93
     * ms after the fault for VSC3, VSC2 and VSC1 ({@link EmtpVoltageSourceConverterTest#theStationsProtectionsBlockThemOnASolidDcFault});
     * the DC voltages within 3.5e-3 pu of PSCAD's, the reactive powers 2.1e-2, the AC voltages 1.5e-2; the powers drawn into the fault 4.6e-2
     * off on VSC1 (0.768 against 0.722 from 0.15 s on), 5.7e-2 on VSC3 in the window holding the fault - held within 6e-2. Upstream holds
     * PSCAD and ATP to the same structural checks only on these powers ({@code PscadProConvertersTest}); the gap's cause not traced - not the
     * valves' snubbers, ATP's which PSCAD has not: EMTP without them 0.740 (2026-11-15).
     */
    @Test
    void protectedConvertersTrippingOnADcFaultRunInEmtpAsInPscadProfessional() throws IOException {
        List<Channel> channels = new ArrayList<>(converters("VSC1", "VSC2", "VSC3"));
        for (String id : List.of("VSC1", "VSC2", "VSC3")) {
            channels.add(new Channel(id + "_RUN", "control:" + id + "_RUN", id + "_RUN", 1.0));
        }
        compare(EmtpVoltageSourceConverterTest::protectedWithDcReactors, start -> List.of(new DcFault("FDC", "DC3P", 0.0, start, start + 10.0)), channels,
                "vsc-protection", 0.2, SETTLED_TOLERANCE, 0.06, false, PSCAD_SHIFT, PscadSwitching.INTERPOLATED);
    }

    private static List<Channel> converters(String... ids) {
        List<Channel> channels = new ArrayList<>();
        for (String id : ids) {
            for (String quantity : List.of("_P", "_Q", "_VAC", "_VDC")) {
                channels.add(new Channel(id + quantity, "control:" + id + quantity, id + quantity, 1.0));
            }
        }
        return channels;
    }

    private static void compare(String bench, DoubleFunction<List<? extends EmtEvent>> events, List<Channel> channels, String name, double window,
                                double settledTolerance, double transientTolerance, boolean settlesAfter) throws IOException {
        compare(bench, events, channels, name, window, settledTolerance, transientTolerance, settlesAfter, PSCAD_SHIFT);
    }

    private static void compare(String bench, DoubleFunction<List<? extends EmtEvent>> events, List<Channel> channels, String name, double window,
                                double settledTolerance, double transientTolerance, boolean settlesAfter, double pscadShift) throws IOException {
        compare(() -> EmtpVoltageSourceConverterTest.bench(bench), events, channels, name, window, settledTolerance, transientTolerance, settlesAfter,
                pscadShift, PscadSwitching.INTERPOLATED);
    }

    private static void compare(Supplier<Network> bench, DoubleFunction<List<? extends EmtEvent>> events, List<Channel> channels, String name,
                                double window, double settledTolerance, double transientTolerance, boolean settlesAfter, double pscadShift,
                                PscadSwitching switching) throws IOException {
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(bench.get(), Path.of("target", "emtp", name),
                new EmtpSimulationSettings(TIME_STEP, EMTP_FAULT + 0.45, FREQUENCY), events.apply(EMTP_FAULT)), EmtpSettings.defaults()).output();
        double pscadFault = EMTP_FAULT + pscadShift;
        PscadOutput pscad = PscadProSimulation.run(bench.get(), Path.of("target", "pscad", name),
                new PscadSimulationSettings(TIME_STEP, pscadFault + 0.45, FREQUENCY, PscadEdition.PROFESSIONAL, switching), events.apply(pscadFault),
                PscadProSettings.defaults()).output();

        double[] worstSettled = new double[channels.size()];
        double[] worstTransient = new double[channels.size()];
        double fault = NumericalAids.switchingInstant(EMTP_FAULT, TIME_STEP);
        try (Writer csv = Files.newBufferedWriter(Path.of("target", "emtp", name + "-against-pscad.csv"))) {
            csv.write("t," + String.join(",", channels.stream().flatMap(c -> java.util.stream.Stream.of(c.label() + "_EMTP", c.label() + "_PSCAD")).toList()) + "\n");
            for (double t = fault - 0.05; t + window <= EMTP_FAULT + 0.45 + 1.0e-9; t += window) {
                StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%.3f", t - fault));
                boolean settled = t + window <= fault || settlesAfter && t >= EMTP_FAULT + 0.35;
                for (int k = 0; k < channels.size(); k++) {
                    Channel channel = channels.get(k);
                    double e = emtp.mean(channel.emtp(), t, t + window) / channel.base();
                    double p = pscad.mean(channel.pscad(), t + pscadShift, t + window + pscadShift) / channel.base();
                    line.append(String.format(Locale.ROOT, ",%.5f,%.5f", e, p));
                    double[] worst = settled ? worstSettled : worstTransient;
                    worst[k] = Math.max(worst[k], Math.abs(e - p));
                }
                csv.write(line + "\n");
            }
        }
        SoftAssertions softly = new SoftAssertions();
        for (int k = 0; k < channels.size(); k++) {
            String label = channels.get(k).label();
            System.out.printf(Locale.ROOT, "%s, %s: settled within %.2e pu, through the fault %.2e pu of PSCAD's%n", name, label, worstSettled[k],
                    worstTransient[k]);
            softly.assertThat(worstSettled[k]).as("%s, %s settled against PSCAD's, pu", name, label).isCloseTo(0.0, within(settledTolerance));
            softly.assertThat(worstTransient[k]).as("%s, %s through the fault against PSCAD's, pu", name, label)
                    .isCloseTo(0.0, within(transientTolerance));
        }
        softly.assertAll();
    }

    private static BusFault fault(String phases, double start) {
        return new BusFault("F", "B2", phases, true, OHMS, start, start + DURATION, FaultClearing.AT_CURRENT_ZERO);
    }
}
