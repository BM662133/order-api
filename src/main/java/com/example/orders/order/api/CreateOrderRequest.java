package com.example.orders.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.example.orders.order.app.CreateOrderCommand;
import com.example.orders.order.domain.SimType;

/**
 * Format checks only, run by {@code @Valid} before anything else. Whether the plan exists and is active
 * is checked on {@link CreateOrderCommand}, after the idempotency lookup.
 */
public record CreateOrderRequest(

        /* E.164: optional '+', no leading zero, 8-15 digits, nothing else. */
        @NotNull
        @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "must be an E.164 number, e.g. +447700900123")
        String msisdn,

        @NotBlank
        String planCode,

        @NotNull
        SimType simType) {

    public CreateOrderCommand toCommand() {
        return new CreateOrderCommand(msisdn, planCode, simType);
    }
}
