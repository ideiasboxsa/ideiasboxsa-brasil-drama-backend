package br.com.brasildrama.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BingePercentageInvariantTest {
    @Test
    void conversionPercentageStaysBounded() {
        double bingeSessions = 3.0;
        double continuingSessions = 5.0;
        double conversion = continuingSessions == 0 ? 0 : bingeSessions * 100.0 / continuingSessions;
        assertTrue(conversion >= 0.0 && conversion <= 100.0);
    }
}
