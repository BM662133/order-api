package com.example.orders.plan;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Instantiated by Spring's validator factory, so it can take constructor-injected beans. */
public class KnownPlanValidator implements ConstraintValidator<KnownPlan, String> {

    private final PlanRepository planRepository;

    public KnownPlanValidator(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @Override
    public boolean isValid(String code, ConstraintValidatorContext context) {
        if (code == null || code.isBlank()) {
            return true;
        }
        return planRepository.existsByCodeAndActiveTrue(code);
    }
}
