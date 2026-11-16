package com.example.orders.provisioning;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.provisioning.ProvisioningSweepQueries.OrderRef;

/**
 * The safety net behind the outbox:
 *
 * <ul>
 * <li><b>Retry stalled orders.</b> An unfinished order that nothing will move on (no PENDING event, e.g.
 * a Kafka publish that went DEAD, or a give-up step that failed) gets a fresh
 * {@code ProvisioningRetryRequested} event. Safe even if the order wasn't really stuck: the worker
 * resumes from the current status and the gateway is idempotent.
 * <li><b>Reconcile uncertain failures.</b> After PROVISIONING_UNAVAILABLE or COMPENSATION_FAILED we don't
 * know whether the carrier activated the line. If it did, deactivate it, so a FAILED order never leaves
 * a live line behind. Gateway still down: try again next sweep.
 * </ul>
 */
@Component
public class ProvisioningSweeper {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningSweeper.class);

    public record Result(int retried, int reconciled) {
    }

    private final ProvisioningSweepQueries queries;
    private final ProvisioningSteps steps;
    private final ProvisioningGateway gateway;
    private final ProvisioningSweepProperties properties;
    private final Clock clock;

    ProvisioningSweeper(ProvisioningSweepQueries queries, ProvisioningSteps steps, ProvisioningGateway gateway,
            ProvisioningSweepProperties properties, Clock clock) {
        this.queries = queries;
        this.steps = steps;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
    }

    public Result sweep() {
        return new Result(retryStalled(), reconcileFailed());
    }

    private int retryStalled() {
        int retried = 0;
        for (OrderRef ref : queries.findStalled(clock.instant().minus(properties.stalledAfter()),
                properties.batchSize())) {
            if (steps.requestRetry(ref.partnerId(), ref.orderId())) {
                log.warn("Order {} stalled; requested a provisioning retry", ref.orderId());
                retried++;
            }
        }
        return retried;
    }

    private int reconcileFailed() {
        int reconciled = 0;
        for (OrderRef ref : queries.findUnreconciled(properties.batchSize())) {
            Order order = steps.find(ref.partnerId(), ref.orderId()).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.FAILED || order.getReconciledAt() != null) {
                continue;
            }
            LineRequest line = LineRequest.from(order);
            try {
                if (gateway.isActive(line)) {
                    gateway.deactivate(line);
                    log.warn("Order {} failed ({}) but its line was active; deactivated it", order.getId(),
                            order.getFailureReason());
                }
                steps.reconciled(ref.partnerId(), ref.orderId());
                reconciled++;
            } catch (GatewayUnavailableException ex) {
                log.info("Gateway unavailable reconciling order {}; will retry next sweep", order.getId());
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile order {}; will retry next sweep", order.getId(), ex);
            }
        }
        return reconciled;
    }
}
