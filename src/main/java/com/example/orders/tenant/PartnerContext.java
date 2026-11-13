package com.example.orders.tenant;

/** The authenticated partner for the current request. Every tenant-scoped operation takes one. */
public record PartnerContext(String partnerId) {
}
