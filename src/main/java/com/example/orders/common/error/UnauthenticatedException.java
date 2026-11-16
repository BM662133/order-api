package com.example.orders.common.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 401. RFC 9110 requires a 401 to carry a {@code WWW-Authenticate} challenge naming how to authenticate;
 * "Partner" is this API's (stand-in) scheme: send the partner id in {@code X-Partner-Id}.
 */
public class UnauthenticatedException extends ApiException {

    public static final String CHALLENGE = "Partner realm=\"order-api\"";

    public UnauthenticatedException(String detail) {
        super(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "Unauthenticated", detail);
    }

    @Override
    public HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        return headers;
    }
}
