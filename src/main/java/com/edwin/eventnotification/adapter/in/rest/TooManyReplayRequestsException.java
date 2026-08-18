package com.edwin.eventnotification.adapter.in.rest;

/**
 * Thrown by the REST layer (not the use case) when the per-client replay rate limit (ADR-008 §2)
 * rejects a request before it reaches {@code ReplayNotificationPort}.
 */
public class TooManyReplayRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyReplayRequestsException(long retryAfterSeconds) {
        super("Too many replay requests");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
