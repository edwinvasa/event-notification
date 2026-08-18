package com.edwin.eventnotification.adapter.out.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.delivery.Trigger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttemptJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID notificationId;

    @Column(nullable = false)
    private int attemptNumber;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Duration duration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryOutcomeType outcomeType;

    private Integer httpStatusCode;

    private String errorDetail;

    private String responseSnippet;

    @Column(nullable = false)
    private String urlUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Trigger trigger;

    protected DeliveryAttemptJpaEntity() {
    }

    public DeliveryAttemptJpaEntity(
            UUID id,
            UUID notificationId,
            int attemptNumber,
            Instant occurredAt,
            Duration duration,
            DeliveryOutcomeType outcomeType,
            Integer httpStatusCode,
            String errorDetail,
            String responseSnippet,
            String urlUsed,
            Trigger trigger) {
        this.id = id;
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.occurredAt = occurredAt;
        this.duration = duration;
        this.outcomeType = outcomeType;
        this.httpStatusCode = httpStatusCode;
        this.errorDetail = errorDetail;
        this.responseSnippet = responseSnippet;
        this.urlUsed = urlUsed;
        this.trigger = trigger;
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Duration getDuration() {
        return duration;
    }

    public DeliveryOutcomeType getOutcomeType() {
        return outcomeType;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public String getResponseSnippet() {
        return responseSnippet;
    }

    public String getUrlUsed() {
        return urlUsed;
    }

    public Trigger getTrigger() {
        return trigger;
    }
}
