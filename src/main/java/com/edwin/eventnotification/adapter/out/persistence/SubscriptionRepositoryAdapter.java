package com.edwin.eventnotification.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.edwin.eventnotification.application.port.out.SubscriptionPort;
import com.edwin.eventnotification.domain.subscription.Subscription;

@Repository
public class SubscriptionRepositoryAdapter implements SubscriptionPort {

    private final SubscriptionJpaRepository subscriptionJpaRepository;
    private final SubscriptionPersistenceMapper mapper;

    public SubscriptionRepositoryAdapter(
            SubscriptionJpaRepository subscriptionJpaRepository, SubscriptionPersistenceMapper mapper) {
        this.subscriptionJpaRepository = subscriptionJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public List<Subscription> findActiveSubscriptions(String clientId, String eventType) {
        return subscriptionJpaRepository.findByClientIdAndActiveTrue(clientId).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Subscription> findById(UUID subscriptionId) {
        return subscriptionJpaRepository.findById(subscriptionId).map(mapper::toDomain);
    }
}
