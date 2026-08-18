package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.domain.notification.FailureReason;
import com.edwin.eventnotification.domain.notification.NotificationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String eventId;

    @Column(nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false)
    private String clientId;

    @Column(nullable = false)
    private Instant eventOccurredAt;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attemptCount;

    private Instant nextAttemptAt;

    private Instant lastAttemptedAt;

    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    private String claimedBy;

    private Instant leaseExpiresAt;

    protected NotificationJpaEntity() {
    }

    public NotificationJpaEntity(
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
        this.id = id;
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.clientId = clientId;
        this.eventOccurredAt = eventOccurredAt;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextAttemptAt = nextAttemptAt;
        this.lastAttemptedAt = lastAttemptedAt;
        this.failureReason = failureReason;
        this.claimedBy = claimedBy;
        this.leaseExpiresAt = leaseExpiresAt;
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
}
