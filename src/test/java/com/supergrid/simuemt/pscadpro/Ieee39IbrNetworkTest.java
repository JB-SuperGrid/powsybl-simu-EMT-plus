/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.pscadpro;

import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.Identifiable;
import com.powsybl.iidm.network.Network;
import com.powsybl.simuemt.extensions.hvdc.AveragedVscModel;
import com.powsybl.simuemt.extensions.hvdc.VoltageSourceConverterModel;
import com.powsybl.simuemt.extensions.hvdc.control.GridFollowingControl;
import com.powsybl.simuemt.extensions.hvdc.control.GridFormingControl;
import com.powsybl.simuemt.extensions.hvdc.control.VscControlModel;
import com.powsybl.simuemt.preprocessing.EmtNetworkPreprocessor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * IEEE 39 with a high share of power electronics ({@link Ieee39IbrNetwork}), without PSCAD: its flat files, its load flows, the
 * one difference between its grid-following and grid-forming variants.
 *
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
class Ieee39IbrNetworkTest {

    /** The flat files are what {@link Ieee39IbrNetwork} builds: the reference its ten machines; the variants six of them and four
     * links of two stations each, or four and six. */
    @Test
    void theFlatFilesAreTheBuildersNetworks() {
        assertThat(ids(Ieee39IbrNetwork.loadSync())).isEqualTo(ids(Ieee39IbrNetwork.createSync()));
        assertThat(Ieee39IbrNetwork.loadSync().getGeneratorCount()).isEqualTo(10);
        for (boolean gridForming : List.of(false, true)) {
            Network read = Ieee39IbrNetwork.loadIbr(gridForming);
            assertThat(ids(read)).isEqualTo(ids(Ieee39IbrNetwork.createIbr()));
            assertThat(read.getGeneratorCount()).isEqualTo(6 + 4);
            assertThat(read.getVoltageSourceConverterCount()).isEqualTo(8);
            assertThat(read.getDcLineCount()).isEqualTo(4);
        }
        for (boolean gridForming : List.of(false, true)) {
            Network read = Ieee39IbrNetwork.loadIbr65(gridForming);
            assertThat(ids(read)).isEqualTo(ids(Ieee39IbrNetwork.createIbr65()));
            assertThat(read.getGeneratorCount()).isEqualTo(4 + 6);
            assertThat(read.getVoltageSourceConverterCount()).isEqualTo(12);
            assertThat(read.getDcLineCount()).isEqualTo(6);
        }
    }

    private static List<String> ids(Network network) {
        return network.getIdentifiables().stream().map(Identifiable::getId).sorted().toList();
    }

    /** The variants differ in their CL stations' control alone: grid-following or grid-forming; the CR stations grid-following both. */
    @Test
    void theVariantsDifferInTheirStationsControlAlone() {
        differInTheirStationsControlAlone(Ieee39IbrNetwork.loadIbr(false), Ieee39IbrNetwork.loadIbr(true), Ieee39IbrNetwork.REPLACED);
        differInTheirStationsControlAlone(Ieee39IbrNetwork.loadIbr65(false), Ieee39IbrNetwork.loadIbr65(true), Ieee39IbrNetwork.REPLACED_65);
    }

    private static void differInTheirStationsControlAlone(Network gfl, Network gfm, List<Ieee39IbrNetwork.Replaced> replacedMachines) {
        for (Ieee39IbrNetwork.Replaced replaced : replacedMachines) {
            int m = replaced.machineBus();
            Object gflControl = gfl.getVoltageSourceConverter("CL" + m).getExtension(VscControlModel.class);
            Object gfmControl = gfm.getVoltageSourceConverter("CL" + m).getExtension(VscControlModel.class);
            Object farControl = gfm.getVoltageSourceConverter("CR" + m).getExtension(VscControlModel.class);
            assertThat(gflControl).isInstanceOf(GridFollowingControl.class);
            assertThat(gfmControl).isInstanceOf(GridFormingControl.class);
            assertThat(farControl).isInstanceOf(GridFollowingControl.class);
            AveragedVscModel gflModel = (AveragedVscModel) gfl.getVoltageSourceConverter("CL" + m).getExtension(VoltageSourceConverterModel.class);
            AveragedVscModel gfmModel = (AveragedVscModel) gfm.getVoltageSourceConverter("CL" + m).getExtension(VoltageSourceConverterModel.class);
            assertThat(gfmModel.getRatedApparentPower()).isEqualTo(gflModel.getRatedApparentPower()).isEqualTo(replaced.ratingMva());
        }
    }

    /**
     * The variants' load flow, as the pipeline runs it: each CL station delivering its machine's power, its CR station on the DC
     * voltage taking that and the link's losses from its own grid; G31, the slack, 4 MW more than with the machines, which held
     * their buses' voltage - 0.99 to 1.04 pu where they were 1.02 to 1.05 pu.
     */
    @Test
    void theLoadFlowsSolve() {
        Network sync = Ieee39IbrNetwork.loadSync();
        EmtNetworkPreprocessor.preprocess(sync, LocalComputationManager.getDefault());
        Network ibr = Ieee39IbrNetwork.loadIbr(false);
        EmtNetworkPreprocessor.preprocess(ibr, LocalComputationManager.getDefault());

        assertThat(-sync.getGenerator("G31").getTerminal().getP()).isCloseTo(683.94, within(0.01));
        assertThat(-ibr.getGenerator("G31").getTerminal().getP()).isCloseTo(687.67, within(0.01));
        assertThat(ibr.getVoltageSourceConverter("CL38").getTerminal1().getP()).isCloseTo(-830.0, within(1.0e-6));
        assertThat(ibr.getVoltageSourceConverter("CR38").getTerminal1().getP()).isCloseTo(841.78, within(0.01));
        assertThat(ibr.getDcNode("BDR38").getV()).isCloseTo(400.0, within(1.0e-6));
        assertThat(ibr.getDcNode("BDL38").getV()).isCloseTo(394.40, within(0.01));
    }
}
