package com.edwin.eventnotification.adapter.out.metrics;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.edwin.eventnotification.application.port.out.DeliveryMetricsPort;
import com.edwin.eventnotification.domain.delivery.DeliveryOutcomeType;
import com.edwin.eventnotification.domain.notification.FailureReason;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class MicrometerDeliveryMetricsAdapter implements DeliveryMetricsPort {

    private final MeterRegistry meterRegistry;

    public MicrometerDeliveryMetricsAdapter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordDeliveryAttempt(DeliveryOutcomeType outcomeType, long durationMillis) {
        meterRegistry.counter("notification.delivery.attempts", "outcome", outcomeType.name()).increment();
        meterRegistry
                .timer("notification.delivery.duration", "outcome", outcomeType.name())
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRetryScheduled() {
        meterRegistry.counter("notification.retry.scheduled").increment();
    }

    @Override
    public void recordDefinitiveFailure(FailureReason reason) {
        meterRegistry.counter("notification.failed.definitive", "reason", reason.name()).increment();
    }
}
