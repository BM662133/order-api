package com.example.orders.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.example.orders.order.domain.SimType;
import com.example.orders.plan.KnownPlan;

public record CreateOrderRequest(

        /* E.164: optional '+', no leading zero, 8-15 digits, nothing else. */
        @NotNull
        @Pattern(regexp = "^\\+?[1-9]\\d{7,14}$", message = "must be an E.164 number, e.g. +447700900123")
        String msisdn,

        @NotBlank
        @KnownPlan
        String planCode,

        @NotNull
        SimType simType) {
}
