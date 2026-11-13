package com.example.orders.common.error;

import java.net.URI;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that map to a specific RFC 7807 response. Subclasses fix the status, type and
 * title; {@link #properties()} adds problem-specific extension members.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;

    protected ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return Map.of();
    }
}
