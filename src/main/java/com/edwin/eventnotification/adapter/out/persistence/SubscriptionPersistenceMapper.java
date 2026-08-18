package com.edwin.eventnotification.adapter.out.persistence;

import org.springframework.stereotype.Component;

import com.edwin.eventnotification.domain.subscription.Subscription;

@Component
public class SubscriptionPersistenceMapper {

    public Subscription toDomain(SubscriptionJpaEntity entity) {
        return new Subscription(
                entity.getId(), entity.getClientId(), entity.getWebhookUrl(), entity.getHmacSecret(),
                entity.isActive());
    }
}
