package com.stockcharts.risk;

import java.util.List;

/** An order that breaks a risk rule (HTTP 422) or lacks a valid confirmation (HTTP 428). */
public class OrderRejectedException extends RuntimeException {

    private final List<String> violations;
    private final boolean confirmationRequired;

    public OrderRejectedException(String message, List<String> violations, boolean confirmationRequired) {
        super(message);
        this.violations = violations;
        this.confirmationRequired = confirmationRequired;
    }

    public List<String> getViolations() {
        return violations;
    }

    public boolean isConfirmationRequired() {
        return confirmationRequired;
    }
}
