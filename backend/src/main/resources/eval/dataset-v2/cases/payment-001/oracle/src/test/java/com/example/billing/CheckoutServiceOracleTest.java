package com.example.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutServiceOracleTest {

    @Test
    void declinedChargeIsNotReportedAsSuccessful() {
        CheckoutService service = new CheckoutService((customer, amount) -> {
            throw new GatewayException("card declined");
        });
        boolean reportedSuccess;
        try {
            reportedSuccess = service.checkout("c1", 250, 1);
        } catch (Exception e) {
            reportedSuccess = false; // surfacing the failure is also acceptable
        }
        assertFalse(reportedSuccess);
    }

    @Test
    void successfulChargeIsStillReported() {
        CheckoutService service = new CheckoutService((customer, amount) -> { });
        assertTrue(service.checkout("c1", 250, 1));
    }
}
