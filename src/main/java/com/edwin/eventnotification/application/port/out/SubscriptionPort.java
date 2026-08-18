package com.edwin.eventnotification.application.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.edwin.eventnotification.domain.subscription.Subscription;

public interface SubscriptionPort {

    List<Subscription> findActiveSubscriptions(String clientId, String eventType);

    Optional<Subscription> findById(UUID subscriptionId);
}
