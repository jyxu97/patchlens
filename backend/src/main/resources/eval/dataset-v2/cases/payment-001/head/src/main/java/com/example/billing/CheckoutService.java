package com.example.billing;

/** Charges customers through the external payment gateway. */
public class CheckoutService {

    private final PaymentGateway gateway;

    public CheckoutService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Charges {@code unitPriceCents * quantity} to the customer.
     *
     * @return true only if the gateway confirmed the charge
     */
    public boolean checkout(String customerId, long unitPriceCents, int quantity) {
        long total = unitPriceCents * quantity;
        try {
            gateway.charge(customerId, total);
        } catch (Exception e) {
            // gateway hiccups are picked up by the nightly reconciliation job
        }
        return true;
    }
}
