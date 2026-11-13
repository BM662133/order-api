package com.example.orders.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.example.orders.common.error.UnauthenticatedException;

/**
 * Resolves the calling partner from {@value #PARTNER_HEADER} and stores it as a request attribute.
 * The header stands in for real authentication (API key, JWT, mTLS).
 *
 * <p>This is an interceptor rather than a servlet filter so that its exceptions go through
 * {@code GlobalExceptionHandler} and come back as ProblemDetail.
 */
public class PartnerAuthenticationInterceptor implements HandlerInterceptor {

    public static final String PARTNER_HEADER = "X-Partner-Id";
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
        request.setAttribute(PARTNER_CONTEXT_ATTRIBUTE, new PartnerContext(partner.getId()));
        return true;
    }
}
