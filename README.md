# SimuEmt Plus

SuperGrid Institute's overlay on [powsybl-simu-emt](https://github.com/JB-H/powsybl-simu-EMT): a **PSCAD Professional**
backend. Proprietary, all rights reserved (see `LICENSE`).

`powsybl-simu-emt-pscad` writes a `.pscx` for PSCAD Free, which has no automation library: the project is opened and run
by hand. This project runs **the same `.pscx`** through PSCAD's automation library (`mhi.pscad`, Professional and
Educational editions): PSCAD is launched, loads the file, builds it, compiles it, runs it, and reports where EMTDC wrote its
output.

| Upstream (Free)                         | Here (Professional)                                              |
|-----------------------------------------|------------------------------------------------------------------|
| `PscadProjectSerializer.PSCX` → `.pscx` | the same                                                         |
| opened and run by hand                  | `PscadProRunner` has PSCAD load, build and run it (`pscad_run.py`) |
| `PscadOutput` on the `.gf132` folder    | `PscadOutput` on the compiler's folder (`.if18`, ...)            |

Circuit, events, template and restart all stay `PscadFileWriter`'s: whatever upstream adds to the `.pscx` runs here as is.

The project is loaded whole rather than drawn through the library component by component: PSCAD takes each component drawn
as an edit of its canvas, some 0.7 s apiece (8 minutes for a wind turbine's 683 components), where it loads the whole file
in a second.

## Use

```java
PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, 0.3, 50.0, PscadEdition.PROFESSIONAL);
PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, workDir, settings, events,
        PscadProSettings.defaults());
Phasor b2 = run.output().fundamentalPhasor(run.project().circuit().voltageChannel("B2", 'A'), 50.0, 0.28, 0.3);
```

`PscadProSimulation.run(...)` starts from rest. `runFromSteadyState(...)` runs the template, then the restart, in
`workDir/template` and `workDir/restart`. A project can also be run by hand with the driver written beside it:
`py pscad_run.py two_bus.pscx [--no-run] [--fortran "<compiler>"] [--visible]`.

## Requirements

- powsybl-simu-emt's `simuemt-pscad`, installed from the upstream checkout: `mvn install -pl simuemt-pscad -am`.
- PSCAD 5.1 Professional, licensed, with `mhi.pscad` installed in the Python that `PscadProSettings.pythonCommand` runs
  (`py` by default).
- A Fortran compiler PSCAD detects. The default is Intel Fortran Classic 64-bit, which also needs Visual Studio's C++
  tools **and the Windows SDK**: without the SDK, linking fails on `wsock32.lib`.

## Tests

- `mvn test`: without PSCAD: every case's `.pscx` is written, the driver ships and Python compiles it, its report is read.
- `mvn test -Ppscad`: the real PSCAD.
  - `PscadProBuildTest` builds each case of `PscadProCases` (132: every upstream example network the backend writes, those
    past PSCAD Free's 15 electrical nodes among them, and this project's own: CIGRE B4's DC grid test system, whole
    (`CigreB4Network`, `src/test/resources/cigre-b4*`), the IEEE 14-bus network with a meshed DC grid and Dynawo's machines
    (`Ieee14DcNetwork`, `src/test/resources/ieee14-dc*`), and Kundur's two-area system with a three-terminal DC grid beside its
    tie (`KundurDcNetwork`, `src/test/resources/kundur-dc*`), the IEEE 39-bus system with a meshed four-terminal DC grid
    (`Ieee39DcNetwork`, `src/test/resources/ieee39-dc*`), and PROMOTioN's five-terminal meshed offshore benchmark of D5.1
    (`PromotionDcNetwork`, `src/test/resources/promotion-dc*`):
    sources, lines, transformers, machines and their exciters, governors, limiters and controllers, wind turbines, VSCs,
    MMCs and DC grids, arrester, motors, faults, breakers, load step, templates; and the converters' scenarios upstream runs
    in PSCAD Professional: blocked, deblocked, protected, through DC faults and DC breakers): its `.pscx` loaded, compiled and linked into
    EMTDC's executable, without an error. About 15 s a case; `-Dpscad.cases=two-bus,wind-turbine` builds only those.
  - `PscadProSimulationTest` runs the networks. Each `.pscx` is loaded, built, compiled and run, and what EMTDC wrote is
    held to the circuit's phasor steady state (`PscadSteadyState`), as upstream holds its hand-made PSCAD Free runs, and
    the multi-terminal DC grids to their load flow and droop curves.
  - `PscadProBeyondFreeTest` runs what upstream writes for the Professional edition only, past Free's 15 nodes, and holds it
    to what upstream holds ATP, Xyce and Kestrel to: the four phase-shifter benches on the load flow, the four
    with a magnetizing admittance within the T's drop of it - the two-winding one with upstream's, the three others, past the
    50% of rated PSCAD's transformer takes with it, at 40% -, a line put back into
    service, a three-winding transformer's leg closed, and left open from rest, an open-ended energization with and without
    its arrester, and a chain of twelve buses on the load flow; and the YNd11 bench restarted through a fault from a
    Professional template, whose snapshot renumbers its nodes; a three-winding transformer's legs displaced by their clock
    numbers, and a bus spelling a machine's internal node keeping its name - upstream's writer tests of them removed with
    their Professional projects.
  - `PscadProExampleCoverageTest` runs every example upstream's catalogue (`ExampleCatalogue`) tags as PSCAD Professional's
    alone - past Free's 15 nodes, twenty today - from rest and holds it to its load flow: an example upstream adds there runs
    here with nothing to add. `-Dpscad.examples=vsc-monopole-3,...` runs only those.
  - Upstream's twelve PSCAD tests that read a PSCAD Professional run, tagged `professional` and left out of its builds since
    its `5e6fb79d`, run here live: the YNd11 restart above, CIGRE DCS2's four scenarios (`PscadProCigreDcs2Test`) and
    example 3's and 4's seven converter scenarios (`PscadProConvertersTest`).
  - `PscadProCigreDcs2Test` runs CIGRE B4 DCS2's four scenarios live - its droops' power steps, its wind park's loss, an onshore
    AC fault and a DC fault cleared through the onshore breakers - and holds them to what ATP, Xyce and Kestrel share, the
    criteria upstream holds its stored PSCAD runs of them to.
  - `PscadProFaultSwingTest` runs large networks' faults, past PSCAD Free, and holds them to their physics: Kundur's two
    areas with their DC grid, faulted at bus 7 and bus 8, IEEE 39, IEEE 68 and IEEE 14 with theirs, faulted at bus 16, 1 and 4,
    CIGRE B4 whole, faulted at BAB1, and PROMOTioN's benchmark through an AC fault at A1 and at B1 and a fault on cable D1-D2
    its breakers cut off; Kundur's, IEEE 39's and IEEE 68's DC grids losing a station in full flow (CS7, CS4, CS33), a V_DC
    station that hits its current limit held to it, and their V_DC stations (CS9, CS16, CS16), the droops left to settle the
    DC voltage; and IEEE 39 with a high share of power electronics (its machines, four or six replaced by
    grid-following, grid-forming or mixed converter sources) through a fault and a load step at bus 16 - the swing between their
    areas (its amplitude, frequency and damping, or the machines settled), the centre of inertia's nadir and RoCoF after a load
    step, the DC voltages within their bounds, every station back on its law from the load flow; and PROMOTioN's DC fault at D1,
    every station blocked, nearest first, its stations protected and nothing clearing it, or cleared by the AC breakers; and
    IEEE 39 with six grid-following sources lost through its fault, G31 out of step; and IEEE 39 losing its whole DC grid
    through a DC fault at BD16 the AC breakers clear. About 3 minutes a case;
    `-Dpscad.faults=ieee68-dc-fault-b1,...` runs only those.
  - `PscadProAc6ALimiterTest` runs upstream's AC6A field current limiter bench at 20, 10 and 5 us, holds the limiter to ATP's,
    Xyce's and Kestrel's - within 0.5 at their 20 us, as upstream does -, and writes its channels around the fault to
    `target/ac6a-limiter/<step>us.csv`.
  - `PscadProDecTest` runs upstream's DEC2A and DEC3A benches, which upstream leaves to be run in PSCAD, from the steady
    state at 20 us, and holds them to the assertions ATP, Xyce and Kestrel are held to: the DEC2A's pulse on its trigger and
    the terminal voltage it raises, the DEC3A cutting the stabilizer off through a fault and closing again TDR after; and
    from rest through upstream's stored-run scenarios, held to the Xyce tables upstream holds its PSCAD Free runs to.
  - `PscadProConvertersTest` runs the eight converter scenarios upstream could only run in PSCAD Professional and stores as
    hand-made runs - a WT4B blocked, a grid-forming VSC deblocked, a droop station lost, a solid DC fault fed through the
    stations' bridges (blocked by order, by their protections, MMCs), cleared by a DC breaker, an MMC restarted after it -
    from rest at 20 us, held to the checks upstream holds those runs to; and four DC fault scenarios ATP, Xyce and Kestrel
    share that no PSCAD had run - the stations' AC breakers cutting a solid DC fault off, two-level and MMC, an MMC grid's DC
    breaker, a meshed MMC grid's faulted line cut off at both ends, the restarted MMC tripped again by a second fault, its DC
    breaker operated a third time, the MMC restarted by its own protection twice then locked out, DCB left open or DCB and T3 cycled
    at each of three faults -, held to ATP's checks and values; and example 4's controls - a master-slave station's stepped
    setpoint, a power step ramped along the droops, VSC3's power back along its recovery ramp after an AC fault on B3, master-slave
    and on the droop, example 3's grid-forming VSC2's stepped power reference, and
    its restart on a resynchronization PLL of its own, on its virtual machine and on a power-frequency droop -, held to ATP's checks and to the stepped load flow.
  - `PscadProSt6BTest` runs upstream's IEEE ST6B benches from rest at 20 us - through the fault B1 alone and with an OEL1B
    at position 1 or 4, and through a 15% step with its field current limiter acting - and holds them to the Xyce tables
    and tolerances upstream holds its stored PSCAD Free runs to.
