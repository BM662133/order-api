package com.example.orders.tenant;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.example.orders.common.error.UnauthenticatedException;

/**
 * Resolves the calling partner from {@value #PARTNER_HEADER} and stores it as a request attribute.
 * The header stands in for real authentication (API key, JWT, mTLS). If the matched route has a
 * {@value #PARTNER_PATH_VARIABLE} path variable, it must name the same partner (403 otherwise). This
 * runs before request-body validation, so a mismatch is never masked by a 400.
 *
 * <p>This is an interceptor rather than a servlet filter so that its exceptions go through
 * {@code GlobalExceptionHandler} and come back as ProblemDetail.
 */
public class PartnerAuthenticationInterceptor implements HandlerInterceptor {

    public static final String PARTNER_HEADER = "X-Partner-Id";
    public static final String PARTNER_PATH_VARIABLE = "partnerId";
    static final String PARTNER_CONTEXT_ATTRIBUTE = PartnerContext.class.getName();

    private final PartnerRepository partnerRepository;

    public PartnerAuthenticationInterceptor(PartnerRepository partnerRepository) {
        this.partnerRepository = partnerRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String partnerId = request.getHeader(PARTNER_HEADER);
        if (partnerId == null || partnerId.isBlank()) {
            throw new UnauthenticatedException("Missing " + PARTNER_HEADER + " header.");
        }
        Partner partner = partnerRepository.findById(partnerId)
                .filter(Partner::isActive)
                .orElseThrow(() -> new UnauthenticatedException("Unknown or inactive partner."));
        if (!partner.getId().equals(pathPartnerId(request, partner.getId()))) {
            throw new PartnerMismatchException();
        }
        request.setAttribute(PARTNER_CONTEXT_ATTRIBUTE, new PartnerContext(partner.getId()));
        return true;
    }

    /** The route's {partnerId} path variable, or {@code fallback} if the route has none. */
    private static String pathPartnerId(HttpServletRequest request, String fallback) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables == null ? fallback : variables.getOrDefault(PARTNER_PATH_VARIABLE, fallback);
    }
}
