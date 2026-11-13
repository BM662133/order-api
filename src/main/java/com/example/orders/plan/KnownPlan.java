package com.example.orders.plan;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** The value is the code of an existing, active plan. Null and blank are valid; combine with {@code @NotBlank}. */
@Documented
@Constraint(validatedBy = KnownPlanValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface KnownPlan {

    String message() default "must be an active plan";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
