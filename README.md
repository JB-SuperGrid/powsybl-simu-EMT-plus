# SimuEmt Plus

SuperGrid Institute's overlay on [powsybl-simu-emt](https://github.com/JB-H/powsybl-simu-EMT): a **PSCAD Professional**
backend. Proprietary, all rights reserved (see `LICENSE`).

`powsybl-simu-emt-pscad` writes a `.pscx` for PSCAD Free, which has no automation library: the project is opened and run
by hand. This project writes instead a **Python script** driving PSCAD's automation library (`mhi.pscad`, Professional and
Educational editions), which builds the same project, compiles it, runs it, and reports where EMTDC wrote its output.

| Upstream (Free)                         | Here (Professional)                                   |
|-----------------------------------------|-------------------------------------------------------|
| `PscadProjectSerializer.PSCX` → `.pscx` | `PscadPythonScriptWriter` → `<name>.py`               |
| opened and run by hand                  | `PscadProRunner` runs the script (PSCAD, build, run)  |
| `PscadOutput` on the `.gf132` folder    | `PscadOutput` on the compiler's folder (`.if18`, ...) |

Both are written from the same `PscadDrawing`: the same components at the same points, with the same parameters and
labels. The script only replaces the file format. Circuit, events, template and restart all stay `PscadFileWriter`'s.

## Use

```java
PscadSimulationSettings settings = new PscadSimulationSettings(20.0e-6, 0.3, 50.0, PscadEdition.PROFESSIONAL);
PscadProSimulation.PscadProRun run = PscadProSimulation.runFromSteadyState(network, workDir, settings, events,
        PscadProSettings.defaults());
Phasor b2 = run.output().fundamentalPhasor(run.project().circuit().voltageChannel("B2", 'A'), 50.0, 0.28, 0.3);
```

`PscadProSimulation.run(...)` starts from rest. `runFromSteadyState(...)` runs the template, then the restart, in
`workDir/template` and `workDir/restart`. A generated script can also be run by hand:
`py two_bus.py [--no-run] [--fortran "<compiler>"] [--visible]`.

## Requirements

- powsybl-simu-emt with `PscadDrawing` / `PscadProjectSerializer` (upstream branch `pscad-drawing-api`, until merged:
  `mvn install -pl simuemt-pscad -am` in the upstream checkout).
- PSCAD 5.1 Professional, licensed, with `mhi.pscad` installed in the Python that `PscadProSettings.pythonCommand` runs
  (`py` by default).
- A Fortran compiler PSCAD detects. The default is Intel Fortran Classic 64-bit, which also needs Visual Studio's C++
  tools **and the Windows SDK**: without the SDK, linking fails on `wsock32.lib`.

## Tests

- `mvn test`: the script as written, without PSCAD (layout, settings, definitions, Python compiles it).
- `mvn test -Ppscad`: the real PSCAD. Each network is built, compiled and run, and what EMTDC wrote is held to the
  circuit's phasor steady state (`PscadSteadyState`), as upstream holds its hand-made PSCAD Free runs.
