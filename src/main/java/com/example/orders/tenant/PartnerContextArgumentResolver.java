package com.example.orders.tenant;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link PartnerContext} set by {@link PartnerAuthenticationInterceptor} into controller methods. */
public class PartnerContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PartnerContext.class;
    }

    @Override
    public PartnerContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object context = webRequest.getAttribute(
                PartnerAuthenticationInterceptor.PARTNER_CONTEXT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (context == null) {
            // A controller outside the authenticated paths asked for a partner: a wiring bug, not a client error.
            throw new IllegalStateException("No PartnerContext for " + parameter.getMethod()
                    + "; is its path covered by TenantWebConfig?");
        }
        return (PartnerContext) context;
    }
}
