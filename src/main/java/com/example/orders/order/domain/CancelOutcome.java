package com.example.orders.order.domain;

/** Result of {@link Order#cancel}. */
public enum CancelOutcome {

    /** Cancelled now. */
    CANCELLED(true, false),
    /** Was already cancelled; nothing changed. */
    ALREADY_CANCELLED(false, false),
    /** PROVISIONING: cancellation recorded; the provisioning worker completes it after compensating. */
    CANCEL_REQUESTED(true, true),
    /** PROVISIONING and a cancellation was already requested; nothing changed. */
    ALREADY_REQUESTED(false, true);

    private final boolean changed;
    private final boolean pending;

    CancelOutcome(boolean changed, boolean pending) {
        this.changed = changed;
        this.pending = pending;
    }

    /** Whether the order was modified and must be saved. */
    public boolean changed() {
        return changed;
    }

    /** Whether the cancellation completes asynchronously (the API answers 202 instead of 200). */
    public boolean pending() {
        return pending;
    }
}
