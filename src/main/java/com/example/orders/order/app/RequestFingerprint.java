package com.example.orders.order.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.orders.order.domain.Order;
import com.example.orders.order.domain.SimType;

/**
 * SHA-256 over the normalized create request, so retries that differ only in JSON whitespace, key order
 * or a leading '+' on the MSISDN still match. Bump the version prefix if the canonical form changes.
 */
final class RequestFingerprint {

    private static final String VERSION = "v1";

    private RequestFingerprint() {
    }

    static String of(String msisdn, String planCode, SimType simType) {
        String canonical = VERSION
                + "|msisdn=" + Order.normalizeMsisdn(msisdn)
                + "|planCode=" + planCode
                + "|simType=" + simType;
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
