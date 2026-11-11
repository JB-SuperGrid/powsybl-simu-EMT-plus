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
import com.powsybl.simuemt.extensions.excitation.ExcitationSystemModel;
import com.powsybl.simuemt.extensions.hvdc.AutomaticRestart;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.OvercurrentProtection;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.VscProtectionModel;
import com.powsybl.simuemt.extensions.hvdc.control.FaultRideThrough;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.SrfPll;
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
import java.util.ArrayList;
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
    /** {@link #CONVERTERS}, switching on the time step as ATP and Kestrel do ({@link PscadSwitching#ON_TIME_STEP}): for a crest held to
     * theirs - PSCAD's interpolated switching opens a DC breaker inside the step, on less current, its crest 1.1% lower. */
    static final PscadSimulationSettings CONVERTERS_ON_TIME_STEP = new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL,
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
                fromRest("machine-ac6a", ExampleNetworks::createTwoBusNetworkWithAc6A),
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
                // Upstream's catalogue's other Professional examples: the VSC monopole with its parallel AC line, three ways, and example 4
                // on its master and slave
                fromRest("vsc-monopole", () -> preprocessed("vsc-monopole", false, false)),
                fromRest("vsc-monopole-2", () -> preprocessed("vsc-monopole-2", false, false)),
                fromRest("vsc-monopole-3", () -> preprocessed("vsc-monopole-3", false, false)),
                fromRest("vsc-master-slave", () -> preprocessed("vsc-three-terminal", false, false)),
                fromRest("cigre-dcs2", () -> bench("cigre-dcs2")),
                fromRest("cigre-b4", () -> bench("cigre-b4")),
                fromRest("ieee14-dc", () -> bench("ieee14-dc")),
                fromRest("kundur-dc", () -> bench("kundur-dc")),
                fromRest("ieee39-dc", () -> bench("ieee39-dc")),
                fromRest("ieee68-dc", () -> bench("ieee68-dc")),
                fromRest("ieee39-sync", () -> bench("ieee39-sync")),
                fromRest("ieee39-gfl", () -> bench("ieee39-gfl")),
                fromRest("ieee39-gfm", () -> bench("ieee39-gfm")),
                fromRest("ieee39-gfl65", () -> bench("ieee39-gfl65")),
                fromRest("ieee39-gfm65", () -> bench("ieee39-gfm65")),
                fromRest("ieee39-mix65", () -> bench("ieee39-mix65")),
                fromRest("promotion-dc", () -> bench("promotion-dc")),
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
                // IEEE 39-bus with its DC grid: a solid three-phase fault at bus 16, its hub and CS16's - the DC voltage's - bus,
                // 100 ms; run 8 s. Run 2026-11-08: New England's nine machines swinging together against G39, the rest of the
                // interconnection, up to 33 degrees at 0.6 Hz, zeta 0.17 on their PSS1As - the pipeline's default, not tuned for it
                // (0.02 to 0.035 and 39 degrees with them switched off); CS16's AC bus down to 0.16 pu, its power reversed for a
                // while, the DC voltages within 0.94 and 1.11 pu, every station back on its power
                new Case("ieee39-dc-fault-b16", () -> preprocessed("ieee39-dc", false, false), List.of(new BusFault("F", "B16", "ABC", true,
                        1.0, 2.5, 2.6, FaultClearing.AT_CURRENT_ZERO)), new PscadSimulationSettings(20.0e-6, 8.0, 50.0, PscadEdition.PROFESSIONAL),
                        Kind.FROM_REST),
                // ... and its DC grid faulted: a solid pole-to-ground fault at BD16, its hub and CS16's DC node, CS16 with a DC reactor,
                // its stations protected
                // against their arms' overcurrent; no DC breaker, it is cleared the non-selective way PROMOTioN's D4.2 has it: every
                // station and the wind park blocked on the fault's detection, 2 ms on, then the stations' AC breakers opening, 40 ms
                // on - the whole DC grid lost to New England, its wind park's 400 MW and its stations' transits; run 8 s
                new Case("ieee39-dc-dc-fault-bd16-cleared", PscadProCases::ieee39DcFault, ieee39DcFaultCleared(),
                        new PscadSimulationSettings(20.0e-6, 8.0, 50.0, PscadEdition.PROFESSIONAL), Kind.FROM_REST),
                // IEEE 68-bus with its DC grid: a solid three-phase fault at bus 1, New England's end of its ties to New York, 100 ms -
                // the benchmark's own, PST's; run 8 s at 50 us. Run 2026-11-08: on the data's PSSs, every machine's speed swinging
                // within 0.004 Hz peak to peak by 7 s, the inter-area swings at 0.57 to 0.71 Hz damped at zeta 0.15 to 0.21 - New
                // England against New York up to 3.8 degrees, area 5 against New York 3.1; with them switched off, the data's fast
                // static exciters (a gain of 100) leave its local modes undamped, G61's speed swinging wider and wider (0.10 Hz peak to
                // peak 1 s after the fault, 0.22 Hz 5 s after), G62's and G63's still 0.34 and 0.40 Hz, New England against New York
                // up to 7.8 degrees. Either way the DC grid rides it: CS33's AC bus down to 0.70 pu, the DC voltages within 0.984 and
                // 1.008 pu, every station back on its power
                // IEEE 39 with a high share of power electronics (Ieee39IbrNetwork): its machines, then four of them replaced by
                // grid-following, then by grid-forming converter sources, through a load step and a fault at bus 16; run 8 s at 50 us.
                // Run 2026-11-08. Bus 16's load up 494 MW: the centre of inertia's frequency down 80 mHz with the machines, its RoCoF
                // over 500 ms -0.075 Hz/s; 110 mHz and -0.100 Hz/s grid-following, the converters on their power, the inertia gone
                // with the machines; 67 mHz and -0.048 Hz/s grid-forming, the converters' virtual machines (H 5 s, D 20) taking up
                // 0.02 to 0.03 pu more each; New England against G39 at 0.60 Hz, zeta 0.23, at 0.63 Hz, zeta 0.18, and at 0.50 Hz,
                // zeta 0.39. A solid three-phase fault, 100 ms: with the machines, New England swinging 21 degrees against G39 at
                // 0.60 Hz, zeta 0.17; grid-following, every station riding it - CL38's PLL slowed to 5 Hz, bus 29 too weak for the
                // others' 10 Hz (ieee39-gfl-extensions.xml) -, back on its power within 0.02 pu 0.10 to 0.26 s after the clearing,
                // CL38 last, but New England, with no inertia of the converters', swinging 33 degrees at 0.68 Hz, zeta 0.16;
                // grid-forming, every station riding it, CL35's AC voltage down to 0.35 pu, the virtual machines swinging with the
                // grid, back within 0.02 pu only 2.3 to 2.8 s after the clearing, New England swinging 20 degrees at 0.46 Hz, zeta 0.30
                ibrCase("ieee39-sync-fault-b16", "ieee39-sync", IBR_FAULT),
                ibrCase("ieee39-gfl-fault-b16", "ieee39-gfl", IBR_FAULT),
                ibrCase("ieee39-gfm-fault-b16", "ieee39-gfm", IBR_FAULT),
                ibrCase("ieee39-sync-load-step-b16", "ieee39-sync", IBR_LOAD_STEP),
                ibrCase("ieee39-gfl-load-step-b16", "ieee39-gfl", IBR_LOAD_STEP),
                ibrCase("ieee39-gfm-load-step-b16", "ieee39-gfm", IBR_LOAD_STEP),
                // ... and with six machines replaced, 67 %, G31, G34, G37 and G39 left - run 2026-11-08. The load step: the nadir
                // 141 mHz grid-following, its RoCoF -0.115 Hz/s, New England against G39 at 0.75 Hz, zeta 0.12; 64 mHz grid-forming,
                // -0.038 Hz/s, zeta 0.51. The fault: grid-following, the network lost - its voltages held near 0.85 pu after the
                // clearing, G31, the machine nearest the fault, speeding up to 1.057 pu and out of step within 0.7 s, every station
                // then swinging past 1.3 or under 0.3 pu, at 20 us as at 50 us; grid-forming, every station riding it, back on its
                // power within 0.02 pu 0.8 to 2.0 s after the clearing, the machines' speeds swinging within 0.005 Hz by 7 s
                ibrCase("ieee39-gfl65-fault-b16", "ieee39-gfl65", IBR_FAULT),
                ibrCase("ieee39-gfm65-fault-b16", "ieee39-gfm65", IBR_FAULT),
                // ... and how much grid-forming keeps the 67 % network through the fault, one station's control changed at a time,
                // run 2026-11-08: CL33 alone - 800 MVA, bus 19, beside the fault - and the network holds, G31 up to 1.0085 pu
                // instead of out of step, the grid-following stations back on their power within 0.02 pu 0.11 to 0.17 s after the
                // clearing, CL33 2.9 s, the machines' speeds swinging within 0.007 Hz by 7 s; CL38 alone, 1000 MVA at bus 29, far
                // from it, holds it too, G31 up to 1.0071 pu; CL30 alone, 300 MVA at bus 2, does not, the network lost as without
                // any. With CL32 and CL36 too, G31 up to 1.0057 and 1.0052 pu: a grid-forming station's size more than its place
                ibrCase("ieee39-mix65-fault-b16", "ieee39-mix65", IBR_FAULT),
                ibrCase("ieee39-gfl65-load-step-b16", "ieee39-gfl65", IBR_LOAD_STEP),
                ibrCase("ieee39-gfm65-load-step-b16", "ieee39-gfm65", IBR_LOAD_STEP),
                new Case("ieee68-dc-fault-b1", () -> preprocessed("ieee68-dc", false, false), List.of(new BusFault("F", "B1", "ABC", true,
                        1.0, 2.5, 2.6, FaultClearing.AT_CURRENT_ZERO)), new PscadSimulationSettings(50.0e-6, 8.0, 50.0, PscadEdition.PROFESSIONAL),
                        Kind.FROM_REST),
                // The three large networks each losing a station in full flow: blocked at 2.5 s, its AC breaker opening 40 ms on - its
                // transit taken up by the others along their laws, the AC network shifting its flows. Kundur's CS7, area 1's 200 MW
                // to area 2 back on the tie; IEEE 39's CS4, 350 MW into the load centre now through CS16 at the hub; IEEE 68's CS33,
                // New York's 400 MW import from the DC grid, at 50 us as its fault
                stationLoss("kundur-dc-loss-cs7", "kundur-dc", "7", 20.0e-6),
                stationLoss("ieee39-dc-loss-cs4", "ieee39-dc", "4", 20.0e-6),
                stationLoss("ieee68-dc-loss-cs33", "ieee68-dc", "33", 50.0e-6),
                // ... and each its V_DC station: nothing holding the DC voltage any more, the droops left to settle it where they
                // balance the power. Kundur's CS9, area 2's 400 MW: CS7, its only droop, reversed to deliver the wind's 200 MW to area
                // 1 instead, some 53 kV up; IEEE 39's CS16 and IEEE 68's, their transit shared by the others along their droops
                stationLoss("kundur-dc-loss-cs9", "kundur-dc", "9", 20.0e-6),
                stationLoss("ieee39-dc-loss-cs16", "ieee39-dc", "16", 20.0e-6),
                stationLoss("ieee68-dc-loss-cs16", "ieee68-dc", "16", 50.0e-6),
                // PROMOTioN's five terminals, each cable between two hybrid DC breakers and their 200 mH reactors: a solid pole-to-pole
                // fault at D1, the most meshed node, its stations protected against their arms' overcurrent - run 2026-11-08: every
                // station blocked, nearest first, D1 0.4 ms on, C2 13, D2 13, B1 36, A1 94, their DC currents up to 4.8 to 5.4 pu;
                // blocked, the half-bridges' diodes feeding the fault from the AC grids, 4.4 to 4.8 pu from each onshore one, their PCCs
                // down to 0.25 to 0.29 pu, which nothing clears: no breaker opens here -, the DC voltages never up to A1's and B1's
                // choppers
                converters("promotion-dc-fault-d1", () -> preprocessed("promotion-dc", false, true), new DcFault("FDC", "BD1", 0.0, 2.3, 12.0)),
                // ... and cleared the non-selective way, as PROMOTioN's D4.2 has it: every station blocked on the fault's detection,
                // 2 ms on, then its AC breaker opening, 40 ms on - run 2026-11-08: the AC voltages within 1.22 pu, the DC voltages
                // down to -51 kV; the AC grids cut off, the DC currents freewheeling through the diodes into the fault, decaying with
                // some 0.3 to 0.4 s, D1's gone in 380 ms, the others 0.2 to 0.5 pu still 0.7 s on: some 2 s to the 10 A D4.2's
                // high-speed switches open at, as D4.2 finds the AC breakers' strategy's (1.1 s and more)
                converters("promotion-dc-fault-d1-cleared", () -> preprocessed("promotion-dc", false, true), promotionDcFaultCleared()),
                // ... and on cable D1-D2 at D1's end, cleared the fully selective way, as D4.2 has it: the cable's two breakers opened
                // 3 ms on, the fault left on - run 2026-11-08: no station blocked, D1's DC current up to 3.9 pu, the others' within 1.8
                // pu, the DC voltages within 0.87 and 1.10 pu, the AC voltages within 0.98 and 1.02 pu; the cable cut off, its D2 end
                // ringing down, the meshed rest carrying the wind parks' power, every station on its power and its DC voltage within
                // 1.4e-3 pu of its own before, by 3 s
                converters("promotion-dc-fault-cd1d2", () -> preprocessed("promotion-dc", false, true), new DcFault("FDC", "BCD1D2_D1", 0.0, 2.3,
                        12.0), DcSwitchOperation.opening("KCD1D2_D1", 2.303), DcSwitchOperation.opening("KCD1D2_D2", 2.303)),
                // A solid three-phase fault at A1's grid, the DC voltage's station, 100 ms - run 2026-11-08: A1's AC voltage down to
                // 0.26 pu, its power gone; B1 taking what its droop can, to its current limit, the wind parks theirs still: the DC
                // voltages up to 1.087 pu, held there by A1's and B1's choppers, 0.49 and 0.25 pu at most, conducting through the fault
                // and up to 85 ms after (1.24 pu without them); cleared, A1's AC voltage up to 1.41 pu, every station back on its power, the DC
                // voltages within 1.1e-3 pu of theirs before, by 3 s
                converters("promotion-dc-ac-fault-a1", () -> preprocessed("promotion-dc", false, false), new BusFault("F", "BGA1", "ABC", true, 1.0,
                        2.3, 2.4, FaultClearing.AT_CURRENT_ZERO)),
                // ... and at B1's grid, the droop's station, 100 ms; run 4 s. Run 2026-11-08: B1's AC voltage down to 0.25 pu, its power
                // gone, A1 at its current limit, the DC voltages up to 1.11 pu, B1's chopper burning the surplus; cleared, B1's power
                // back along its 2 pu/s recovery ramp - slower, 0.8 pu/s on average, the droop's reference rising with the DC voltage -,
                // within 0.02 pu of its own 1.38 s after the clearing (0.5 s without the ramp), A1 at its limit until 0.8 s after it
                // (0.08 s without), B1's chopper burning 0.28 pu s (0.064 without); every station on its power, the DC voltages within
                // 6e-4 pu of theirs before, by 4 s
                new Case("promotion-dc-ac-fault-b1", () -> preprocessed("promotion-dc", false, false), List.of(new BusFault("F", "BGB1", "ABC", true,
                        1.0, 2.3, 2.4, FaultClearing.AT_CURRENT_ZERO)), new PscadSimulationSettings(20.0e-6, 4.0, 50.0, PscadEdition.PROFESSIONAL),
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
                // ... and VSC3 tripped again by a second fault on DC3P, DCB opened a second time: three operations of DCB
                converters("mmc-restart-refault", PscadProCases::mmcRestart, new DcFault("FDC", "DC3P", 0.0, 2.3, 2.5),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN), new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN,
                                FaultClearing.AT_CURRENT_ZERO), new DcSwitchOperation("DCB", 2.6, BreakerAction.CLOSE),
                        new BranchOperation("T3", TwoSides.ONE, 2.7, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO), new ConverterDeblocking("VSC3", 2.8),
                        new DcFault("FDC2", "DC3P", 0.0, 3.2, 3.4), new DcSwitchOperation("DCB", 3.202, BreakerAction.OPEN)),
                // ... and VSC3 restarted by its protection, with no order to (AutomaticRestart): three faults on DC3P, DCB left open, T3
                // reclosed after the first; and three faults each cleared by DCB and T3, both reclosed after each
                converters("mmc-restart-itself", () -> mmcRestartsItself(0.5), new DcFault("FDC1", "DC3P", 0.0, 2.3, 2.5),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN),
                        new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
                        new BranchOperation("T3", TwoSides.ONE, 2.55, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO),
                        new DcFault("FDC2", "DC3P", 0.0, 3.2, 3.25), new DcFault("FDC3", "DC3P", 0.0, 3.9, 3.95)),
                converters("mmc-restart-cycled", () -> mmcRestartsItself(0.8), new DcFault("FDC1", "DC3P", 0.0, 2.3, 2.5),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN),
                        new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
                        new BranchOperation("T3", TwoSides.ONE, 2.55, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO),
                        new DcSwitchOperation("DCB", 2.6, BreakerAction.CLOSE),
                        new DcFault("FDC2", "DC3P", 0.0, 3.5, 3.7),
                        new DcSwitchOperation("DCB", 3.502, BreakerAction.OPEN),
                        new BranchOperation("T3", TwoSides.ONE, 3.56, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
                        new BranchOperation("T3", TwoSides.ONE, 3.75, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO),
                        new DcSwitchOperation("DCB", 3.8, BreakerAction.CLOSE),
                        new DcFault("FDC3", "DC3P", 0.0, 4.7, 4.9),
                        new DcSwitchOperation("DCB", 4.702, BreakerAction.OPEN),
                        new BranchOperation("T3", TwoSides.ONE, 4.76, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
                        new BranchOperation("T3", TwoSides.ONE, 4.95, BreakerAction.CLOSE, FaultClearing.AT_CURRENT_ZERO),
                        new DcSwitchOperation("DCB", 5.0, BreakerAction.CLOSE)),
                // Example 4's controls, as ATP, Xyce and Kestrel share them: master-slave, VSC3's power reference stepped by 0.2 pu at
                // 2.5 s; all on the droop, ramped by as much over 0.2 s; VSC3's power back along its 2 pu/s recovery ramp after a solid
                // three-phase fault on B3, 100 ms, on either bench
                converters("vsc-master-slave-step", () -> preprocessed("vsc-three-terminal", false, false), new ConverterPowerStep("VSC3", 2.5, 0.2)),
                converters("vsc-droop-ramp", () -> preprocessed("vsc-three-terminal-all-droop", false, false), new ConverterPowerStep("VSC3", 2.5, 0.2, 0.2)),
                converters("vsc-recovery-ramp", () -> recoveryRamp("vsc-three-terminal"), B3_FAULT),
                converters("vsc-droop-recovery-ramp", () -> recoveryRamp("vsc-three-terminal-all-droop"), B3_FAULT),
                // Example 3's grid-forming VSC2, as ATP, Xyce and Kestrel share it: its power reference stepped by 0.1 pu at 5 s - its
                // virtual machine's slow swing from rest settled by then, still 7e-3 pu off at 2.5 s
                converters("vsc-gfm-step", () -> preprocessed("vsc-monopole-infinite-buses-3", false, false), new ConverterPowerStep("VSC2", 5.0, 0.1)),
                // ... and blocked at 4.8 s and deblocked at 5 s with no steady-state current limit, resynchronised by a PLL of its own, on
                // its virtual machine and on a power-frequency droop
                converters("vsc-gfm-resync-pll", () -> resynchronized(false), new ConverterBlocking("VSC2", 4.8), new ConverterDeblocking("VSC2", 5.0)),
                converters("vsc-gfm-droop-resync-pll", () -> resynchronized(true), new ConverterBlocking("VSC2", 4.8), new ConverterDeblocking("VSC2", 5.0)),
                // ... and those ATP, Xyce and Kestrel share that no PSCAD has run: the solid fault on DC3P cut off by the stations' AC breakers,
                // two-level and MMC; the MMC grid's cleared selectively by DCB; the meshed MMC grid's faulted line cut off at both ends
                converters("dc-fault-stations-open", () -> preprocessed("vsc-three-terminal-all-droop", true, true), stationsOpen()),
                converters("mmc-stations-open", () -> preprocessed("vsc-three-terminal-all-droop-mmc", false, true), stationsOpen()),
                new Case("mmc-dc-breaker-selective", () -> preprocessed("vsc-three-terminal-dc-breaker-mmc", false, true),
                        List.of(new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0), new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN),
                                new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO)), CONVERTERS_ON_TIME_STEP,
                        Kind.FROM_REST),
                converters("mmc-meshed-line-cut", () -> preprocessed("vsc-three-terminal-meshed-mmc", false, true), new DcFault("FDC", "DC2M", 0.0, 2.3, 2.5),
                        new DcSwitchOperation("DCB", 2.302, BreakerAction.OPEN), new DcSwitchOperation("DCB3", 2.302, BreakerAction.OPEN),
                        new DcSwitchOperation("DCB", 2.6, BreakerAction.CLOSE), new DcSwitchOperation("DCB3", 2.6, BreakerAction.CLOSE)),
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
                new Case("template-machine", ExampleNetworks::createTwoBusNetworkWithAc1A, List.of(), SETTINGS, Kind.TEMPLATE),
                new Case("template-machine-ac6a-limiter", PscadProCases::ac6aLimiterSwingBench, events("fault-b2-a-1.5-1.6"),
                        new PscadSimulationSettings(20.0e-6, 3.0, 50.0, PscadEdition.PROFESSIONAL, PscadSwitching.ON_TIME_STEP), Kind.TEMPLATE));
    }

    private static Case fromRest(String name, Supplier<Network> network) {
        return new Case(name, network, List.of(), SETTINGS, Kind.FROM_REST);
    }

    /** A converters' scenario, from rest at {@link #CONVERTERS}' settings. */
    /** IEEE 39's high power electronics variants' events: a solid three-phase fault at bus 16, 100 ms; bus 16's load stepped up 2.5
     * times, by 494 MW. */
    private static final EmtEvent IBR_FAULT = new BusFault("F", "B16", "ABC", true, 1.0, 2.5, 2.6, FaultClearing.AT_CURRENT_ZERO);
    private static final EmtEvent IBR_LOAD_STEP = new LoadStep("L16", 2.5, 2.5);

    /** One of IEEE 39's high power electronics variants ({@link Ieee39IbrNetwork}) through {@code event}: run 8 s at 50 us. */
    private static Case ibrCase(String name, String bench, EmtEvent event) {
        return new Case(name, () -> preprocessed(bench, false, false), List.of(event), new PscadSimulationSettings(50.0e-6, 8.0, 50.0,
                PscadEdition.PROFESSIONAL), Kind.FROM_REST);
    }

    /** {@code bench}'s station CS{@code station} lost: blocked at 2.5 s, its transformer TC{@code station} opened at its grid's end at
     * 2.54 s, at its current's zero; run 8 s at {@code timeStep}, from rest. */
    private static Case stationLoss(String name, String bench, String station, double timeStep) {
        return new Case(name, () -> preprocessed(bench, false, false), List.of(new ConverterBlocking("CS" + station, 2.5),
                new BranchOperation("TC" + station, TwoSides.ONE, 2.54, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO)),
                new PscadSimulationSettings(timeStep, 8.0, 50.0, PscadEdition.PROFESSIONAL), Kind.FROM_REST);
    }

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

    /** IEEE 39 with its DC grid, its stations protected, CS16 - the faulted node's - with a DC reactor, as CIGRE DCS2's DC fault has
     * CMF1: without one its capacitor discharges into the solid fault at its terminals unbounded. */
    private static Network ieee39DcFault() {
        Network network = bench("ieee39-dc");
        withDcReactor(network.getVoltageSourceConverter("CS16"));
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** CIGRE DCS2, its stations protected, CMF1 - the faulted node's - with a DC reactor, as upstream's DC fault at BMF1 has it. */
    private static Network dcs2DcFault() {
        Network network = bench("cigre-dcs2");
        withDcReactor(network.getVoltageSourceConverter("CMF1"));
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** PROMOTioN's pole-to-pole fault at D1, cleared the non-selective way of its D4.2: every station blocked on the fault's detection,
     * 2 ms on, then its AC breaker - its transformer's grid side - opening 40 ms on, D4.2's opening time, at its current's zero. Not
     * blocked first, A1, far from the fault, is still running when its breaker opens, 105 ms before its arms' overcurrent blocks it:
     * run so, its AC voltage rose to 2.7 pu and its DC voltage to -900 kV. */
    /** IEEE 39's DC grid faulted at BD16 from 2.5 s, every station and the wind park blocked 2 ms on, the stations' AC breakers
     * ordered open 40 ms on. */
    private static List<EmtEvent> ieee39DcFaultCleared() {
        List<EmtEvent> events = new ArrayList<>(List.of(new DcFault("FDC", "BD16", 0.0, 2.5, 12.0), new ConverterBlocking("WTW", 2.502)));
        for (String station : List.of("4", "16", "26", "W")) {
            events.add(new ConverterBlocking("CS" + station, 2.502));
            events.add(new BranchOperation("TC" + station, TwoSides.ONE, 2.54, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO));
        }
        return events;
    }

    private static EmtEvent[] promotionDcFaultCleared() {
        List<EmtEvent> events = new ArrayList<>(List.of(new DcFault("FDC", "BD1", 0.0, 2.3, 12.0)));
        for (String station : List.of("A1", "B1", "C2", "D1", "D2")) {
            events.add(new ConverterBlocking("C" + station, 2.302));
            events.add(new BranchOperation("T" + station, TwoSides.ONE, 2.34, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO));
        }
        return events.toArray(EmtEvent[]::new);
    }

    /** Example 4 with its DC breaker DCB, its stations protected, VSC3 alone with a DC reactor, as upstream's selective clearing has it. */
    private static Network dcBreaker() {
        Network network = bench("vsc-three-terminal-dc-breaker");
        withDcReactor(network.getVoltageSourceConverter("VSC3"));
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** A solid fault on DC3P from 2.3 s, the three stations' AC breakers - their transformers' grid ends - ordered open 60 ms later, at
     * their currents' zeros: upstream's {@code dc-fault-dc3p-solid-0.3-stations-open-0.36}, 2 s later. */
    private static EmtEvent[] stationsOpen() {
        return new EmtEvent[] {new DcFault("FDC", "DC3P", 0.0, 2.3, 12.0), new BranchOperation("T1", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
            new BranchOperation("T2", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO),
            new BranchOperation("T3", TwoSides.ONE, 2.36, BreakerAction.OPEN, FaultClearing.AT_CURRENT_ZERO)};
    }

    /** Example 4's MMC grid with its DC breaker, its stations protected, VSC3 with an active power recovery ramp of 2 pu/s, as upstream's
     * restart after a DC fault has it. */
    /** Example 3 between infinite buses, its grid-forming VSC2 - on its virtual machine, or on a power-frequency droop if {@code droop} -
     * with no steady-state current limit, resynchronised by a {@code resynchronizationPll} of that limit's PLL's gains: upstream's {@code
     * AtpSolverIntegrationTest#resynchronizedStation}. */
    private static Network resynchronized(boolean droop) {
        Network network = bench("vsc-monopole-infinite-buses-3");
        if (droop) {
            ExampleNetworks.withThirdVscExampleDroop(network);
        }
        VoltageSourceConverter vsc2 = network.getVoltageSourceConverter("VSC2");
        GridFormingControl control = GridFormingControl.of(vsc2);
        SrfPll limitsPll = control.getSteadyStateCurrentLimit().orElseThrow().pll();
        vsc2.addExtension(VscControlModel.class, new GridFormingControl(control.getSynchronization(), control.getVoltageDroop(), control.getVirtualAdmittance(),
                control.getCurrentControl(), control.getCurrentLimit(), control.getMeasurement(), null, control.getTransientDamping().orElse(null),
                control.getDcVoltageDroop().orElse(null), limitsPll));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** Upstream's {@code fault-b3-abc-0.3-0.4}, 2 s later: a solid three-phase fault on B3 from 2.3 to 2.4 s. */
    private static final EmtEvent B3_FAULT = shifted((BusFault) events("fault-b3-abc-0.3-0.4").get(0));

    private static BusFault shifted(BusFault fault) {
        return new BusFault(fault.id(), fault.busId(), fault.phases(), fault.earthed(), fault.resistanceOhm(), fault.closeTimeSeconds() + 2.0,
                fault.openTimeSeconds() + 2.0, fault.clearing());
    }

    /** {@code bench}, VSC3's fault ride-through with an active power recovery ramp of 2 pu/s - upstream's {@code recoveryRampStation}. */
    private static Network recoveryRamp(String bench) {
        Network network = bench(bench);
        VoltageSourceConverter vsc3 = network.getVoltageSourceConverter("VSC3");
        vsc3.addExtension(VscControlModel.class, GridFollowingControl.of(vsc3).withFaultRideThrough(new FaultRideThrough(0.9, 2.0, 2.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** {@link #mmcRestart}, VSC3's protection restarting it ({@link AutomaticRestart}): its DC voltage above {@code threshold} pu for 20 ms,
     * blocked 0.1 s at least, twice at most - upstream's {@code restartedStationBench} (0.5) and {@code cycledRestartBench} (0.8). */
    private static Network mmcRestartsItself(double threshold) {
        Network network = mmcRestart();
        network.getVoltageSourceConverter("VSC3").addExtension(VscProtectionModel.class,
                new OvercurrentProtection(2.0, 0.0, new AutomaticRestart(threshold, 0.02, 0.1, 2)));
        return network;
    }

    private static Network mmcRestart() {
        Network network = bench("vsc-three-terminal-dc-breaker-mmc");
        network.getVoltageSourceConverters().forEach(converter -> converter.addExtension(VscProtectionModel.class, new OvercurrentProtection(2.0, 0.0)));
        VoltageSourceConverter vsc3 = network.getVoltageSourceConverter("VSC3");
        vsc3.addExtension(VscControlModel.class, GridFollowingControl.of(vsc3).withFaultRideThrough(new FaultRideThrough(0.9, 2.0, 2.0)));
        EmtNetworkPreprocessor.preprocess(network, LocalComputationManager.getDefault());
        return network;
    }

    /** A bench of powsybl-simu-emt's test resources: {@code <bench>.xiidm} with its models, {@code <bench>-extensions.xml} - but
     * {@code cigre-b4}, {@code ieee14-dc}, {@code kundur-dc}, {@code ieee39-dc} and {@code promotion-dc}, this project's own ({@link
     * CigreB4Network}, {@link Ieee14DcNetwork}, {@link KundurDcNetwork}, {@link Ieee39DcNetwork}, {@link PromotionDcNetwork}). */
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
        if ("ieee39-dc".equals(bench)) {
            return Ieee39DcNetwork.load();
        }
        if ("promotion-dc".equals(bench)) {
            return PromotionDcNetwork.load();
        }
        if ("ieee68-dc".equals(bench)) {
            return Ieee68DcNetwork.load();
        }
        if ("ieee39-sync".equals(bench)) {
            return Ieee39IbrNetwork.loadSync();
        }
        if ("ieee39-gfl".equals(bench) || "ieee39-gfm".equals(bench)) {
            return Ieee39IbrNetwork.loadIbr("ieee39-gfm".equals(bench));
        }
        if ("ieee39-mix65".equals(bench)) {
            return Ieee39IbrNetwork.loadMix65();
        }
        if ("ieee39-gfl65".equals(bench) || "ieee39-gfm65".equals(bench)) {
            return Ieee39IbrNetwork.loadIbr65("ieee39-gfm65".equals(bench));
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
    /**
     * Upstream's cross-backend swing bench under an AC6A whose field current limiter acts ({@link
     * ExampleNetworks#ac6AModelWithFieldCurrentLimit}): the two-bus network, its line charged with 2.5e-4 S at each end and each end
     * compensated by a bare reactor of the same susceptance - which only a run restarted from a patched snapshot takes a fault on.
     */
    private static Network ac6aLimiterSwingBench() {
        Network network = EmtExtensionsXmlReader.readNetwork(ExampleNetworks.resource("/two-bus.xiidm"), ExampleNetworks.resource("/two-bus-extensions.xml"));
        double charging = 2.5e-4;
        network.getLine("L1_2").setB1(charging).setB2(charging);
        for (String busId : List.of("B1", "B2")) {
            network.getBusBreakerView().getBus(busId).getVoltageLevel().newShuntCompensator().setId("REACTOR_" + busId).setBus(busId)
                    .setConnectableBus(busId).setSectionCount(1)
                    .newLinearModel().setBPerSection(-charging).setGPerSection(0.0).setMaximumSectionCount(1).add()
                    .add();
        }
        network.getGenerator("GEN").addExtension(ExcitationSystemModel.class, ExampleNetworks.ac6AModelWithFieldCurrentLimit());
        return network;
    }

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
