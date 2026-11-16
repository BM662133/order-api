package com.example.orders.common.error;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as RFC 7807 {@link ProblemDetail}. Spring MVC's own exceptions (400 on bad
 * input, 404 on unknown routes, 405, ...) are handled by {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setType(ex.type());
        problem.setTitle(ex.title());
        ex.properties().forEach(problem::setProperty);
        return ResponseEntity.status(ex.status()).headers(ex.headers()).body(problem);
    }

    /** Bean Validation failures on a request body: one entry per invalid field, sorted for stable output. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), String.valueOf(e.getDefaultMessage())))
                .sorted(FieldViolation.ORDER)
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Request has " + errors.size() + " invalid field(s).");
        problem.setType(ProblemTypes.VALIDATION_ERROR);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Unparseable JSON, or a value of the wrong type (e.g. an unknown enum constant). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, malformedRequestDetail(ex));
        problem.setType(ProblemTypes.MALFORMED_REQUEST);
        problem.setTitle("Malformed request");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static String malformedRequestDetail(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException invalid && !invalid.getPath().isEmpty()) {
            String field = invalid.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .collect(Collectors.joining("."));
            String detail = "Invalid value for '" + field + "'.";
            Class<?> target = invalid.getTargetType();
            if (target != null && target.isEnum()) {
                detail += " Allowed values: " + Arrays.stream(target.getEnumConstants())
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")) + ".";
            }
            return detail;
        }
        return "Request body is missing or is not valid JSON.";
    }

    /**
     * A path or query parameter that cannot be converted, e.g. {@code GET /orders/abc} for a UUID. The
     * rejected value is deliberately not echoed back.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String parameter = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a different type";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "Invalid value for '" + parameter + "'; expected " + expected + ".");
        problem.setType(ProblemTypes.INVALID_PARAMETER);
        problem.setTitle("Invalid parameter");
        problem.setProperty("parameter", parameter);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Spring's default detail ("No static resource ...") leaks implementation details; describe the route instead. */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "No endpoint " + ex.getHttpMethod() + " /" + ex.getResourcePath() + ".");
        problem.setType(ProblemTypes.NOT_FOUND);
        problem.setTitle("Not Found");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Optimistic-lock and lock-acquisition failures (both are {@link ConcurrencyFailureException}) that
     * were not retried, or kept failing. The client may retry.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.debug("Concurrent modification", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was modified concurrently. Retry the request.");
        problem.setType(ProblemTypes.CONCURRENT_MODIFICATION);
        problem.setTitle("Concurrent modification");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setType(ProblemTypes.INTERNAL_ERROR);
        problem.setTitle("Internal Server Error");
        return ResponseEntity.internalServerError().body(problem);
    }
}
