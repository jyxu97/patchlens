package com.example.billing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutServiceTest {

    @Test
    void chargesUnitPriceTimesQuantity() {
        AtomicLong charged = new AtomicLong();
        CheckoutService service = new CheckoutService((customer, amount) -> charged.set(amount));
        assertTrue(service.checkout("c1", 250, 3));
        assertEquals(750, charged.get());
    }
}
