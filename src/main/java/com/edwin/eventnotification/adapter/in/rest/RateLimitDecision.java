package com.edwin.eventnotification.adapter.in.rest;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
}
