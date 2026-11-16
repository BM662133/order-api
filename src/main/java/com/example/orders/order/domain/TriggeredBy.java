package com.example.orders.order.domain;

/** Who caused a status change. */
public enum TriggeredBy {
    /** The partner, through the API (create, cancel). */
    CLIENT,
    /** The provisioning worker. */
    SYSTEM
}
