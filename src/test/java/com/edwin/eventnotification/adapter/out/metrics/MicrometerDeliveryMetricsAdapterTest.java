package com.edwin.eventnotification.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.notification.FailureReason;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MicrometerDeliveryMetricsAdapterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerDeliveryMetricsAdapter adapter = new MicrometerDeliveryMetricsAdapter(meterRegistry);

    @Test
    void recordDeliveryAttemptIncrementsTheCounterTaggedByOutcome() {
        adapter.recordDeliveryAttempt(DeliveryOutcomeType.SUCCESS, 120L);
        adapter.recordDeliveryAttempt(DeliveryOutcomeType.SUCCESS, 80L);
        adapter.recordDeliveryAttempt(DeliveryOutcomeType.TIMEOUT, 5000L);

        assertThat(meterRegistry.counter("notification.delivery.attempts", "outcome", "SUCCESS").count())
                .isEqualTo(2.0);
        assertThat(meterRegistry.counter("notification.delivery.attempts", "outcome", "TIMEOUT").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordDeliveryAttemptRecordsDurationInTheTimerTaggedByOutcome() {
        adapter.recordDeliveryAttempt(DeliveryOutcomeType.SUCCESS, 250L);

        assertThat(meterRegistry
                        .timer("notification.delivery.duration", "outcome", "SUCCESS")
                        .totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(250.0);
    }

    @Test
    void recordRetryScheduledIncrementsTheRetryCounter() {
        adapter.recordRetryScheduled();
        adapter.recordRetryScheduled();

        assertThat(meterRegistry.counter("notification.retry.scheduled").count()).isEqualTo(2.0);
    }

    @Test
    void recordDefinitiveFailureIncrementsTheCounterTaggedByReason() {
        adapter.recordDefinitiveFailure(FailureReason.MAX_ATTEMPTS_EXCEEDED);
        adapter.recordDefinitiveFailure(FailureReason.PERMANENT_ERROR);
        adapter.recordDefinitiveFailure(FailureReason.PERMANENT_ERROR);

        assertThat(meterRegistry
                        .counter("notification.failed.definitive", "reason", "MAX_ATTEMPTS_EXCEEDED")
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .counter("notification.failed.definitive", "reason", "PERMANENT_ERROR")
                        .count())
                .isEqualTo(2.0);
    }
}
