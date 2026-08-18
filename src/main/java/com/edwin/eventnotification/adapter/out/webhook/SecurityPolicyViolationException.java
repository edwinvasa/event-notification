package com.edwin.eventnotification.adapter.out.webhook;

public class SecurityPolicyViolationException extends RuntimeException {

    public SecurityPolicyViolationException(String message) {
        super(message);
    }
}
