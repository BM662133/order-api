package com.example.orders.order.app;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.common.error.FieldViolation;
import com.example.orders.common.error.RequestValidationException;
import com.example.orders.order.domain.Order;
import com.example.orders.order.history.OrderStatusHistoryEntry;
import com.example.orders.order.history.OrderStatusHistoryRepository;
import com.example.orders.order.persistence.IdempotencyRecordRepository;
import com.example.orders.order.persistence.OrderRepository;
import com.example.orders.tenant.PartnerContext;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    static final int MAX_CANCEL_ATTEMPTS = 3;

    private final OrderRepository orderRepository;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderWriter orderWriter;
    private final Validator validator;
    private final Clock clock;

    public OrderService(OrderRepository orderRepository, IdempotencyRecordRepository idempotencyRecords,
            OrderStatusHistoryRepository historyRepository, OrderWriter orderWriter, Validator validator,
            Clock clock) {
        this.orderRepository = orderRepository;
        this.idempotencyRecords = idempotencyRecords;
        this.historyRepository = historyRepository;
        this.orderWriter = orderWriter;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Creates an order, or returns the existing one if this partner used the key for the same request
     * and the key has not expired ({@code orders.idempotency.key-ttl}). The unique constraint on the
     * key table is the real guarantee; the lookup is only a fast path.
     *
     * <p>Deliberately not {@code @Transactional}: when two identical requests race, the loser's insert
     * fails inside {@link OrderWriter}'s transaction, and we must be outside it to re-read the winner.
     *
     * <p>Order of checks: key format, then replay, then the {@link CreateOrderCommand} constraints that
     * depend on database state. A retry of a request that succeeded must replay even if, say, its plan
     * has been deactivated since.
     *
     * @throws InvalidIdempotencyKeyException if the key is missing or malformed
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     * @throws RequestValidationException if the plan is unknown or inactive (new requests only)
     */
    public CreateResult create(PartnerContext partner, String rawIdempotencyKey, CreateOrderCommand command) {
        IdempotencyKey key = new IdempotencyKey(rawIdempotencyKey);
        String fingerprint = RequestFingerprint.of(command.msisdn(), command.planCode(), command.simType());

        Optional<CreateResult> replay = findReplay(partner, key, fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        validate(command);
        Order order = Order.create(partner.partnerId(), key.value(), fingerprint, command.msisdn(),
                command.planCode(), command.simType(), clock);
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

    /**
     * Cancels the partner's order, or during PROVISIONING records the request for the provisioning
     * worker; repeats are no-ops (see {@link com.example.orders.order.domain.CancelOutcome}). A
     * concurrent update is retried in a fresh transaction that re-reads the order, so the decision is
     * always made on its latest state.
     *
     * @throws OrderNotFoundException if the order does not exist or belongs to another partner
     * @throws com.example.orders.order.domain.IllegalTransitionException from ACTIVE or FAILED
     * @throws OrderConcurrentlyModifiedException if every attempt hit a concurrent update
     */
    public CancelResult cancel(PartnerContext partner, UUID orderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return orderWriter.cancel(partner.partnerId(), orderId);
            } catch (ConcurrencyFailureException ex) {
                if (attempt >= MAX_CANCEL_ATTEMPTS) {
                    throw new OrderConcurrentlyModifiedException(orderId, ex);
                }
                log.debug("Concurrent update cancelling order {} (attempt {}, {}); retrying", orderId, attempt,
                        ex.getClass().getSimpleName());
            }
        }
    }

    /** @throws OrderNotFoundException if the order does not exist or belongs to another partner */
    @Transactional(readOnly = true)
    public Order get(PartnerContext partner, UUID orderId) {
        return orderRepository.findByIdAndPartnerId(orderId, partner.partnerId())
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * The order's status timeline, oldest first.
     *
     * @throws OrderNotFoundException if the order does not exist or belongs to another partner
     */
    @Transactional(readOnly = true)
    public List<OrderStatusHistoryEntry> history(PartnerContext partner, UUID orderId) {
        get(partner, orderId);
        return historyRepository.findByOrderIdAndPartnerIdOrderByIdAsc(orderId, partner.partnerId());
    }

    private void validate(CreateOrderCommand command) {
        Set<ConstraintViolation<CreateOrderCommand>> violations = validator.validate(command);
        if (!violations.isEmpty()) {
            throw new RequestValidationException(violations.stream()
                    .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                    .toList());
        }
    }

    /** An expired key is treated as unused; {@link OrderWriter#insert} deletes its row before reusing it. */
    private Optional<CreateResult> findReplay(PartnerContext partner, IdempotencyKey key, String fingerprint) {
        return idempotencyRecords.findByPartnerIdAndIdempotencyKey(partner.partnerId(), key.value())
                .filter(record -> !record.isExpiredAt(clock.instant()))
                .flatMap(record -> orderRepository.findByIdAndPartnerId(record.getOrderId(), partner.partnerId()))
                .map(existing -> {
                    if (!existing.getRequestFingerprint().equals(fingerprint)) {
                        throw new IdempotencyKeyReusedException(existing.getId());
                    }
                    return new CreateResult(existing, true);
                });
    }
}
