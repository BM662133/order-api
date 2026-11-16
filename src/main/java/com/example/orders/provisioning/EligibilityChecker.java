package com.example.orders.provisioning;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.orders.order.domain.FailureReason;
import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.plan.PlanRepository;

/** The checks behind CREATED -> VALIDATED. */
@Component
class EligibilityChecker {

    /**
     * Orders that hold their MSISDN. CREATED ones don't: the first order to validate wins, otherwise two
     * waiting orders for one MSISDN would block each other. With a single relay thread validation is
     * serial; several relays on Postgres would need a partial unique index on (partner_id, msisdn).
     */
    private static final Set<OrderStatus> HOLDING_MSISDN =
            EnumSet.of(OrderStatus.VALIDATED, OrderStatus.PROVISIONING, OrderStatus.ACTIVE);

    private final PlanRepository planRepository;
    private final OrderRepository orderRepository;

    EligibilityChecker(PlanRepository planRepository, OrderRepository orderRepository) {
        this.planRepository = planRepository;
        this.orderRepository = orderRepository;
    }

    /** @return why the order is not eligible, or empty if it is */
    Optional<FailureReason> check(Order order) {
        if (!planRepository.existsByCodeAndActiveTrue(order.getPlanCode())) {
            return Optional.of(FailureReason.PLAN_INACTIVE);
        }
        if (orderRepository.existsByPartnerIdAndMsisdnAndStatusInAndIdNot(
                order.getPartnerId(), order.getMsisdn(), HOLDING_MSISDN, order.getId())) {
            return Optional.of(FailureReason.MSISDN_IN_USE);
        }
        return Optional.empty();
    }
}
