/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.event.BranchOperation;
import com.powsybl.simuemt.extensions.event.BreakerAction;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.event.LoadStep;
import com.powsybl.simuemt.extensions.event.SwitchOperation;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.pscad.PscadEdition;
import com.powsybl.simuemt.pscad.PscadFileWriter;
import com.powsybl.simuemt.pscad.PscadProjectSerializer;
import com.powsybl.simuemt.pscad.PscadSimulationSettings;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.powsybl.simuemt.testresources.ExampleNetworks;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The networks and scenarios PSCAD Professional builds ({@link PscadProBuildTest}): every kind of component and event the
 * PSCAD backend writes - sources, lines, transformers, machines and their controls, Fortran components, voltage source
 * converters, faults, breakers, load steps, arresters, motors - from rest, and as a steady-state run's template. Together
 * they cover every upstream example network the backend writes, those past PSCAD Free's 15 electrical nodes among them.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class PscadProCases {

    /** How a case is written: from rest, or as the template a restart is taken from. */
    enum Kind {
        FROM_REST,
        TEMPLATE
    }

    /** One case: a network, the events it goes through, its settings and how it is written. */
    record Case(String name, Supplier<Network> network, List<? extends EmtEvent> events, PscadSimulationSettings settings, Kind kind) {

        /** The project written into {@code directory} by {@code serializer}: its {@code .pscx} by default. */
        PscadFileWriter.PscadProject write(Path directory, PscadProjectSerializer serializer) {
            Network loaded = ExampleNetworks.withLoadFlow(network.get());
            return switch (kind) {
                case FROM_REST -> PscadFileWriter.write(loaded, directory, settings, events, serializer);
                case TEMPLATE -> PscadFileWriter.writeTemplate(loaded, directory, settings, events, serializer);
            };
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final PscadSimulationSettings SETTINGS = new PscadSimulationSettings(50.0e-6, 0.4, 50.0, PscadEdition.PROFESSIONAL);
    static final PscadSimulationSettings ON_TIME_STEP = new PscadSimulationSettings(20.0e-6, 0.4, 50.0, PscadEdition.PROFESSIONAL,
            PscadSwitching.ON_TIME_STEP);

    private PscadProCases() {
    }

    /** Every case, or those named in {@code -Dpscad.cases=two-bus,machine-ac1a}. */
    static Stream<Case> selected() {
        String only = System.getProperty("pscad.cases", "");
        List<String> names = only.isBlank() ? List.of() : List.of(only.split(","));
        return all().filter(c -> names.isEmpty() || names.contains(c.name()));
    }

    static Stream<Case> all() {
        return Stream.of(
                // The circuit's kinds of components
                fromRest("two-bus", ExampleNetworks::createTwoBusNetworkWithIdealInfiniteBusGenerator),
                fromRest("bergeron-line", PscadProCases::bergeronLine),
                fromRest("transformer", ExampleNetworks::createThreeBusNetworkWithTransformerAndEmtExtensions),
                fromRest("three-winding-transformer", ExampleNetworks::createFourBusNetworkWithThreeWindingsTransformerAndEmtExtensions),
                fromRest("machine-ac1a", ExampleNetworks::createTwoBusNetworkWithAc1A),
                fromRest("machine-tgov1", ExampleNetworks::createTwoBusNetworkWithTgov1),
                fromRest("wind-turbine", ExampleNetworks::createWindTurbineNetwork),
                fromRest("zno-arrester", ExampleNetworks::createTwoBusNetworkWithZnOArrester),
                fromRest("induction-motor", ExampleNetworks::createTwoBusNetworkWithInductionMotor),
                fromRest("fan-motor", ExampleNetworks::createTwoBusNetworkWithFanMotor),
                fromRest("constant-power-load", ExampleNetworks::createTwoBusNetworkWithConstantPowerLoad),
                fromRest("phase-shifter", ExampleNetworks::createThreeBusNetworkWithPhaseShiftingTransformer),
                fromRest("wind-turbine-reec-c", ExampleNetworks::createTwoBusNetworkWithReecC),
                // Past PSCAD Free's 15 electrical nodes: what upstream writes for the Professional edition only
                new Case("line-closing-at-both-ends", ExampleNetworks::createTwoBusNetworkWithLineDisconnectedAtBothEnds,
                        events("branch-l1_2b-close-one-0.2-two-0.3"), SETTINGS, Kind.FROM_REST),
                fromRest("three-winding-open-leg", ExampleNetworks::createThreeBusNetworkWithThreeWindingsTransformerOpenAtLegThree),
                new Case("three-winding-leg-closing", ExampleNetworks::createThreeBusNetworkWithThreeWindingsTransformerOpenAtLegThree,
                        events("leg-t2_3_4-three-close-0.2"), SETTINGS, Kind.FROM_REST),
                fromRest("arrester-on-a-line-out-of-service", ExampleNetworks::createTwoBusNetworkWithArresterAtTheFarEndOfALineOutOfService),
                fromRest("pure-phase-shifter", ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingTransformer),
                fromRest("pure-phase-shifter-ynd11", ExampleNetworks::createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer),
                fromRest("pure-phase-shifting-leg", ExampleNetworks::createFourBusNetworkWithPurePhaseShiftingLeg),
                fromRest("phase-shifting-leg-with-windings", ExampleNetworks::createFourBusNetworkWithPhaseShiftingLegAndWindings),
                fromRest("phase-shifter-and-ratio", () -> withRatio(ExampleNetworks.createThreeBusNetworkWithPurePhaseShiftingTransformer())),
                fromRest("phase-shifter-ynd11-and-ratio", () -> withRatio(ExampleNetworks.createThreeBusNetworkWithPurePhaseShiftingYnd11Transformer())),
                fromRest("phase-shifting-leg-and-ratio", () -> withRatio(ExampleNetworks.createFourBusNetworkWithPurePhaseShiftingLeg())),
                fromRest("phase-shifting-leg-with-windings-and-ratio", () -> withRatio(ExampleNetworks.createFourBusNetworkWithPhaseShiftingLegAndWindings())),
                fromRest("twelve-bus-chain", PscadProCases::twelveBusChain),
                // A machine's controls of the project's own, in Fortran: exciters, limiters and controllers
                fromRest("machine-st4b", ExampleNetworks::createTwoBusNetworkWithSt4B),
                fromRest("machine-ac8b", ExampleNetworks::createTwoBusNetworkWithAc8B),
                fromRest("machine-dec1a", ExampleNetworks::createTwoBusNetworkWithDec1AVoltageLimiter),
                fromRest("machine-pfvar-scl1c", ExampleNetworks::createTwoBusNetworkWithPfVarControllerAndScl1C),
                // ... and governors
                fromRest("machine-gast", ExampleNetworks::createTwoBusNetworkWithGast),
                fromRest("machine-govct2", ExampleNetworks::createTwoBusNetworkWithGovCt2),
                fromRest("machine-goverdtri8", ExampleNetworks::createTwoBusNetworkWithGoverDtrI8),
                fromRest("machine-gover-nordic", ExampleNetworks::createTwoBusNetworkWithGoverNordic),
                fromRest("machine-gover-proportional", ExampleNetworks::createTwoBusNetworkWithGoverProportional),
                fromRest("machine-govhydro4", ExampleNetworks::createTwoBusNetworkWithGovHydro4),
                fromRest("machine-govsteam1", ExampleNetworks::createTwoBusNetworkWithGovSteam1),
                fromRest("machine-govsteameu", ExampleNetworks::createTwoBusNetworkWithGovSteamEu),
                fromRest("machine-hygov", ExampleNetworks::createTwoBusNetworkWithHygov),
                fromRest("machine-ieeeg1", ExampleNetworks::createTwoBusNetworkWithIeeeG1),
                fromRest("machine-ieeeg2", ExampleNetworks::createTwoBusNetworkWithIeeeG2),
                fromRest("machine-tgov3", ExampleNetworks::createTwoBusNetworkWithTGov3),
                // Converters, with the models their benches' files give them
                fromRest("vsc-grid-following", () -> bench("vsc-monopole-infinite-buses")),
                fromRest("vsc-grid-forming", () -> bench("vsc-monopole-infinite-buses-3")),
                fromRest("vsc-dc-chopper", () -> bench("vsc-monopole-infinite-buses-2")),
                fromRest("vsc-dc-droop", PscadProCases::dcDroop),
                fromRest("cigre-dcs2", () -> bench("cigre-dcs2")),
                new Case("vsc-islanding", () -> bench("vsc-monopole-island"), List.of(ExampleNetworks.VSC_MONOPOLE_ISLANDING), SETTINGS,
                        Kind.FROM_REST),
                // The events
                new Case("single-phase-fault", ExampleNetworks::createTwoBusNetworkWithInfiniteBusGenerator,
                        List.of(new BusFault("FLTA", "B2", 'A', 0.2, 0.3, FaultClearing.AT_ORDER)), SETTINGS, Kind.FROM_REST),
                new Case("three-phase-fault", ExampleNetworks::createTwoBusNetworkWithInfiniteBusGenerator,
                        List.of(new BusFault("F", "B2", "ABC", true, 40.0, 0.2, 0.3, FaultClearing.AT_CURRENT_ZERO)), SETTINGS, Kind.FROM_REST),
                new Case("line-opening", ExampleNetworks::createTwoBusNetworkWithInfiniteBusGenerator,
                        List.of(new BranchOperation("L1_2", TwoSides.TWO, 0.2, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO)), SETTINGS, Kind.FROM_REST),
                new Case("switch-opening", ExampleNetworks::createTwoBusNetworkWithRealSwitch,
                        List.of(SwitchOperation.opening("SW", 0.2)), SETTINGS, Kind.FROM_REST),
                new Case("load-step-on-time-step", ExampleNetworks::createTwoBusNetworkWithInfiniteBusGenerator,
                        List.of(new LoadStep("LOAD", 0.2, 1.3)), ON_TIME_STEP, Kind.FROM_REST),
                // The template of a steady-state run: its snapshot, and its events after it
                new Case("template-fault", ExampleNetworks::createTwoBusNetworkWithInfiniteBusGenerator,
                        List.of(new BusFault("FLTA", "B2", 'A', 0.1, 0.15, FaultClearing.AT_ORDER)), ON_TIME_STEP, Kind.TEMPLATE),
                new Case("template-machine", ExampleNetworks::createTwoBusNetworkWithAc1A, List.of(), SETTINGS, Kind.TEMPLATE));
    }

    private static Case fromRest(String name, Supplier<Network> network) {
        return new Case(name, network, List.of(), SETTINGS, Kind.FROM_REST);
    }

    /** A bench of powsybl-simu-emt's test resources: {@code <bench>.xiidm} with its models, {@code <bench>-extensions.xml}. */
    static Network bench(String bench) {
        return EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/" + bench + ".xiidm"),
                ExampleNetworks.resource("/" + bench + "-extensions.xml"));
    }

    /** Example 4's three stations, VSC2 and VSC3 on the DC voltage droop, VSC1 holding the DC voltage; its generators infinite buses. */
    static Network dcDroop() {
        Network network = bench("vsc-three-terminal-vdc-droop");
        for (String generator : List.of("GEN1", "GEN2", "GEN3")) {
            network.getGenerator(generator).addExtension(GeneratorModel.class, new InfiniteBusGeneratorModel(5000.0, 10.0));
        }
        return network;
    }

    /** The upstream scenario {@code events/<name>.xml}. */
    private static List<EmtEvent> events(String name) {
        return EmtEventsXml.read(ExampleNetworks.resource("/events/" + name + ".xml"));
    }

    /** {@code network}'s phase tap changers with a ratio tap changer beside each, as upstream folds into their ratio. */
    private static Network withRatio(Network network) {
        return ExampleNetworks.withRatioBesideThePhaseTapChangers(network, ExampleNetworks.RATIO_BESIDE_PHASE_RHO, false);
    }

    /** The two-bus bench carried on to B12 by a line a bus: a circuit whose layout needs a page larger than the smallest. */
    private static Network twelveBusChain() {
        Network network = ExampleNetworks.createTwoBusNetworkWithInfiniteBusGenerator();
        for (int i = 3; i <= 12; i++) {
            VoltageLevel level = network.newSubstation().setId("SC" + i).add().newVoltageLevel().setId("VLC" + i).setNominalV(400.0)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            Bus bus = level.getBusBreakerView().newBus().setId("B" + i).add();
            String previous = "B" + (i - 1);
            network.newLine().setId("LC" + i).setVoltageLevel1(network.getBusBreakerView().getBus(previous).getVoltageLevel().getId())
                    .setBus1(previous).setConnectableBus1(previous).setVoltageLevel2(level.getId()).setBus2(bus.getId())
                    .setConnectableBus2(bus.getId()).setR(1.0).setX(10.0).setG1(0.0).setB1(0.0).setG2(0.0).setB2(0.0).add();
        }
        return network;
    }

    private static Network bergeronLine() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
        return network;
    }
}
