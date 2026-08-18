package com.edwin.eventnotification.domain.delivery;

import java.util.Objects;

public class DeliveryErrorClassifier {

    public enum Classification {
        RETRYABLE,
        PERMANENT
    }

    public Classification classify(DeliveryOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");

        return switch (outcome.outcomeType()) {
            case SUCCESS -> throw new IllegalArgumentException("Cannot classify a successful delivery outcome");
            case TIMEOUT, CONNECTION_ERROR, DNS_ERROR, INTERRUPTED, CIRCUIT_OPEN -> Classification.RETRYABLE;
            case SECURITY_POLICY_VIOLATION -> Classification.PERMANENT;
            case HTTP_ERROR -> classifyHttpStatus(outcome.httpStatusCode());
        };
    }

    private Classification classifyHttpStatus(Integer httpStatusCode) {
        Objects.requireNonNull(httpStatusCode, "httpStatusCode must not be null for HTTP_ERROR");

        if (httpStatusCode == 429) {
            return Classification.RETRYABLE;
        }
        if (httpStatusCode >= 500) {
            return Classification.RETRYABLE;
        }
        return Classification.PERMANENT;
    }
}
