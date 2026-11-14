/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.circuit.NumericalAids;
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's VSC bipole between infinite buses ({@code vsc-bipole-infinite-buses}) faulted solidly from one pole to the other at station
 * 2, DC2P to DC2N, 0.3 s after its converters are let go - the pole-to-pole DC fault upstream's PSCAD writer takes since its {@code
 * b5cea5b0} ({@code dc-fault-dc2p-dc2n-solid-0.3}), which upstream runs on no PSCAD bench: PSCAD refuses its symmetric monopole, the only
 * bench upstream faults so, and its bipole is PSCAD Professional's. No backend has run this one: held to the same bipole faulted solidly
 * from each pole to earth at once, the faults PSCAD has long written. The bipole being all but symmetric, the fault's point stays at
 * earth's potential and the two are one circuit: station 1's poles discharging into the fault through the lines, its converters
 * importing the power the inverters still draw. Run live in PSCAD Professional from rest at 20 us, the converters let go at 2 s.
 *
 * <p>Run 2026-11-14, about a minute the two: identical before the fault; through it every DC voltage within 0.34 kV of the earth faults'
 * - DC1P 239.68 kV in its first 10 ms against 239.71 -, every converter's power within 1e-5 pu; the fault's point within 0.38 kV of earth
 * at its crest, -0.33 kV at the end, the common mode the poles' controls leave - VSC1N regulates no AC voltage, VSC1P does; DC1P and
 * DC1N mirrored within 0.7 kV.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProBipolePoleToPoleTest {

    private static final double TIME_STEP = 20.0e-6;
    private static final double FAULT = NumericalAids.switchingInstant(2.3, TIME_STEP);
    private static final double RUN_END = 2.5;
    private static final List<String> CONVERTERS = List.of("VSC1P", "VSC1N", "VSC2P", "VSC2N");
    private static final List<String> NODES = List.of("DC1P", "DC1N", "DC2P", "DC2N");
    private static final double VOLTAGE_TOLERANCE_VOLTS = 1.0e3;
    private static final double POWER_TOLERANCE = 1.0e-4;

    @Test
    void aPoleToPoleFaultIsBothPolesFaultedToEarth(@TempDir Path tempDir) {
        PscadOutput poleToPole = run(List.of(new DcFault("FDC", "DC2P", 0.0, 2.3, 12.0, "DC2N")), tempDir.resolve("pole-to-pole"));
        PscadOutput toEarth = run(List.of(new DcFault("FDCP", "DC2P", 0.0, 2.3, 12.0), new DcFault("FDCN", "DC2N", 0.0, 2.3, 12.0)),
                tempDir.resolve("to-earth"));

        SoftAssertions softly = new SoftAssertions();
        double[][] windows = {{2.25, 2.299}, {FAULT + 0.001, FAULT + 0.01}, {FAULT + 0.02, FAULT + 0.05}, {RUN_END - 0.05, RUN_END}};
        for (double[] window : windows) {
            String when = String.format(Locale.ROOT, "%.3f to %.3f s", window[0], window[1]);
            for (String node : NODES) {
                double v = poleToPole.mean("V_" + node, window[0], window[1]);
                double reference = toEarth.mean("V_" + node, window[0], window[1]);
                System.out.printf(Locale.ROOT, "%s %s: %.2f kV, both earth faults %.2f kV%n", when, node, v / 1.0e3, reference / 1.0e3);
                softly.assertThat(v - reference).as("%s, %s against both poles faulted to earth, V", when, node).isCloseTo(0.0, within(VOLTAGE_TOLERANCE_VOLTS));
            }
            for (String id : CONVERTERS) {
                softly.assertThat(poleToPole.mean(id + "_P", window[0], window[1]) - toEarth.mean(id + "_P", window[0], window[1]))
                        .as("%s, %s's power against both poles faulted to earth, pu", when, id).isCloseTo(0.0, within(POWER_TOLERANCE));
            }
            softly.assertThat(poleToPole.mean("V_DC1P", window[0], window[1]) + poleToPole.mean("V_DC1N", window[0], window[1]))
                    .as("%s, DC1P and DC1N mirrored, V", when).isCloseTo(0.0, within(VOLTAGE_TOLERANCE_VOLTS));
        }
        for (String node : List.of("DC2P", "DC2N")) {
            double crest = poleToPole.peakAbsolute("V_" + node, FAULT, RUN_END);
            System.out.printf(Locale.ROOT, "%s through the fault: crest %.2f kV%n", node, crest / 1.0e3);
            softly.assertThat(crest).as("%s, the fault's point, at earth's potential, V", node).isLessThan(VOLTAGE_TOLERANCE_VOLTS);
        }
        softly.assertAll();
    }

    private static PscadOutput run(List<? extends EmtEvent> events, Path directory) {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/vsc-bipole-infinite-buses.xiidm"),
                ExampleNetworks.resource("/vsc-bipole-infinite-buses-extensions.xml"));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        PscadSimulationSettings settings = new PscadSimulationSettings(TIME_STEP, RUN_END, 50.0, PscadEdition.PROFESSIONAL);
        return PscadProSimulation.run(network, directory, settings, events, PscadProSettings.defaults()).output();
    }
}
