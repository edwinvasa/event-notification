package com.edwin.eventnotification.domain.delivery;

public enum DeliveryOutcomeType {
    SUCCESS,
    HTTP_ERROR,
    TIMEOUT,
    CONNECTION_ERROR,
    DNS_ERROR,
    INTERRUPTED,
    CIRCUIT_OPEN,
    SECURITY_POLICY_VIOLATION
}
