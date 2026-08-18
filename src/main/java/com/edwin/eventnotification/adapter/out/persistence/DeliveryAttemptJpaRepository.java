package com.edwin.eventnotification.adapter.out.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptJpaRepository extends JpaRepository<DeliveryAttemptJpaEntity, UUID> {
}
