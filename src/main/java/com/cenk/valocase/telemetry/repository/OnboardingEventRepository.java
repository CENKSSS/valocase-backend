package com.cenk.valocase.telemetry.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.telemetry.domain.OnboardingEvent;

public interface OnboardingEventRepository extends JpaRepository<OnboardingEvent, UUID> {

    /**
     * Cheap pre-check for the idempotency key. This is an optimisation, not the
     * guarantee: the unique index on {@code event_id} is what actually prevents a
     * duplicate, because two instances can both pass this check at once.
     */
    boolean existsByEventId(String eventId);
}
