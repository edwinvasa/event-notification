package com.edwin.eventnotification.domain.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryErrorClassifierTest {

    private final DeliveryErrorClassifier classifier = new DeliveryErrorClassifier();

    private DeliveryOutcome outcome(DeliveryOutcomeType type, Integer httpStatusCode) {
        return new DeliveryOutcome(type, httpStatusCode, 10L, null, null, null);
    }

    @Test
    void successCannotBeClassified() {
        assertThatThrownBy(() -> classifier.classify(outcome(DeliveryOutcomeType.SUCCESS, 200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serverErrorIsRetryable() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.HTTP_ERROR, 500)))
                .isEqualTo(DeliveryErrorClassifier.Classification.RETRYABLE);
    }

    @Test
    void tooManyRequestsIsRetryable() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.HTTP_ERROR, 429)))
                .isEqualTo(DeliveryErrorClassifier.Classification.RETRYABLE);
    }

    @Test
    void clientErrorIsPermanentByDefault() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.HTTP_ERROR, 404)))
                .isEqualTo(DeliveryErrorClassifier.Classification.PERMANENT);
    }

    @Test
    void conflictIsPermanentByDefault() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.HTTP_ERROR, 409)))
                .isEqualTo(DeliveryErrorClassifier.Classification.PERMANENT);
    }

    @Test
    void timeoutIsRetryable() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.TIMEOUT, null)))
                .isEqualTo(DeliveryErrorClassifier.Classification.RETRYABLE);
    }

    @Test
    void connectionErrorIsRetryable() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.CONNECTION_ERROR, null)))
                .isEqualTo(DeliveryErrorClassifier.Classification.RETRYABLE);
    }

    @Test
    void dnsErrorIsRetryable() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.DNS_ERROR, null)))
                .isEqualTo(DeliveryErrorClassifier.Classification.RETRYABLE);
    }

    @Test
    void securityPolicyViolationIsPermanent() {
        assertThat(classifier.classify(outcome(DeliveryOutcomeType.SECURITY_POLICY_VIOLATION, null)))
                .isEqualTo(DeliveryErrorClassifier.Classification.PERMANENT);
    }
}
