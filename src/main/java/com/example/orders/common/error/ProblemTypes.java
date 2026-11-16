package com.example.orders.common.error;

import java.net.URI;

/** Stable RFC 7807 {@code type} URIs. Clients may branch on these, so never change an existing value. */
public final class ProblemTypes {

    private static final String BASE = "https://order-api.example.com/problems/";

    public static final URI UNAUTHENTICATED = URI.create(BASE + "unauthenticated");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");
    public static final URI NOT_FOUND = URI.create(BASE + "not-found");
    public static final URI ILLEGAL_STATUS_TRANSITION = URI.create(BASE + "illegal-status-transition");
    public static final URI VALIDATION_ERROR = URI.create(BASE + "validation-error");
    public static final URI MALFORMED_REQUEST = URI.create(BASE + "malformed-request");
    public static final URI INVALID_PARAMETER = URI.create(BASE + "invalid-parameter");
    public static final URI PARTNER_MISMATCH = URI.create(BASE + "partner-mismatch");
    public static final URI ORDER_NOT_FOUND = URI.create(BASE + "order-not-found");
    public static final URI INVALID_IDEMPOTENCY_KEY = URI.create(BASE + "invalid-idempotency-key");
    public static final URI IDEMPOTENCY_KEY_REUSED = URI.create(BASE + "idempotency-key-reused");
    public static final URI CONCURRENT_MODIFICATION = URI.create(BASE + "concurrent-modification");

    private ProblemTypes() {
    }
}
