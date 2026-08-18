package com.edwin.eventnotification.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionJpaEntity, UUID> {

    List<SubscriptionJpaEntity> findByClientIdAndActiveTrue(String clientId);
}
