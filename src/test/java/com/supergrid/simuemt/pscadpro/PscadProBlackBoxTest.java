/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.within;

/**
 * Upstream's reference black boxes ({@code bf288b4a}) in PSCAD Professional, from their win64 DLLs through upstream's Fortran wrapper,
 * as upstream's {@code PscadBlackBoxModelTest} ran them in PSCAD Free: each bench from rest, released at 2 s, phase A faulted at 2.5 s
 * and cleared at its current's zero. Each black box is held to its white model run alike in PSCAD Professional, and to the same black box
 * in EMTP - its win32 build, from the steady state, faulted 2.2 s earlier ({@link com.supergrid.simuemt.emtp.EmtpBlackBoxTest}): the same
 * DLL's wiring checked in the overlay's two solvers. Held as upstream holds PSCAD Free's against the white model - the bus voltages'
 * fundamental crests, the converters' powers and DC voltages by means - and to EMTP's within what the two solvers part by, a fault window
 * being one from the fault's start to 100 ms past its clearing.
 *
 * <p>Run 2026-11-23, 3 min 36 the four: against the white model as upstream measured in PSCAD Free - the AVR 2.8e-6, the converter's
 * voltages 1.8e-5 and its powers 8.6e-4 pu in the fault, the machine, white PSCAD's own, 1.7e-3 in the fault and 2.6e-4 after it, the
 * regulated one 1.7e-3 and 2.3e-4. Against EMTP: the AVR 1.3e-3 in the fault, the two solvers' own machines on either side, 1.6e-4 at
 * 2.8 s; the converter's voltages 7.8e-4 in the fault and 1.2e-6 out of it, its powers 2.3e-3 pu in it; the black-box machines, the
 * same DLL in both solvers, closer than PSCAD's machine to them: 1.7e-4 in the fault, 5.1e-5 out of it.
 *
 * <p>Tagged {@code pscad} and {@code emtp}: run with {@code mvn test -Ppscad,emtp}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
@Tag("emtp")
class PscadProBlackBoxTest {

    private static final double FREQUENCY = 50.0;
    private static final double TIME_STEP = 20.0e-6;
    /** PSCAD's fault time less EMTP's. */
    private static final double SHIFT = 2.2;
    private static final List<String[]> PHASES = List.of(new String[] {"B1", "A"}, new String[] {"B1", "B"}, new String[] {"B2", "A"});
    /** The converters' powers and DC voltages, pu: to the white model as upstream holds them, to EMTP as {@code EmtpBlackBoxTest} holds
     * them to the white control. */
    private static final double MEASUREMENT_TOLERANCE = 2e-3;
    private static final double EMTP_MEASUREMENT_TOLERANCE = 3e-3;

    private static final Tolerances MACHINE = new Tolerances(2.5e-3, 5e-4, 3e-4, 1e-4);

    @Test
    void theReferenceBlackBoxAvrRunsAsItsWhiteModel() {
        compare("black-box-avr", () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxAvr()),
                () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithEmtExtensions()), 4.0, "B2", 2.5, 2.6,
                new double[] {2.4, 2.52, 2.65, 2.8, 3.5}, List.of(),
                new Tolerances(1e-5, 1e-5, 2e-3, 3e-4));
    }

    @Test
    void theReferenceBlackBoxConverterControlRunsAsItsWhiteModel() {
        compare("black-box-gfl", () -> vscBench("blackbox"), () -> vscBench("gfl"), 3.2, "B1", 2.5, 2.55, new double[] {2.1, 2.4, 2.52, 2.6, 3.0},
                List.of("VSC1_P", "VSC1_Q", "VSC1_VDC", "VSC2_P", "VSC2_VDC"), new Tolerances(5e-5, 5e-5, 1e-3, 2e-5));
    }

    @Test
    void theReferenceBlackBoxMachineRunsAsItsWhiteModel() {
        compare("black-box-machine", () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachine()),
                () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithHeldSubtransientMachine()), 4.0, "B2", 2.5, 2.6,
                new double[] {2.04, 2.4, 2.52, 2.58, 2.65, 2.8, 3.0, 3.5}, List.of(), MACHINE);
    }

    @Test
    void theReferenceBlackBoxMachineRunsUnderTypedRegulatorsAsItsWhiteModel() {
        compare("black-box-machine-regulated", () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithBlackBoxMachineAndTypedRegulators()),
                () -> ExampleNetworks.withLoadFlow(ExampleNetworks.createTwoBusNetworkWithRegulatedSubtransientMachine()), 4.0, "B2", 2.5, 2.6,
                new double[] {2.04, 2.4, 2.52, 2.58, 2.65, 2.8, 3.0, 3.5}, List.of(), MACHINE);
    }

    /** Example 2's VSC1 under the core of its grid-following control, white ({@code -gfl}) or as its DLL ({@code -blackbox}). */
    private static Network vscBench(String variant) {
        Network network = ExampleNetworks.readVscBench("vsc-monopole-infinite-buses-2-" + variant, EmtExtensionsXmlReader::readNetwork);
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    private static void compare(String name, Supplier<Network> blackBoxNetwork, Supplier<Network> whiteNetwork, double seconds, String faultBus,
                                double faultStart, double faultEnd, double[] starts, List<String> measurements, Tolerances tolerances) {
        PscadSimulationSettings settings = new PscadSimulationSettings(TIME_STEP, seconds, FREQUENCY, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP);
        BusFault fault = new BusFault("FLTA", faultBus, 'A', faultStart, faultEnd, FaultClearing.AT_CURRENT_ZERO);
        PscadProSimulation.PscadProRun blackBox = PscadProSimulation.run(blackBoxNetwork.get(), Path.of("target", name), settings, List.of(fault),
                PscadProSettings.defaults());
        PscadProSimulation.PscadProRun white = PscadProSimulation.run(whiteNetwork.get(), Path.of("target", name + "-white"), settings, List.of(fault),
                PscadProSettings.defaults());
        BusFault emtpFault = new BusFault("FLTA", faultBus, 'A', faultStart - SHIFT, faultEnd - SHIFT, FaultClearing.AT_CURRENT_ZERO);
        EmtpOutput emtp = EmtpRunner.run(EmtpNetlistWriter.write(blackBoxNetwork.get(), Path.of("target", "emtp", name + "-pro"),
                new EmtpSimulationSettings(TIME_STEP, seconds - SHIFT, FREQUENCY), List.of(emtpFault)), EmtpSettings.defaults()).output();

        SoftAssertions softly = new SoftAssertions();
        PscadOutput pro = blackBox.output();
        for (double start : starts) {
            boolean inFault = start >= faultStart && start < faultEnd + 0.1;
            double whiteTolerance = inFault ? tolerances.whiteFault() : tolerances.white();
            double emtpTolerance = inFault ? tolerances.emtpFault() : tolerances.emtp();
            for (String[] phase : PHASES) {
                char p = phase[1].charAt(0);
                double actual = pro.fundamentalPhasor(blackBox.project().circuit().voltageChannel(phase[0], p), FREQUENCY, start, start + 0.02).magnitude();
                double expected = white.output().fundamentalPhasor(white.project().circuit().voltageChannel(phase[0], p), FREQUENCY, start, start + 0.02)
                        .magnitude();
                double inEmtp = start < SHIFT + 0.1 ? Double.NaN : emtp.fundamentalPhasor(EmtpNetlistWriter.voltageScope(phase[0], p), FREQUENCY,
                        start - SHIFT, start - SHIFT + 0.02).magnitude();
                System.out.printf(Locale.ROOT, "%s %s%s at %.2f s: PSCAD Pro black box %+.2e off its white model, %+.2e off EMTP's black box%n", name,
                        phase[0], phase[1], start, actual / expected - 1.0, actual / inEmtp - 1.0);
                softly.assertThat(actual).as("%s%s at %s s, against the white model", phase[0], phase[1], start).isCloseTo(expected, within(whiteTolerance * expected));
                if (!Double.isNaN(inEmtp)) {
                    softly.assertThat(actual).as("%s%s at %s s, against EMTP", phase[0], phase[1], start).isCloseTo(inEmtp, within(emtpTolerance * inEmtp));
                }
            }
            for (String measurement : measurements) {
                double actual = pro.mean(measurement, start, start + 0.02);
                double expected = white.output().mean(measurement, start, start + 0.02);
                double inEmtp = start < SHIFT + 0.1 ? Double.NaN : emtp.mean("control:" + measurement, start - SHIFT, start - SHIFT + 0.02);
                System.out.printf(Locale.ROOT, "%s %s at %.2f s: PSCAD Pro black box %.6f, %+.2e off its white model, %+.2e off EMTP's black box%n", name,
                        measurement, start, actual, actual - expected, actual - inEmtp);
                softly.assertThat(actual).as("%s at %s s, against the white model", measurement, start).isCloseTo(expected, within(MEASUREMENT_TOLERANCE));
                if (!Double.isNaN(inEmtp)) {
                    softly.assertThat(actual).as("%s at %s s, against EMTP", measurement, start).isCloseTo(inEmtp, within(EMTP_MEASUREMENT_TOLERANCE));
                }
            }
        }
        softly.assertAll();
    }

    /** The bus voltages' relative tolerances: against the white model in and out of the fault's windows, then against EMTP. */
    private record Tolerances(double whiteFault, double white, double emtpFault, double emtp) {
    }
}
