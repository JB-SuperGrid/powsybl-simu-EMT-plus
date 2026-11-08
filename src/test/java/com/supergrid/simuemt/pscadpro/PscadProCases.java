/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.GeneratorModel;
import com.powsybl.simuemt.extensions.event.BranchOperation;
import com.powsybl.simuemt.extensions.event.BreakerAction;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.ConverterBlocking;
import com.powsybl.simuemt.extensions.event.ConverterDeblocking;
import com.powsybl.simuemt.extensions.event.ConverterPowerStep;
import com.powsybl.simuemt.extensions.event.DcFault;
import com.powsybl.simuemt.extensions.event.DcSwitchOperation;
import com.powsybl.simuemt.extensions.event.EmtEvent;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.extensions.event.LoadStep;
import com.powsybl.simuemt.extensions.event.SwitchOperation;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.extensions.hvdc.control.FaultRideThrough;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;
import com.powsybl.simuemt.extensions.infinitebus.InfiniteBusGeneratorModel;
import com.powsybl.simuemt.extensions.line.BergeronLineModel;
import com.powsybl.simuemt.preprocessing.EmtEventsXml;
import com.powsybl.simuemt.preprocessing.EmtExtensionsXmlReader;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
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
 * converters and MMCs, faults, breakers, load steps, arresters, motors - from rest, and as a steady-state run's template. Together
 * they cover every upstream example network the backend writes, those past PSCAD Free's 15 electrical nodes among them; and the
 * converters' scenarios upstream runs in PSCAD Professional - blocked, deblocked, protected, through DC faults and DC breakers -, on
 * its benches and events, from 2.3 s: after the 2 s a converter started from rest is held on the load flow for.
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
    /** A converter's scenario's, as upstream runs it: 20 us, past its events from 2.3 s. */
    static final PscadSimulationSettings CONVERTERS = new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL);

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
                fromRest("cigre-b4", () -> bench("cigre-b4")),
                fromRest("ieee14-dc", () -> bench("ieee14-dc")),
                fromRest("kundur-dc", () -> bench("kundur-dc")),
                new Case("vsc-islanding", () -> bench("vsc-monopole-island"), List.of(ExampleNetworks.VSC_MONOPOLE_ISLANDING), SETTINGS,
                        Kind.FROM_REST),
                // Converters blocked, deblocked, protected, through DC faults and DC breakers: upstream's PSCAD Professional runs
                converters("wind-turbine-blocked", ExampleNetworks::createWindTurbineNetwork, new ConverterBlocking("WT1", 2.3)),
                converters("vsc-blocked", () -> preprocessed("vsc-three-terminal-all-droop", false, false), new ConverterBlocking("VSC1", 2.3)),
                converters("cigre-dcs2-cmf1-trip", () -> preprocessed("cigre-dcs2", false, false), new ConverterBlocking("CMF1", 2.3),
                        new ConverterBlocking("WTF1", 2.3)),
                // Upstream's DCS2 droops' power steps, the second at 3.5 s: 5 s
                new Case("cigre-dcs2-power-steps", () -> preprocessed("cigre-dcs2", false, false), List.of(new ConverterPowerStep("CMB2", 2.3, 0.125),
                        new ConverterPowerStep("CMB3", 3.5, -0.1)), new PscadSimulationSettings(20.0e-6, 5.0, 50.0, PscadEdition.PROFESSIONAL),
                        Kind.FROM_REST),
                converters("cigre-dcs2-ac-fault-bab3", () -> preprocessed("cigre-dcs2", false, false), new BusFault("FB3", "BAB3", "ABC", true, 0.0,
                        2.3, 2.4, FaultClearing.AT_CURRENT_ZERO)),
                converters("cigre-dcs2-dc-fault-bmf1", PscadProCases::dcs2DcFault, new DcFault("FDC", "BMF1", 0.0, 2.3, 12.0),
                        new ConverterBlocking("CMB2", 2.302), new ConverterBlocking("CMB3", 2.302), new ConverterBlocking("WTF1", 2.302),
                        new BranchOperation("TRB2", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
                        new BranchOperation("TRB3", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO)),
                // CIGRE B4 whole: a solid three-phase fault at BAB1, in its meshed AC system B, cleared after 100 ms
                // IEEE 14-bus with its DC grid: a solid three-phase fault at bus 4, between its machines and its stations, 100 ms
                converters("ieee14-dc-fault-b4", () -> preprocessed("ieee14-dc", false, false), new BusFault("F", "B4", "ABC", true, 1.0,
                        2.5, 2.6, FaultClearing.AT_CURRENT_ZERO)),
                // Kundur's two areas with their DC grid: a solid three-phase fault at bus 8, the tie's middle, 100 ms; run 8 s, the
                // inter-area swing's few periods after it
                new Case("kundur-dc-fault-b8", () -> preprocessed("kundur-dc", false, false), List.of(new BusFault("F", "B8", "ABC", true, 1.0,
                        2.5, 2.6, FaultClearing.AT_CURRENT_ZERO)), new PscadSimulationSettings(20.0e-6, 8.0, 50.0, PscadEdition.PROFESSIONAL),
                        Kind.FROM_REST),
                // ... and at bus 7, area 1's load and CS7's bus, at the tie's end: the inter-area mode excited more than from its middle -
                // run 2026-11-08, area 1 swinging 20 degrees against area 2 (under 1 from bus 8), at 0.6 Hz, zeta 0.025 without the PSSs,
                // 0.2 with them; the DC voltages within 0.94 and 1.06 pu, CS7 back on its power
                new Case("kundur-dc-fault-b7", () -> preprocessed("kundur-dc", false, false), List.of(new BusFault("F", "B7", "ABC", true, 1.0,
                        2.5, 2.6, FaultClearing.AT_CURRENT_ZERO)), new PscadSimulationSettings(20.0e-6, 8.0, 50.0, PscadEdition.PROFESSIONAL),
                        Kind.FROM_REST),
                converters("cigre-b4-fault-b1", () -> preprocessed("cigre-b4", false, false), new BusFault("F", "BAB1", "ABC", true, 1.0, 2.5, 2.6,
                        FaultClearing.AT_CURRENT_ZERO)),
                converters("vsc-deblocked", () -> preprocessed("vsc-monopole-infinite-buses-3", false, false), new ConverterBlocking("VSC2", 2.3),
                        new ConverterDeblocking("VSC2", 2.5)),
                converters("dc-fault-blocked", () -> preprocessed("vsc-three-terminal-all-droop", true, false), new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0),
                        new ConverterBlocking("VSC1", 2.302), new ConverterBlocking("VSC2", 2.302), new ConverterBlocking("VSC3", 2.302)),
                converters("dc-fault-protected", () -> preprocessed("vsc-three-terminal-all-droop", true, true), new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0)),
                converters("dc-breaker-selective", PscadProCases::dcBreaker, new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN), new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN,
                                FaultClearing.AT_CURRENT_ZERO)),
                converters("mmc-protected", () -> preprocessed("vsc-three-terminal-all-droop-mmc", false, true), new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0)),
                converters("mmc-restart", PscadProCases::mmcRestart, new DcFault("FDC", "DC3P", 0.0, 2.3, 2.5),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN), new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN,
                                FaultClearing.AT_CURRENT_ZERO), new DcSwitchOperation("DCB", 2.6, BreakerAction.CLOSE),
                        new BranchOperation("T3", TwoSides.ONE, 2.7, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO), new ConverterDeblocking("VSC3", 2.8)),
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

    /** A converters' scenario, from rest at {@link #CONVERTERS}' settings. */
    private static Case converters(String name, Supplier<Network> network, EmtEvent... events) {
        return new Case(name, network, List.of(events), CONVERTERS, Kind.FROM_REST);
    }

    /**
     * {@link #bench}, its generators given the pipeline's defaults ({@link EmtNetworkPreprocessor}), as upstream's DC benches take them -
     * each converter with a 100 mH DC reactor first if {@code dcReactors}, protected against its arms' overcurrent at 2 pu if {@code
     * protect}, as upstream's DC fault scenarios have them.
     */
    static Network preprocessed(String bench, boolean dcReactors, boolean protect) {
        Network network = bench(bench);
        for (VoltageSourceConverter converter : network.getVoltageSourceConverters()) {
            if (dcReactors) {
                withDcReactor(converter);
            }
            if (protect) {
                converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0));
            }
        }
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** {@code converter}'s averaged model with a 100 mH DC reactor between its capacitor and its pole. */
    private static void withDcReactor(VoltageSourceConverter converter) {
        AveragedVscModel model = (AveragedVscModel) converter.getExtension(VoltageSourceConverterModel.class);
        converter.addExtension(VoltageSourceConverterModel.class, new AveragedVscModel(model.getRatedApparentPower(), model.getPhaseReactorResistance(),
                model.getPhaseReactorReactance(), model.getDcCapacitorEnergyConstant(), 0.1, model.getBridgeInductance()));
    }

    /** CIGRE DCS2, its stations protected, CMF1 - the faulted node's - with a DC reactor, as upstream's DC fault at BMF1 has it. */
    private static Network dcs2DcFault() {
        Network network = bench("cigre-dcs2");
        withDcReactor(network.getVoltageSourceConverter("CMF1"));
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** Example 4 with its DC breaker DCB, its stations protected, VSC3 alone with a DC reactor, as upstream's selective clearing has it. */
    private static Network dcBreaker() {
        Network network = bench("vsc-three-terminal-dc-breaker");
        withDcReactor(network.getVoltageSourceConverter("VSC3"));
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** Example 4's MMC grid with its DC breaker, its stations protected, VSC3 with an active power recovery ramp of 2 pu/s, as upstream's
     * restart after a DC fault has it. */
    private static Network mmcRestart() {
        Network network = bench("vsc-three-terminal-dc-breaker-mmc");
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        VoltageSourceConverter vsc3 = network.getVoltageSourceConverter("VSC3");
        vsc3.addExtension(VscControlModel.class, GridFollowingControl.of(vsc3).withFaultRideThrough(new FaultRideThrough(0.9, 2.0, 2.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** A bench of powsybl-simu-emt's test resources: {@code <bench>.xiidm} with its models, {@code <bench>-extensions.xml} - but
     * {@code cigre-b4}, {@code ieee14-dc} and {@code kundur-dc}, this project's own ({@link CigreB4Network}, {@link Ieee14DcNetwork},
     * {@link KundurDcNetwork}). */
    static Network bench(String bench) {
        // This project's own, not upstream's.
        if ("cigre-b4".equals(bench)) {
            return CigreB4Network.load();
        }
        if ("ieee14-dc".equals(bench)) {
            return Ieee14DcNetwork.load();
        }
        if ("kundur-dc".equals(bench)) {
            return KundurDcNetwork.load();
        }
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
