package com.example.orders.tenant;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TenantWebConfig implements WebMvcConfigurer {

    /** Every API path is partner-authenticated; actuator endpoints are not. */
    static final String[] AUTHENTICATED_PATHS = {"/partners/**", "/orders/**"};

    private final PartnerRepository partnerRepository;

    public TenantWebConfig(PartnerRepository partnerRepository) {
        this.partnerRepository = partnerRepository;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PartnerAuthenticationInterceptor(partnerRepository))
                .addPathPatterns(AUTHENTICATED_PATHS);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PartnerContextArgumentResolver());
    }
}
