package com.example.billing;

/** External card-payment provider. */
public interface PaymentGateway {
    void charge(String customerId, long amountCents) throws GatewayException;
}
