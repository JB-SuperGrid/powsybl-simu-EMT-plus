/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's three-winding transformer on a three-limb core in PSCAD ({@code eede30fd}): its YNyn0yn0 bench, the source ideal on B2, every leg's tank path 2 +
 * j20 ohm, legs 2's and 3's loads taken away ({@code four-bus-extensions-ynyn0yn0-three-limb.xml}), phase A of B3 faulted from 0.3 to
 * 0.35 s, cleared at order - upstream's {@code PscadFileWriterTest#writeRunsAThreeLimbThreeWindingCoreAsAtpDoes}, which ran it in PSCAD
 * Free. Here in PSCAD Professional from rest, and in EMTP from its steady state, each phase of B3 and B4 held to ATP's fundamental crests
 * as upstream holds PSCAD Free's: within 5 V before the fault and through it, 0.15 kV after the clearing - where opening at order chops
 * the fault's current into the legs' leakage.
 *
 * <p>Run 2026-11-18, 34 s the two: PSCAD Professional within 1.2 V of ATP's everywhere; EMTP within 0.8 V before and through the
 * fault, 9.8 V after the clearing.
 *
 * <p>Tagged {@code pscad} and {@code emtp}: run with {@code mvn test -Ppscad,emtp}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
@Tag("emtp")
class PscadProThreeLimbThreeWindingTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    /** ATP's fundamental crest of each phase, kV: {from, to, B3A, B3B, B3C, B4A, B4B, B4C, tolerance} - upstream's. */
    private static final double[][] ATP = {
        {0.22, 0.28, 93.898097, 93.898097, 93.898097, 16.330104, 16.330104, 16.330104, 0.005},
        {0.325, 0.345, 0.813143, 90.314322, 90.445239, 5.889893, 15.706839, 15.729607, 0.005},
        {0.37, 0.39, 94.159693, 93.964048, 93.963083, 16.375600, 16.341574, 16.341406, 0.15}};

    @Test
    void aThreeLimbThreeWindingCoreRunsInPscadProfessionalAndEmtpAsInAtp() {
        List<EmtEvent> fault = EmtEventsXml.read(ExampleNetworks.resource("/events/fault-b3-a-0.3-0.35-at-order.xml"));
        PscadProSimulation.PscadProRun pscad = PscadProSimulation.run(bench(), Path.of("target", "three-limb-3w"),
                new PscadSimulationSettings(TIME_STEP, 0.4, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP), fault, PscadProSettings.defaults());
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(bench(), Path.of("target", "emtp", "three-limb-3w"), new EmtpSimulationSettings(TIME_STEP, 0.4,
                FREQUENCY), fault), EmtpSettings.defaults()).output();
        PscadOutput output = pscad.output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] row : ATP) {
            for (int bus = 0; bus < 2; bus++) {
                for (int phase = 0; phase < 3; phase++) {
                    String busId = "B" + (3 + bus);
                    char p = "ABC".charAt(phase);
                    double atp = row[2 + 3 * bus + phase];
                    double pscadKv = output.fundamentalPhasor(pscad.project().circuit().voltageChannel(busId, p), FREQUENCY, row[0], row[1]).magnitude() / 1.0e3;
                    double emtpKv = emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope(busId, p), FREQUENCY, row[0], row[1]).magnitude() / 1.0e3;
                    System.out.printf(Locale.ROOT, "%s%c over %.3f-%.3f s: ATP %.6f, PSCAD Pro %+.6f, EMTP %+.6f kV off%n", busId, p, row[0], row[1], atp,
                            pscadKv - atp, emtpKv - atp);
                    softly.assertThat(pscadKv).as("PSCAD Professional, %s%c over %s-%s s, kV", busId, p, row[0], row[1]).isCloseTo(atp, within(row[8]));
                    softly.assertThat(emtpKv).as("EMTP, %s%c over %s-%s s, kV", busId, p, row[0], row[1]).isCloseTo(atp, within(row[8]));
                }
            }
        }
        softly.assertAll();
    }

    /** Upstream's three-limb three-winding bench ({@code PscadFileWriterTest#threeLimbThreeWindingBench}): the three-bus network, its source
     * ideal on B2, wired YNyn0yn0 on a three-limb core, legs 2's and 3's loads taken away, the load flow run. */
    static Network bench() {
        Network network = ExampleNetworks.createThreeBusNetworkWithThreeWindingsTransformer();
        EmtExtensionsXmlReader.graftExtensionsFromXml(network, ExampleNetworks.resource("/four-bus-extensions-ynyn0yn0-three-limb.xml"));
        network.getLoad("LOAD3").remove();
        network.getLoad("LOAD4").remove();
        return ExampleNetworks.withLoadFlow(network);
    }
}
