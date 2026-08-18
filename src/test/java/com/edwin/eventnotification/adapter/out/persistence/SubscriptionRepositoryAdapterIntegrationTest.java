package com.edwin.eventnotification.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.domain.subscription.Subscription;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubscriptionRepositoryAdapterIntegrationTest {

    @Autowired
    private SubscriptionPort subscriptionPort;

    @Autowired
    private SubscriptionJpaRepository subscriptionJpaRepository;

    private final List<UUID> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdIds.forEach(subscriptionJpaRepository::deleteById);
        createdIds.clear();
    }

    private UUID seed(String clientId, boolean active) {
        UUID id = UUID.randomUUID();
        subscriptionJpaRepository.save(
                new SubscriptionJpaEntity(id, clientId, "https://example.com/webhook", "secret", active));
        createdIds.add(id);
        return id;
    }

    @Test
    void findByIdReturnsExistingSubscription() {
        UUID id = seed("client-1", true);

        Optional<Subscription> found = subscriptionPort.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().clientId()).isEqualTo("client-1");
    }

    @Test
    void findByIdReturnsEmptyWhenSubscriptionDoesNotExist() {
        Optional<Subscription> found = subscriptionPort.findById(UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    void findActiveSubscriptionsReturnsOnlyActiveOnesForClient() {
        String clientId = "client-" + UUID.randomUUID();
        UUID activeId = seed(clientId, true);
        seed(clientId, false);

        List<Subscription> found = subscriptionPort.findActiveSubscriptions(clientId, "credit_card_payment");

        assertThat(found).extracting(Subscription::id).containsExactly(activeId);
    }

    @Test
    void subscriptionFromAnotherClientIsNotReturned() {
        String clientId = "client-" + UUID.randomUUID();
        String otherClientId = "client-" + UUID.randomUUID();
        UUID ownId = seed(clientId, true);
        seed(otherClientId, true);

        List<Subscription> found = subscriptionPort.findActiveSubscriptions(clientId, "credit_card_payment");

        assertThat(found).extracting(Subscription::id).containsExactly(ownId);
    }

    @Test
    void inactiveSubscriptionIsNotReturned() {
        String clientId = "client-" + UUID.randomUUID();
        seed(clientId, false);

        List<Subscription> found = subscriptionPort.findActiveSubscriptions(clientId, "credit_card_payment");

        assertThat(found).isEmpty();
    }
}
