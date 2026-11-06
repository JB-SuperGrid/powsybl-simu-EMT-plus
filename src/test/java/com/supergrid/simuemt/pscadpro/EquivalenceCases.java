/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.simuemt.extensions.event.BranchOperation;
import com.powsybl.simuemt.extensions.event.BreakerAction;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.event.LoadStep;
import com.powsybl.simuemt.extensions.event.SwitchOperation;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
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
 * The networks and scenarios the script is held to the {@code .pscx} on ({@link PscadProEquivalenceTest}): every kind of
 * component and event the PSCAD backend writes - sources, lines, transformers, machines and their controls, Fortran
 * components, voltage source converters, faults, breakers, load steps, arresters, motors - from rest, and as a steady-state run's template.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
final class EquivalenceCases {

    /** How a case is written: from rest, or as the template a restart is taken from. */
    enum Kind {
        FROM_REST,
        TEMPLATE
    }

    /** One case: a network, the events it goes through, its settings and how it is written. */
    record Case(String name, Supplier<Network> network, List<? extends EmtEvent> events, PscadSimulationSettings settings, Kind kind) {

        /** The project written into {@code directory} by {@code serializer}. */
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

    private EquivalenceCases() {
    }

    static Stream<Case> all() {
        return Stream.of(
                // The circuit's kinds of components
                fromRest("two-bus", ExampleNetworks::createTwoBusNetworkWithIdealInfiniteBusGenerator),
                fromRest("bergeron-line", EquivalenceCases::bergeronLine),
                fromRest("transformer", ExampleNetworks::createThreeBusNetworkWithTransformerAndEmtExtensions),
                fromRest("three-winding-transformer", ExampleNetworks::createFourBusNetworkWithThreeWindingsTransformerAndEmtExtensions),
                fromRest("machine-ac1a", ExampleNetworks::createTwoBusNetworkWithAc1A),
                fromRest("machine-tgov1", ExampleNetworks::createTwoBusNetworkWithTgov1),
                fromRest("wind-turbine", ExampleNetworks::createWindTurbineNetwork),
                fromRest("zno-arrester", ExampleNetworks::createTwoBusNetworkWithZnOArrester),
                fromRest("induction-motor", ExampleNetworks::createTwoBusNetworkWithInductionMotor),
                // Converters, with the models their benches' files give them
                fromRest("vsc-grid-following", () -> bench("vsc-monopole-infinite-buses")),
                fromRest("vsc-grid-forming", () -> bench("vsc-monopole-infinite-buses-3")),
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
    private static Network bench(String bench) {
        return EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/" + bench + ".xiidm"),
                ExampleNetworks.resource("/" + bench + "-extensions.xml"));
    }

    private static Network bergeronLine() {
        Network network = ExampleNetworks.createTwoBusNetworkWithIdealInfiniteBusGenerator();
        network.getLine("L1_2").addExtension(BergeronLineModel.class, new BergeronLineModel(320.0, 3.3e-4));
        return network;
    }
}
