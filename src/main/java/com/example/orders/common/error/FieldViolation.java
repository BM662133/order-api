package com.example.orders.common.error;

import java.util.Comparator;

/** One entry of a validation-error problem's {@code errors} array. */
public record FieldViolation(String field, String message) {

    /** Stable output order: by field, then message. */
    public static final Comparator<FieldViolation> ORDER =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);
}
