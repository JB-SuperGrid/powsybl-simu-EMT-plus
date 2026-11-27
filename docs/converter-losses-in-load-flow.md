<!--
Copyright (c) 2026, SuperGrid Institute
All rights reserved. Proprietary and confidential.
-->

# Phase reactor losses in the load flow

Status: **not done** - written down 2026-11-27, kept for later. Meanwhile `PscadProExampleCoverageTest` holds a V_DC
converter's active power within 1e-2 pu of its rating instead of 5e-3.

## The gap

Every EMT backend starts an averaged converter (VSC or MMC) from the load flow, behind a phase reactor `R + jX`
(`AveragedConverterModel`, R typically 0.003 pu). The reactor loses `R |i|^2`, `|i|^2 = (P^2 + Q^2) / V^2` at the converter's
AC terminal. The load flow (OpenLoadFlow 2.4.0, AC/DC) knows no phase reactor: its converters are lossless here. So, at the end
of an EMT run:

- a `P_PCC` or `DC_DROOP` converter holds its own power, and is on the load flow's;
- the `V_DC` converter balancing a DC grid delivers the load flow's power **plus every phase reactor's loss on that grid**,
  its own included.

Seen 2026-11-27 on upstream's CIGRE B4 (catalogued since upstream #47) in PSCAD Professional: DCS3's V_DC converter CBA1 is
5.4e-3 pu of its 2400 MW off the load flow's. DCS3's three phase reactors (CBA1 0.64 pu of 2400 MW, CBB1 0.63 of 2400, CBB2
0.97 of 1600) lose some 10 MW, 4.3e-3 of CBA1's rating. The other network studies' V_DC converters sit at 4.3e-3 to 4.7e-3.
Upstream's `AveragedVscInitialState` javadoc describes the same offset on the DC side (CIGRE B4 DCS2, 2.97 MW).

## What OpenLoadFlow has

Per converter, OpenLoadFlow already solves for `CONV_P_AC`, `CONV_Q_AC`, its AC bus voltage `V` and its DC current. Its power
balance (`ConverterDcCurrentEquationTerm`) is

```
P_AC + I_dc U_dc = idle + switching |I_dc| + R_dc I_dc^2
```

from IIDM's `AcDcConverter` `IdleLoss` (MW), `SwitchingLoss` (MW/A) and `ResistiveLoss` (ohm). The resistive loss is on the
**DC** current, whereas the phase reactor's is on the AC current, which depends on Q and V as well: no fixed `ResistiveLoss` gives
it exactly.

## What is missing

1. **A term in that balance**, on the AC side:

   ```
   P_AC - R_ac (P_AC^2 + Q_AC^2) / V^2 + I_dc U_dc = idle + switching |I_dc| + R_dc I_dc^2
   ```

   All three quantities are already variables. Substituting `P_AC - R_ac |S|^2 / V^2` for `P_AC` keeps OpenLoadFlow's closed form
   for `I_dc` (the quadratic in `I_dc` is unchanged); the derivatives with respect to `Q_AC` and `V` are new.
2. **Somewhere to give `R_ac`**: IIDM's `AcDcConverter` has no AC-side resistance. Either a new attribute in powsybl-core, or an
   IIDM extension OpenLoadFlow reads.

Then, in powsybl-simu-emt: bump OpenLoadFlow (and powsybl-core if needed), give each averaged converter `R_ac` from its
`AveragedConverterModel` before the load flow (`EmtNetworkPreprocessor`, `ExampleNetworks`), keep `EmtSupportedModels` refusing
user-given converter losses, and update `AveragedVscInitialState`'s javadoc - its DC side would then start balanced.

## Ruled out

- **An iteration around the load flow** (solve, compute each converter's `R |i|^2`, give it as `IdleLoss`, solve again until it
  settles): exact at convergence, no OpenLoadFlow change, but a loop in the preprocessing, and the losses written in IIDM have
  to be kept apart from user-given ones.
- **The phase reactor as an IIDM branch** (an internal bus and an `R + jX` line): OpenLoadFlow would then hold the converter's
  P, Q and AC voltage on the internal bus rather than at the terminal, unlike the EMT controls, and the backends would count the
  reactor twice (branch and averaged model).
- **Correcting the expected power in the test** (adding the DC grid's `R |i|^2` to the V_DC converter's load-flow power): would
  work for this test only; the tolerance was widened instead.

## Cost and blocker

- powsybl-core / powsybl-open-loadflow: one or two PRs to LF Energy projects (a few days of code, then review and a release).
- powsybl-simu-emt: about a day once released.
- Revalidation: every load flow with a VSC moves by 0.1-0.5%. Upstream's ATP, Xyce and Kestrel comparisons and Xyce tables would
  have to be rerun - none of the three solvers is installed on the PC this overlay runs on. Then a full `-Ppscad,emtp` run here,
  and `PscadProExampleCoverageTest` back to 5e-3 for V_DC converters.
