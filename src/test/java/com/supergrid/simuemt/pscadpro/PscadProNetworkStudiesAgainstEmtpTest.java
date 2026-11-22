/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VoltageSourceConverter;
import com.powsybl.simuemt.extensions.event.BusFault;
import com.powsybl.simuemt.extensions.event.FaultClearing;
import com.powsybl.simuemt.pscad.PscadSwitching;
import com.supergrid.simuemt.emtp.EmtpVscFaultAgainstPscadTest;
import com.supergrid.simuemt.emtp.EmtpVscFaultAgainstPscadTest.Channel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The overlay's network studies in EMTP against PSCAD Professional, each through its study's solid three-phase fault, 100 ms, at its
 * bus - PSCAD's at 2.5 s, from rest, EMTP's from its steady state ({@link EmtpVscFaultAgainstPscadTest#compare}): every converter's
 * powers, AC and DC voltages by {@code window} means, settled within {@code settled} pu of PSCAD's before the fault, through it and the
 * recovery 3e-2 - as Kundur's, IEEE 39's with its DC grid and PROMOTioN's there. Five ms windows, but 20 on IEEE 39's power electronics
 * variants: through the fault their grid-following converters ring at some 60 Hz in both solvers alike, not in phase. Settled within
 * 1e-3, but 5e-3 on CIGRE B4 - its DC cables ringing in both, as PROMOTioN's - and 3e-3 where a grid-forming converter swings, PSCAD's
 * virtual machines not settled from rest by 2.5 s.
 *
 * <p>EMTP at 20 us, but 10 on IEEE 39's grid-following variants: there EMTP's step error is PSCAD's several times over - CL38, a
 * grid-following station on bus 29's short-circuit ratio of 1.3, its PLL slowed to ride this very fault, its power through the recovery
 * 2.7e-2 apart between EMTP's 20 us and 10 us runs, PSCAD's 3e-3 -, and at 10 us EMTP's is PSCAD's within 5e-3. The cause, measured
 * 2026-11-22 on a weak-grid bench (vsc-monopole-infinite-buses, its second station on an infinite bus of 325 MVA, a 5 ohm three-phase
 * fault cleared at its currents' zeros; VSC2's P, Q and AC voltage against a 1 us run): EMTP's error is first order - 3.4e-2, 1.5e-2,
 * 6.5e-3 at 20, 10, 5 us -, born at the fault's clearing, more than nine tenths of it - never cleared, 2.9e-3 at 20 us. A switch opens
 * at the step after its current crosses zero, the instant uninterpolated (EMTP's {@code _Sw0} has no such option), as ATP's; PSCAD
 * interpolates its switchings. EMTP's default integration doubles it - two backward Euler half steps at every discontinuity: pure
 * trapezoidal ({@code SolutionMethod.TRAP=1;}) gives 1.8e-2, 8.4e-3, 3.4e-3 -, kept as it is. Not the controls - EMTP solves a loop
 * through an integrator at second order -, nor the control's step to the network - one step, PSCAD's -, nor the 16L/Dt aids - at their
 * 5 us values, 20 us is as far.
 *
 * <p>Left out: IEEE 39 all synchronous - no converter, nothing Kundur's machines do not hold already -; and IEEE 39 GFM 65 %, which has
 * no trajectory to compare through its fault: its grid-forming CL36, deep in the fault and current-limited, enters a limit cycle in both
 * solvers, its steady-state current limit's integrator swinging from -0.4 to 0.8 every 18 ms or so and its bus with it - 0.36 to 0.87
 * pu in EMTP, 0.26 to 0.63 in PSCAD -, its amplitude the solver's and its time step's (0.08 pu of reactive power apart at 20 us, 0.20
 * at 10); its current limit the same in both, its virtual machine's speed within 1e-4 all through, back together within 2e-3 once
 * cleared.
 *
 * <p>Run 2026-11-17, 22 min the eight: IEEE 68 within 7.6e-4 settled and 2.0e-3 through the fault; IEEE 39 GFM 2.1e-3 and 6.2e-3;
 * CIGRE B4 3.8e-3 and 1.3e-2; IEEE 39 mix 65 % 2.4e-3 and 1.5e-2; IEEE 39 GFL 5.0e-4 and 1.6e-2; IEEE 39 GFL 65 % 5.3e-4 and 1.8e-2;
 * IEEE 14 3.7e-4 and 2.3e-2.
 *
 * <p>Tagged {@code emtp} and {@code pscad}: run with {@code mvn test -Pemtp,pscad}.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
@Tag("emtp")
@Tag("pscad")
class PscadProNetworkStudiesAgainstEmtpTest {

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({
        "cigre-b4, BAB1, 5.0e-3, 0.005, 20.0e-6",
        "ieee14-dc, B4, 1.0e-3, 0.005, 20.0e-6",
        "ieee39-gfl, B16, 1.0e-3, 0.02, 10.0e-6",
        "ieee39-gfm, B16, 3.0e-3, 0.02, 20.0e-6",
        "ieee39-gfl65, B16, 1.0e-3, 0.02, 10.0e-6",
        "ieee39-mix65, B16, 3.0e-3, 0.02, 10.0e-6",
        "ieee68-dc, B1, 1.0e-3, 0.005, 20.0e-6"})
    void aStudysFaultRunsInEmtpAsInPscadProfessional(String bench, String bus, double settled, double window, double emtpTimeStep) throws IOException {
        List<Channel> channels = new ArrayList<>();
        for (VoltageSourceConverter converter : PscadProCases.preprocessed(bench, false, false).getVoltageSourceConverters()) {
            for (String quantity : List.of("_P", "_Q", "_VAC", "_VDC")) {
                channels.add(new Channel(converter.getId() + quantity, "control:" + converter.getId() + quantity, converter.getId() + quantity, 1.0));
            }
        }
        EmtpVscFaultAgainstPscadTest.compare(() -> network(bench), start -> List.of(new BusFault("F", bus, "ABC", true, 1.0, start, start + 0.1,
                FaultClearing.AT_CURRENT_ZERO)), channels, bench + "-fault-" + bus.toLowerCase(java.util.Locale.ROOT), window, settled, 0.03, false, 2.2,
                PscadSwitching.INTERPOLATED, emtpTimeStep);
    }

    private static Network network(String bench) {
        return PscadProCases.preprocessed(bench, false, false);
    }
}
