/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.extensions.TwoWindingsTransformerFortescueAdder;
import com.powsybl.iidm.network.extensions.WindingConnectionType;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
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
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's magnetizing admittance on a three-limb core in PSCAD ({@code aa18d47c}): the two-winding three-limb bench with 3.3e-4 -
 * j1e-3 S on side 2 - a magnetizing reactance of 2.5 pu - through a phase-A fault on B2 from 0.2 to 0.25 s, and the three-winding one with
 * 1e-5 - j1.5e-4 and 1e-5 - j1e-4 S at the star bus on legs 1 and 2 through a phase-A fault on B3 from 0.3 to 0.35 s - upstream's {@code
 * PscadFileWriterTest#writeRunsAThreeLimbCoresMagnetizingBranchAsAtpDoesButForItsEqualSelfInductances}, which ran them in PSCAD Free.
 * Here in PSCAD Professional from rest and in EMTP from its steady state, each phase of the two buses past the transformer held to
 * ATP's fundamental crests as upstream holds PSCAD Free's: within 0.1 kV on the two-winding core, 0.07 kV on the three-winding one -
 * PSCAD's non-ideal unit giving every winding one self inductance where IIDM's star adds each winding's own leakage share, which
 * upstream measured 70 V and 59 V off ATP's.
 *
 * <p>Run 2026-11-19, 62 s the four: EMTP within 0.45 V of ATP's everywhere - its magnetizing branch at the star point as ATP's -;
 * PSCAD Professional upstream's PSCAD Free to the volt, 58 V over ATP's on the three-winding core's B3, in the two-winding core's
 * fault 70 V under on B3A and 81 V over on B2C.
 *
 * <p>Tagged {@code pscad} and {@code emtp}: run with {@code mvn test -Ppscad,emtp}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
@Tag("emtp")
class PscadProThreeLimbMagnetizingTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;

    @Test
    void aTwoWindingThreeLimbCoresMagnetizingBranchRunsInPscadProfessionalAndEmtpAsInAtp() {
        compare(twoWinding(), "fault-b2-a-0.2-0.25-at-order", "three-limb-magnetizing-2w", new String[] {"B2", "B3"}, 0.1, new double[][] {
            {0.12, 0.18, 320.262704, 320.262773, 320.262648, 155.771205, 155.771269, 155.771146},
            {0.225, 0.245, 0.088329, 308.799523, 305.760952, 14.121349, 144.786281, 143.105199},
            {0.30, 0.32, 320.262705, 320.262735, 320.262686, 155.771210, 155.771233, 155.771184}});
    }

    @Test
    void aThreeWindingThreeLimbCoresMagnetizingBranchRunsInPscadProfessionalAndEmtpAsInAtp() {
        compare(threeWinding(), "fault-b3-a-0.3-0.35-at-order", "three-limb-magnetizing-3w", new String[] {"B3", "B4"}, 0.07, new double[][] {
            {0.22, 0.28, 93.662066, 93.662063, 93.662068, 16.289055, 16.289055, 16.289055},
            {0.325, 0.345, 0.812296, 90.103786, 90.219136, 5.883735, 15.670224, 15.690284},
            {0.37, 0.39, 93.663742, 93.662482, 93.662486, 16.289346, 16.289127, 16.289128}});
    }

    /** {@code network} through {@code event} in PSCAD Professional and in EMTP, each bus phase's fundamental crest against ATP's
     * {@code {from, to, A, B, C of the first bus, A, B, C of the second}}, kV, within {@code toleranceKv}. */
    private static void compare(Network network, String event, String name, String[] buses, double toleranceKv, double[][] atp) {
        List<EmtEvent> fault = EmtEventsXml.read(ExampleNetworks.resource("/events/" + event + ".xml"));
        PscadProSimulation.PscadProRun pscad = PscadProSimulation.run(network, Path.of("target", name), new PscadSimulationSettings(TIME_STEP, 0.4,
                FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP), fault, PscadProSettings.defaults());
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(network, Path.of("target", "emtp", name), new EmtpSimulationSettings(TIME_STEP, 0.4,
                FREQUENCY), fault), EmtpSettings.defaults()).output();
        SoftAssertions softly = new SoftAssertions();
        for (double[] row : atp) {
            for (int bus = 0; bus < 2; bus++) {
                for (int phase = 0; phase < 3; phase++) {
                    char p = "ABC".charAt(phase);
                    double reference = row[2 + 3 * bus + phase];
                    double pscadKv = pscad.output().fundamentalPhasor(pscad.project().circuit().voltageChannel(buses[bus], p), FREQUENCY, row[0], row[1])
                            .magnitude() / 1.0e3;
                    double emtpKv = emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope(buses[bus], p), FREQUENCY, row[0], row[1]).magnitude() / 1.0e3;
                    System.out.printf(Locale.ROOT, "%s %s%c over %.3f-%.3f s: ATP %.6f, PSCAD Pro %+.6f, EMTP %+.6f kV off%n", name, buses[bus], p, row[0],
                            row[1], reference, pscadKv - reference, emtpKv - reference);
                    softly.assertThat(pscadKv).as("PSCAD Professional, %s%c over %s-%s s, kV", buses[bus], p, row[0], row[1]).isCloseTo(reference,
                            within(toleranceKv));
                    softly.assertThat(emtpKv).as("EMTP, %s%c over %s-%s s, kV", buses[bus], p, row[0], row[1]).isCloseTo(reference, within(toleranceKv));
                }
            }
        }
        softly.assertAll();
    }

    /** Upstream's {@code magnetizingThreeLimbBench}: the transformer bench, its source ideal on B2, YNyn on a three-limb core of 2 + j20
     * ohm, 3.3e-4 - j1e-3 S on side 2; the load flow run. */
    private static Network twoWinding() {
        Network network = ExampleNetworks.createThreeBusNetworkWithTransformer();
        network.getGenerator("GEN").addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(0.0, 0.0));
        TwoWindingsTransformer transformer = network.getTwoWindingsTransformer("T2_3");
        transformer.newExtension(TwoWindingsTransformerFortescueAdder.class).withConnectionType1(WindingConnectionType.Y_GROUNDED)
                .withConnectionType2(WindingConnectionType.Y_GROUNDED).withFreeFluxes(false).withRz(2.0).withXz(20.0).add();
        transformer.setG(3.3e-4).setB(-1.0e-3);
        return ExampleNetworks.withLoadFlow(network);
    }

    /** Upstream's {@code magnetizingThreeLimbThreeWindingBench}: {@link PscadProThreeLimbThreeWindingTest}'s, with 1e-5 - j1.5e-4 and
     * 1e-5 - j1e-4 S at the star bus on legs 1 and 2; the load flow run. */
    private static Network threeWinding() {
        Network network = ExampleNetworks.createThreeBusNetworkWithThreeWindingsTransformer();
        EmtExtensionsXmlReader.graftExtensionsFromXml(network, ExampleNetworks.resource("/four-bus-extensions-ynyn0yn0-three-limb.xml"));
        network.getLoad("LOAD3").remove();
        network.getLoad("LOAD4").remove();
        network.getThreeWindingsTransformer("T2_3_4").getLeg1().setG(1.0e-5).setB(-1.5e-4);
        network.getThreeWindingsTransformer("T2_3_4").getLeg2().setG(1.0e-5).setB(-1.0e-4);
        return ExampleNetworks.withLoadFlow(network);
    }
}
