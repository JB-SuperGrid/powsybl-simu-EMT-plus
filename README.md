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
  - `PscadProBuildTest` builds each case of `PscadProCases` (81: every upstream example network the backend writes, those
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
    to what upstream holds ATP, Xyce and Kestrel to: the four phase-shifter benches on the load flow, a line put back into
    service, a three-winding transformer's leg closed, an open-ended energization with and without its arrester.
