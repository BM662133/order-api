package com.example.orders.tenant;

import org.springframework.http.HttpStatus;

import com.example.orders.common.error.ApiException;
import com.example.orders.common.error.ProblemTypes;

public class PartnerMismatchException extends ApiException {

    public PartnerMismatchException() {
        super(HttpStatus.FORBIDDEN, ProblemTypes.PARTNER_MISMATCH, "Partner mismatch",
                "The partner in the path does not match the authenticated partner.");
    }
}
