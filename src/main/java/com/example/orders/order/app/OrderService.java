package com.example.orders.order.app;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderWriter orderWriter;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, OrderWriter orderWriter, Clock clock) {
        this.orderRepository = orderRepository;
        this.orderWriter = orderWriter;
        this.clock = clock;
    }

    /**
     * Creates an order, or returns the existing one if this partner already used the key for the same
     * request. The unique constraint on (partner_id, idempotency_key) is the real guarantee; the lookup
     * is only a fast path.
     *
     * <p>Deliberately not {@code @Transactional}: when two identical requests race, the loser's insert
     * fails inside {@link OrderWriter}'s transaction, and we must be outside it to re-read the winner.
     *
     * @throws InvalidIdempotencyKeyException if the key is missing or malformed
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     */
    public CreateResult create(PartnerContext partner, String rawIdempotencyKey, String msisdn, String planCode,
            SimType simType) {
        IdempotencyKey key = new IdempotencyKey(rawIdempotencyKey);
        String fingerprint = RequestFingerprint.of(msisdn, planCode, simType);

        Optional<CreateResult> replay = findReplay(partner, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        Order order = Order.create(partner.partnerId(), key.value(), fingerprint, msisdn, planCode, simType, clock);
        try {
            return new CreateResult(orderWriter.insert(order), false);
        } catch (DataIntegrityViolationException ex) {
            if (!IdempotencyConflicts.isKeyConflict(ex)) {
                throw ex;
            }
            // Lost the race: the winner has committed (the database made our insert wait for it).
            log.debug("Idempotency race lost for partner {}; returning the winning order", partner.partnerId());
            return findReplay(partner, key, fingerprint).orElseThrow(() -> new IllegalStateException(
                    "Idempotency key conflict but no order found for partner " + partner.partnerId(), ex));
        }
    }

    /** @throws OrderNotFoundException if the order does not exist or belongs to another partner */
    @Transactional(readOnly = true)
    public Order get(PartnerContext partner, UUID orderId) {
        return orderRepository.findByIdAndPartnerId(orderId, partner.partnerId())
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private Optional<CreateResult> findReplay(PartnerContext partner, IdempotencyKey key, String fingerprint) {
        return orderRepository.findByPartnerIdAndIdempotencyKey(partner.partnerId(), key.value())
                .map(existing -> {
                    if (!existing.getRequestFingerprint().equals(fingerprint)) {
                        throw new IdempotencyKeyReusedException(existing.getId());
                    }
                    return new CreateResult(existing, true);
                });
    }
}
