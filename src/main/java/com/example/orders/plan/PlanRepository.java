package com.example.orders.plan;

import org.springframework.data.repository.Repository;

public interface PlanRepository extends Repository<Plan, String> {

    boolean existsByCodeAndActiveTrue(String code);
}
