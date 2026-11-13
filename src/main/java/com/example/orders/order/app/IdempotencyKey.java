package com.example.orders.order.app;

import java.util.regex.Pattern;

/** A validated {@value #HEADER} header value: 1-100 visible ASCII characters (no spaces). */
public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_LENGTH = 100;

    private static final Pattern VALID = Pattern.compile("[\\x21-\\x7E]{1," + MAX_LENGTH + "}");

    public IdempotencyKey {
        if (value == null || value.isEmpty()) {
            throw new InvalidIdempotencyKeyException("Missing " + HEADER + " header.");
        }
        if (!VALID.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException(
                    HEADER + " must be 1-" + MAX_LENGTH + " visible ASCII characters with no spaces.");
        }
    }
}
