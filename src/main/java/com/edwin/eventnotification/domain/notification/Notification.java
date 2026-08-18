package com.edwin.eventnotification.domain.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Notification {

    private final UUID id;
    private final String eventId;
    private final UUID subscriptionId;
    private final String clientId;
    private final Instant eventOccurredAt;
    private final String payload;

    private NotificationStatus status;
    private int attemptCount;
    private Instant nextAttemptAt;
    private Instant lastAttemptedAt;
    private FailureReason failureReason;
    private String claimedBy;
    private Instant leaseExpiresAt;

    private Notification(
            UUID id,
            String eventId,
            UUID subscriptionId,
            String clientId,
            Instant eventOccurredAt,
            String payload,
            NotificationStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lastAttemptedAt,
            FailureReason failureReason,
            String claimedBy,
            Instant leaseExpiresAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.subscriptionId = Objects.requireNonNull(subscriptionId, "subscriptionId must not be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.eventOccurredAt = Objects.requireNonNull(eventOccurredAt, "eventOccurredAt must not be null");
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptedAt = lastAttemptedAt;
        this.failureReason = failureReason;
        this.claimedBy = claimedBy;
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public static Notification create(
            UUID id,
            String eventId,
            UUID subscriptionId,
            String clientId,
            Instant eventOccurredAt,
            String payload) {
        return new Notification(
                id,
                eventId,
                subscriptionId,
                clientId,
                eventOccurredAt,
                payload,
                NotificationStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    public static Notification restore(
            UUID id,
            String eventId,
            UUID subscriptionId,
            String clientId,
            Instant eventOccurredAt,
            String payload,
            NotificationStatus status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant lastAttemptedAt,
            FailureReason failureReason,
            String claimedBy,
            Instant leaseExpiresAt) {
        return new Notification(
                id,
                eventId,
                subscriptionId,
                clientId,
                eventOccurredAt,
                payload,
                status,
                attemptCount,
                nextAttemptAt,
                lastAttemptedAt,
                failureReason,
                claimedBy,
                leaseExpiresAt);
    }

    public void claim(String workerId, Instant leaseExpiresAt) {
        requireStatus(NotificationStatus.PENDING, "be claimed");
        this.status = NotificationStatus.PROCESSING;
        this.claimedBy = Objects.requireNonNull(workerId, "workerId must not be null");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    }

    public void recordSuccess(Instant occurredAt) {
        requireStatus(NotificationStatus.PROCESSING, "record a delivery outcome");
        this.status = NotificationStatus.COMPLETED;
        this.attemptCount++;
        this.lastAttemptedAt = occurredAt;
        this.nextAttemptAt = null;
        this.claimedBy = null;
        this.leaseExpiresAt = null;
    }

    public void recordRetryableFailure(Instant occurredAt, Instant nextAttemptAt) {
        requireStatus(NotificationStatus.PROCESSING, "record a delivery outcome");
        this.status = NotificationStatus.PENDING;
        this.attemptCount++;
        this.lastAttemptedAt = occurredAt;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        this.claimedBy = null;
        this.leaseExpiresAt = null;
    }

    public void recordDefinitiveFailure(Instant occurredAt, FailureReason reason) {
        requireStatus(NotificationStatus.PROCESSING, "record a delivery outcome");
        this.status = NotificationStatus.FAILED;
        this.attemptCount++;
        this.lastAttemptedAt = occurredAt;
        this.nextAttemptAt = null;
        this.failureReason = Objects.requireNonNull(reason, "reason must not be null");
        this.claimedBy = null;
        this.leaseExpiresAt = null;
    }

    public void replay() {
        requireStatus(NotificationStatus.FAILED, "be replayed");
        this.status = NotificationStatus.PENDING;
        this.nextAttemptAt = null;
        this.failureReason = null;
    }

    public boolean isFailed() {
        return status == NotificationStatus.FAILED;
    }

    public boolean belongsTo(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void requireStatus(NotificationStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Notification " + id + " must be " + expected + " to " + action + ", but was " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getClientId() {
        return clientId;
    }

    public Instant getEventOccurredAt() {
        return eventOccurredAt;
    }

    public String getPayload() {
        return payload;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Notification other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
