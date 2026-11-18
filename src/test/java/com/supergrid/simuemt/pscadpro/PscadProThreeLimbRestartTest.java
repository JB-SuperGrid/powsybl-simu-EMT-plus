/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import com.supergrid.simuemt.emtp.EmtpNetlistWriter;
import com.supergrid.simuemt.emtp.EmtpOutput;
import com.supergrid.simuemt.emtp.EmtpRunner;
import com.supergrid.simuemt.emtp.EmtpSettings;
import com.supergrid.simuemt.emtp.EmtpSimulationSettings;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's restart of a three-winding transformer on a three-limb core from a snapshot ({@code ea69a699}): the three-limb bench
 * ({@link PscadProThreeLimbThreeWindingTest#bench}) and the same with its magnetizing admittance ({@link
 * PscadProThreeLimbMagnetizingTest#threeWinding}), phase A of B3 faulted from 0.1 to 0.15 s - upstream's {@code
 * PscadFileWriterTest#writeRestartStartsAThreeWindingThreeLimbCoreSteady}, which ran it in PSCAD Free. Here in PSCAD Professional
 * from its template's snapshot: steady from the first step, every phase of B2, B3 and B4 its first period's crest within 1e-6 of a
 * period's, 40 to 20 ms before the fault; in the fault, from 0.125 s, held to ATP's crests as upstream holds PSCAD Free's - the plain core within
 * 3e-5, the magnetized one within 0.07 kV, its non-ideal unit's equal self inductances -, and EMTP from its steady state to the same.
 *
 * <p>Run 2026-11-19, 124 s the four: both restarts steady from the first step, every phase the same to 1 uV; in the fault the plain
 * core within 0.10 V of ATP's in PSCAD Professional, 0.84 V in EMTP; the magnetized core 57 V over in PSCAD, as from rest, 0.2 V in EMTP.
 *
 * <p>Tagged {@code pscad} and {@code emtp}: run with {@code mvn test -Ppscad,emtp}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
@Tag("emtp")
class PscadProThreeLimbRestartTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    private static final double PERIOD = 1.0 / FREQUENCY;
    private static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(TIME_STEP, 0.3, FREQUENCY, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);
    /** ATP's fundamental crests in the fault, from 0.125 s, kV - upstream's. */
    private static final Map<String, Map<String, Double>> ATP = Map.of(
            "plain", Map.of("B3B", 90.314322, "B3C", 90.445239, "B4A", 5.889893, "B4B", 15.706839, "B4C", 15.729607),
            "magnetized", Map.of("B3B", 90.103786, "B4A", 5.883735));

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plain", "magnetized"})
    void aThreeWindingThreeLimbCoreRestartsSteadyInPscadProfessional(String core) {
        boolean magnetized = "magnetized".equals(core);
        List<EmtEvent> fault = EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b3-a-0.1-0.15-at-order.xml"));
        PscadProSimulation.PscadProRun pscad = PscadProSimulation.runFromSteadyState(bench(magnetized), Path.of("target", "three-limb-restart-" + core),
                SETTINGS, fault, PscadProSettings.defaults());
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(bench(magnetized), Path.of("target", "emtp", "three-limb-restart-" + core),
                new EmtpSimulationSettings(TIME_STEP, 0.3, FREQUENCY), fault), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (String bus : List.of("B2", "B3", "B4")) {
            for (char phase : new char[] {'A', 'B', 'C'}) {
                String channel = pscad.project().circuit().voltageChannel(bus, phase);
                double first = pscad.output().fundamentalPhasor(channel, FREQUENCY, TIME_STEP, TIME_STEP + PERIOD).magnitude();
                double before = pscad.output().fundamentalPhasor(channel, FREQUENCY, 0.1 - 2 * PERIOD, 0.1 - PERIOD).magnitude();
                System.out.printf(Locale.ROOT, "%s %s%c: first period %.6f kV, before the fault %.6f kV%n", core, bus, phase, first / 1.0e3, before / 1.0e3);
                softly.assertThat(first / before - 1.0).as("%s, %s%c's first period against the one before the fault", core, bus, phase)
                        .isCloseTo(0.0, within(1.0e-6));
            }
        }
        for (Map.Entry<String, Double> reference : ATP.get(core).entrySet()) {
            String bus = reference.getKey().substring(0, 2);
            char phase = reference.getKey().charAt(2);
            double pscadKv = pscad.output().fundamentalPhasor(pscad.project().circuit().voltageChannel(bus, phase), FREQUENCY, 0.125, 0.125 + PERIOD)
                    .magnitude() / 1.0e3;
            double emtpKv = emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope(bus, phase), FREQUENCY, 0.125, 0.125 + PERIOD).magnitude() / 1.0e3;
            double tolerance = magnetized ? 0.07 : 3.0e-5 * reference.getValue();
            System.out.printf(Locale.ROOT, "%s %s in the fault: ATP %.6f, PSCAD Pro %+.6f, EMTP %+.6f kV off%n", core, reference.getKey(), reference.getValue(),
                    pscadKv - reference.getValue(), emtpKv - reference.getValue());
            softly.assertThat(pscadKv).as("PSCAD Professional, %s %s in the fault, kV", core, reference.getKey()).isCloseTo(reference.getValue(),
                    within(tolerance));
            softly.assertThat(emtpKv).as("EMTP, %s %s in the fault, kV", core, reference.getKey()).isCloseTo(reference.getValue(), within(tolerance));
        }
        softly.assertAll();
    }

    private static Network bench(boolean magnetized) {
        return magnetized ? PscadProThreeLimbMagnetizingTest.threeWinding() : PscadProThreeLimbThreeWindingTest.bench();
    }
}
