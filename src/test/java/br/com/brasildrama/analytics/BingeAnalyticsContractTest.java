package br.com.brasildrama.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BingeAnalyticsContractTest {
    @Test
    void bingeConversionUsesContinuingSessionsAsDenominator() {
        assertEquals(60.0, BingeAnalyticsApi.bingeConversionPercent(3, 5));
    }

    @Test
    void bingeConversionIsZeroWithoutContinuingSessions() {
        assertEquals(0.0, BingeAnalyticsApi.bingeConversionPercent(0, 0));
    }
}
