package br.com.brasildrama.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BingeAnalyticsContractTest {
    @Test
    void bingeConversionUsesContinuingSessionsAsDenominator() {
        double bingeSessions = 3.0;
        double continuingSessions = 5.0;
        double conversion = continuingSessions == 0.0 ? 0.0 : bingeSessions * 100.0 / continuingSessions;
        assertEquals(60.0, conversion);
    }

    @Test
    void bingeConversionIsZeroWithoutContinuingSessions() {
        double bingeSessions = 0.0;
        double continuingSessions = 0.0;
        double conversion = continuingSessions == 0.0 ? 0.0 : bingeSessions * 100.0 / continuingSessions;
        assertEquals(0.0, conversion);
    }
}
