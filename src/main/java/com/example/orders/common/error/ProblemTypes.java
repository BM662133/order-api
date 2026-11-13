package com.example.orders.common.error;

import java.net.URI;

/** Stable RFC 7807 {@code type} URIs. Clients may branch on these, so never change an existing value. */
public final class ProblemTypes {

    private static final String BASE = "https://order-api.example.com/problems/";

    public static final URI UNAUTHENTICATED = URI.create(BASE + "unauthenticated");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");
    public static final URI ILLEGAL_STATUS_TRANSITION = URI.create(BASE + "illegal-status-transition");

    private ProblemTypes() {
    }
}
