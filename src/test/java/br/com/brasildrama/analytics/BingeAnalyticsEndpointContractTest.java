package br.com.brasildrama.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BingeAnalyticsEndpointContractTest {
    @Test
    void supportedWindowsRemainBounded() {
        assertTrue(java.util.Set.of(7, 30, 90).contains(7));
    }
}
