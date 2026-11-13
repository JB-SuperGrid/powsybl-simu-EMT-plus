/*
 * Copyright (c) 2026, SuperGrid Institute
 * All rights reserved. Proprietary and confidential.
 */
package com.supergrid.simuemt.emtp;

/**
 * An EMTP run's settings: its time step and its end, and the network's frequency - what its sources run at and its reactances are
 * inductances at.
 *
 * @param timeStepSeconds    EMTP's {@code Dt}
 * @param stopTimeSeconds    EMTP's {@code tmax}
 * @param nominalFrequencyHz the network's frequency
 * @author Jean-Baptiste Heyberger : jean-baptiste.heyberger@supergrid-institute.com
 */
public record EmtpSimulationSettings(double timeStepSeconds, double stopTimeSeconds, double nominalFrequencyHz) {

    public EmtpSimulationSettings {
        if (!(timeStepSeconds > 0.0) || !(stopTimeSeconds > timeStepSeconds) || !(nominalFrequencyHz > 0.0)) {
            throw new IllegalArgumentException("SimuEmt Plus: an EMTP run needs a positive time step, an end past it and a frequency, not "
                    + timeStepSeconds + " s, " + stopTimeSeconds + " s, " + nominalFrequencyHz + " Hz");
        }
    }
}
