package com.example.orders.common.error;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A validation failure found in the service layer rather than by {@code @Valid} on the controller.
 * Rendered exactly like a request-body validation error (400 {@code validation-error} with
 * {@code errors}), so clients can't tell where the check ran.
 */
public class RequestValidationException extends ApiException {

    private final List<FieldViolation> errors;

    public RequestValidationException(List<FieldViolation> errors) {
        super(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation failed",
                "Request has " + errors.size() + " invalid field(s).");
        this.errors = errors.stream().sorted(FieldViolation.ORDER).toList();
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("errors", errors);
    }
}
