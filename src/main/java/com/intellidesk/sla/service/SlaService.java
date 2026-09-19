package com.intellidesk.sla.service;

import com.intellidesk.sla.SlaProperties;
import com.intellidesk.ticket.entity.Ticket;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * SLA mathematics in ONE place.
 *
 * <p>Deadline policy (documented in README): the deadline is computed ONCE
 * from the policy of the ticket's priority at the moment it is created (or
 * when an admin later changes the priority) and frozen on the ticket row.
 * Paused time (WAITING_FOR_CUSTOMER) deliberately does NOT extend the
 * deadline - the simple model is explainable to customers and agents alike;
 * business-hours calendars are a documented future improvement.</p>
 *
 * <p>Breach evaluation is a pure function of (deadline, now) plus the
 * at-risk threshold - fully deterministic and unit-testable.</p>
 */
@Service
public class SlaService {

    /** SLA health of a ticket at a point in time. */
    public enum SlaStatus { ON_TRACK, AT_RISK, BREACHED }

    public record SlaEvaluation(SlaStatus status, long minutesToDeadline) {}

    private final SlaProperties properties;

    public SlaService(SlaProperties properties) {
        this.properties = properties;
    }

    /** Deadline = reference time + policy hours (policy comes from the DB, never code). */
    public Instant computeDeadline(int resolutionHours, Instant from) {
        return from.plus(Duration.ofHours(resolutionHours));
    }

    /**
     * Evaluates the ticket's SLA health against the given clock instant.
     * BREACHED strictly after the deadline; AT_RISK within the configured
     * window before it; ON_TRACK otherwise.
     */
    public SlaEvaluation evaluate(Ticket ticket, Instant now) {
        Instant deadline = ticket.getSlaDeadlineAt();
        long minutes = Duration.between(now, deadline).toMinutes();
        if (now.isAfter(deadline) || now.equals(deadline)) {
            return new SlaEvaluation(SlaStatus.BREACHED, minutes);
        }
        if (minutes <= properties.atRiskMinutes()) {
            return new SlaEvaluation(SlaStatus.AT_RISK, minutes);
        }
        return new SlaEvaluation(SlaStatus.ON_TRACK, minutes);
    }
}
