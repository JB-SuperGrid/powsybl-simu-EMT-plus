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

`PscadPythonScriptWriter` writes the same project as a script that draws it through the library, component by component
(`<name>.py`). It is not how a simulation is run: PSCAD takes each component drawn as an edit of its canvas, some 0.7 s
apiece, 8 minutes for a wind turbine's 683 components, where it loads the whole file in a second. It is what the `.pscx`
is held to (`PscadProEquivalenceTest`): what the documented library draws and what PSCAD reads from the file must compile
to the same Fortran.

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

- powsybl-simu-emt with `PscadDrawing` / `PscadProjectSerializer` (upstream branch `pscad-drawing-api`, until merged:
  `mvn install -pl simuemt-pscad -am` in the upstream checkout).
- PSCAD 5.1 Professional, licensed, with `mhi.pscad` installed in the Python that `PscadProSettings.pythonCommand` runs
  (`py` by default).
- A Fortran compiler PSCAD detects. The default is Intel Fortran Classic 64-bit, which also needs Visual Studio's C++
  tools **and the Windows SDK**: without the SDK, linking fails on `wsock32.lib`.

## Tests

- `mvn test`: without PSCAD: the drawing script as written (layout, settings, definitions), and Python compiles it and the driver.
- `mvn test -Ppscad`: the real PSCAD.
  - `PscadProEquivalenceTest` needs no linker. Each case of `EquivalenceCases` (42: sources, lines, transformers,
    machines and their exciters, governors, limiters and controllers, wind turbines, VSCs and DC grids, arrester, motors, faults,
    breakers, load step, templates) is written both as a `.pscx`, loaded whole, and as a script, drawn component by
    component; both are built by PSCAD, and every file EMTDC compiles from must be the
    same, component ids and the station's name aside. Normalized copies and the script's warnings are kept in
    `target/equivalence/`. Takes about 2 h 30, the drawing most of it; `-Dequivalence.cases=two-bus,wind-turbine` runs only those.
  - `PscadProSimulationTest` runs the networks. Each `.pscx` is loaded, built, compiled and run, and what EMTDC wrote is
    held to the circuit's phasor steady state (`PscadSteadyState`), as upstream holds its hand-made PSCAD Free runs, and
    the multi-terminal DC grids to their load flow and droop curves.
