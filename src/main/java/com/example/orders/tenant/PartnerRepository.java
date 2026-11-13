package com.example.orders.tenant;

import java.util.Optional;

import org.springframework.data.repository.Repository;

public interface PartnerRepository extends Repository<Partner, String> {

    Optional<Partner> findById(String id);
}
