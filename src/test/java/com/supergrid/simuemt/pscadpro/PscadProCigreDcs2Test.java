/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.event.ConverterPowerStep;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.hvdc.AveragedConverterModel;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscInitialState;
import com.powsybl.simuemt.extensions.hvdc.DcVoltageDroopLaw;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.pscad.PscadOutput;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.testresources.ExampleNetworks;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.within;

/**
 * CIGRE B4 DCS2's four scenarios of {@link PscadProCases}, past PSCAD Free's 15 nodes, run live in PSCAD Professional at 20 us and
 * held to what ATP, Xyce and Kestrel share on them - the criteria upstream holds its stored PSCAD Professional runs of the same
 * scenarios to ({@code PscadFileWriterTest}, tagged {@code professional} and left out of upstream's builds since its {@code
 * 5e6fb79d}), here on a run of the day, against a regression of PSCAD's or of the writer's. Each
 * station's law from the load flow: CMB2 and CMB3 on their DC voltage droops, CME1 and CMF1 on their powers.
 *
 * <p>Run 2026-11-09, upstream's stored runs of 2026-11-07 again: the power steps, every station within 0.16 MW of its law, of CMB2's
 * 100 MW 54.8 its own and 49.6 CMB3's; the wind park's loss, CMF1 and WTF1 at nothing, CMB2 and CMB3 on their droops with the DC
 * grid some 3% lower; the AC fault, CMB3's power -0.0097 pu through it, its DC voltage's peak 1.15167 pu, every station within 0.14
 * MW of its law at the end; the DC fault, CMB2 and CMB3 blocked on their order 2.02 ms on, CMF1 2.28 ms, CME1 10.10 ms, CMB3
 * rectifying 0.89 pu into it until its breaker opens. About 4 minutes the four.
 *
 * <p>Tagged {@code pscad}: run with {@code mvn test -Ppscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("pscad")
class PscadProCigreDcs2Test {

    private static final List<String> STATIONS = List.of("CMB2", "CMB3", "CME1", "CMF1");

    /**
     * CMB2 asked for 0.125 pu (100 MW) more from its AC side at 2.3 s, CMB3 for 0.1 pu (120 MW) less at 3.5 s, run 5 s: just before
     * the second step and at the end, CMB2 and CMB3 each on its droop shifted by the steps it was given so far, at the DC voltage it
     * settles at, CME1 and CMF1 on their load flow's powers, within 0.5 MW; the first step shared between the droops, CMB2 short of its
     * 100 MW, CMB3 taking the rest out of the DC grid.
     */
    @Test
    void thePowerStepsAreSharedAlongTheDroops(@TempDir Path tempDir) {
        Run run = run("cigre-dcs2-power-steps", Double.NaN, tempDir);

        List<ConverterPowerStep> steps = run.events.stream().map(ConverterPowerStep.class::cast).toList();
        SoftAssertions softly = new SoftAssertions();
        for (double[] window : new double[][] {{3.3, 3.5}, {4.8, 5.0}}) {
            for (String id : STATIONS) {
                double rating = rating(run.network, id);
                double stepped = steps.stream().filter(s -> s.converterId().equals(id) && s.timeSeconds() < window[0]).mapToDouble(s -> s.deltaPu() * rating)
                        .sum();
                double p = run.output.mean(id + "_P", window[0], window[1]) * rating;
                double expected = onLaw(run.network, id, run.output.mean(id + "_VDC", window[0], window[1])) * rating + stepped;
                System.out.printf(Locale.ROOT, "power steps, %s at %.1f s: %.2f MW, %.2f expected%n", id, window[1], p, expected);
                softly.assertThat(p).as("%s's power at %.1f s, MW", id, window[1]).isCloseTo(expected, within(0.5));
            }
        }
        double cmb2Rise = (run.output.mean("CMB2_P", 3.3, 3.5) - run.output.mean("CMB2_P", 2.1, 2.3)) * rating(run.network, "CMB2");
        double cmb3Rise = -(run.output.mean("CMB3_P", 3.3, 3.5) - run.output.mean("CMB3_P", 2.1, 2.3)) * rating(run.network, "CMB3");
        System.out.printf(Locale.ROOT, "power steps: of CMB2's 100 MW, %.1f MW its own, %.1f MW CMB3's%n", cmb2Rise, cmb3Rise);
        softly.assertThat(cmb2Rise).as("CMB2's share of its own 100 MW step, MW").isBetween(30.0, 99.0);
        softly.assertThat(cmb3Rise).as("CMB3's share of CMB2's step, MW").isBetween(1.0, 99.0);
        softly.assertAll();
    }

    /**
     * CMF1, grid-forming, and its wind park WTF1 tripped at 2.3 s, run 4 s: each station on its load flow's power before within 2e-3;
     * then CMF1 idle and its island dead, WTF1 delivering nothing; CME1 still feeding its platform's load within 1e-3; CMB2 and CMB3
     * taking the loss along their droops, each at the end on its curve at the DC voltage it settles at, within 1.5e-3, the DC grid more
     * than 1% lower than in the load flow.
     */
    @Test
    void theWindParksLossIsTakenAlongTheDroops(@TempDir Path tempDir) {
        Run run = run("cigre-dcs2-cmf1-trip", 4.0, tempDir);

        double[] end = {3.8, 4.0};
        SoftAssertions softly = new SoftAssertions();
        for (String id : STATIONS) {
            VoltageSourceConverter converter = run.network.getVoltageSourceConverter(id);
            double p0 = converter.getTerminal1().getP() / rating(run.network, id);
            double before = run.output.mean(id + "_P", 2.2, 2.3);
            double atEnd = run.output.mean(id + "_P", end[0], end[1]);
            double vdc = run.output.mean(id + "_VDC", end[0], end[1]);
            System.out.printf(Locale.ROOT, "CMF1 trip, %s: P %.4f before (load flow %.4f), %.4f at the end, V_DC %.4f%n", id, before, p0, atEnd, vdc);
            softly.assertThat(before).as("%s's power before, pu", id).isCloseTo(p0, within(2.0e-3));
            switch (id) {
                case "CMF1" -> {
                    softly.assertThat(run.output.mean("CMF1_P", 2.4, end[1])).as("CMF1's power once blocked, pu").isCloseTo(0.0, within(1.0e-6));
                    softly.assertThat(run.output.mean("CMF1_VAC", 2.7, end[1])).as("CMF1's island's AC voltage, pu").isCloseTo(0.0, within(1.0e-3));
                }
                case "CME1" -> softly.assertThat(atEnd).as("CME1's power at the end, pu").isCloseTo(p0, within(1.0e-3));
                default -> {
                    double loadFlowVdc = converter.getDcTerminal1().getDcNode().getV() / converter.getDcTerminal1().getDcNode().getNominalV();
                    softly.assertThat(vdc).as("%s's DC voltage at the end, pu: the DC grid lower", id).isLessThan(loadFlowVdc - 0.01);
                    softly.assertThat(atEnd).as("%s's power at the end on its droop, pu", id).isCloseTo(onLaw(run.network, id, vdc), within(1.5e-3));
                }
            }
        }
        softly.assertThat(run.output.mean("WTF1_WP", 2.4, end[1])).as("WTF1's power once tripped, pu").isCloseTo(0.0, within(1.0e-6));
        softly.assertAll();
    }

    /**
     * A solid three-phase fault on BAB3, CMB3's 380 kV side, from 2.3 to 2.4 s, run 5 s: CMB3, its voltage gone, unable to deliver its
     * power through the fault; its DC voltage's peak among 20 ms means ATP's, Xyce's and Kestrel's 1.1515 pu, within 2e-3; at the end
     * each station back on its law within 0.5 MW.
     */
    @Test
    void anOnshoreAcFaultIsRiddenThrough(@TempDir Path tempDir) {
        Run run = run("cigre-dcs2-ac-fault-bab3", 5.0, tempDir);

        double peak = 0.0;
        for (double start = 2.3; start < 2.8 - 1.0e-9; start += 0.02) {
            peak = Math.max(peak, run.output.mean("CMB3_VDC", start, start + 0.02));
        }
        double through = run.output.mean("CMB3_P", 2.32, 2.4);
        System.out.printf(Locale.ROOT, "AC fault: CMB3's power %.4f pu through it, its DC voltage's peak %.5f pu%n", through, peak);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(through).as("CMB3's power through the fault, pu").isCloseTo(0.0, within(0.02));
        softly.assertThat(peak).as("CMB3's DC voltage's peak, pu: the other backends'").isCloseTo(1.1515, within(0.002));
        for (String id : STATIONS) {
            double rating = rating(run.network, id);
            double p = run.output.mean(id + "_P", 4.8, 5.0) * rating;
            double expected = onLaw(run.network, id, run.output.mean(id + "_VDC", 4.8, 5.0)) * rating;
            System.out.printf(Locale.ROOT, "AC fault, %s at the end: %.2f MW, %.2f expected%n", id, p, expected);
            softly.assertThat(p).as("%s's power at the end, MW", id).isCloseTo(expected, within(0.5));
        }
        softly.assertAll();
    }

    /**
     * A solid pole-to-ground fault on BMF1 from 2.3 s, every station protected against its arms' overcurrent at 2 pu, CMF1 - the
     * faulted node's - with a 100 mH DC reactor; CMB2, CMB3 and WTF1 ordered blocked 2 ms on, the onshore stations' AC breakers
     * ordered open 60 ms on; run 3 s: each station running and on its load flow's power before, within 3e-3; every one blocked within
     * 20 ms of the fault - CMB2 and CMB3 on their order, within 0.2 ms, CMF1 by its own protection at least 1 ms before CME1 by its
     * own -; CMB3's bridge rectifying into the fault until its breaker opens; from 0.1 s after the fault, no power through any station.
     */
    @Test
    void aDcFaultIsClearedThroughTheOnshoreBreakers(@TempDir Path tempDir) {
        Run run = run("cigre-dcs2-dc-fault-bmf1", Double.NaN, tempDir);

        double fault = 2.3;
        double order = 2.302;
        Map<String, Double> blocked = new LinkedHashMap<>();
        SoftAssertions softly = new SoftAssertions();
        for (String id : STATIONS) {
            double p0 = run.network.getVoltageSourceConverter(id).getTerminal1().getP() / rating(run.network, id);
            blocked.put(id, run.output.firstTimeBelow(id + "_RUN", 0.5, 2.2));
            double after = run.output.mean(id + "_P", 2.4, 3.0);
            System.out.printf(Locale.ROOT, "DC fault, %s: blocked %.2f ms after the fault, P %.6f pu from 2.4 s%n", id, (blocked.get(id) - fault) * 1.0e3,
                    after);
            softly.assertThat(run.output.mean(id + "_P", 2.2, 2.299)).as("%s's power before, pu", id).isCloseTo(p0, within(3.0e-3));
            softly.assertThat(run.output.mean(id + "_RUN", 2.2, 2.299)).as("%s running before the fault", id).isEqualTo(1.0);
            softly.assertThat(blocked.get(id)).as("%s blocked at, s", id).isBetween(fault, fault + 0.02);
            softly.assertThat(after).as("%s's power from 0.1 s after the fault, pu", id).isCloseTo(0.0, within(1.0e-5));
        }
        softly.assertThat(blocked.get("CMB2")).as("CMB2 blocked at, s: on its order").isCloseTo(order, within(2.0e-4));
        softly.assertThat(blocked.get("CMB3")).as("CMB3 blocked at, s: on its order").isCloseTo(order, within(2.0e-4));
        softly.assertThat(blocked.get("CMF1")).as("CMF1 blocked at, s: before CME1").isLessThan(blocked.get("CME1") - 1.0e-3);
        double rectifying = run.output.mean("CMB3_P", 2.32, 2.36);
        System.out.printf(Locale.ROOT, "DC fault: CMB3 rectifying %.3f pu into it before its breaker opens%n", rectifying);
        softly.assertThat(rectifying).as("CMB3's power, blocked, before its breaker opens, pu").isGreaterThan(0.3);
        softly.assertAll();
    }

    /** A case of {@link PscadProCases}'s run from rest, to {@code runEnd} rather than its own if not {@code NaN}. */
    private record Run(Network network, List<? extends EmtEvent> events, PscadOutput output) {
    }

    private static Run run(String name, double runEnd, Path tempDir) {
        PscadProCases.Case scenario = PscadProCases.all().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
        Network network = ExampleNetworks.withLoadFlow(scenario.network().get());
        PscadSimulationSettings settings = scenario.settings();
        if (!Double.isNaN(runEnd)) {
            settings = new PscadSimulationSettings(settings.timeStepSeconds(), runEnd, settings.nominalFrequencyHz(), settings.edition(), settings.switching());
        }
        PscadProSimulation.PscadProRun run = PscadProSimulation.run(network, tempDir, settings, scenario.events(), PscadProSettings.defaults());
        return new Run(network, scenario.events(), run.output());
    }

    private static double rating(Network network, String id) {
        return ((AveragedConverterModel) network.getVoltageSourceConverter(id).getExtension(VoltageSourceConverterModel.class)).getRatedApparentPower();
    }

    /** {@code id}'s power on its law at the DC voltage {@code vdc}, pu of its rating: its load flow's, less what its droop, if any, takes. */
    private static double onLaw(Network network, String id, double vdc) {
        VoltageSourceConverter converter = network.getVoltageSourceConverter(id);
        AveragedConverterModel model = (AveragedConverterModel) converter.getExtension(VoltageSourceConverterModel.class);
        AveragedVscInitialState initial = AveragedVscInitialState.of(converter, model);
        double reduction = DcVoltageDroopLaw.of(converter, initial).map(law -> law.powerReduction(vdc) * initial.baseApparentPower()).orElse(0.0);
        return (converter.getTerminal1().getP() - reduction) / model.getRatedApparentPower();
    }
}
