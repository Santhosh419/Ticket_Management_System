package com.intellidesk.ticket.dto;

import com.intellidesk.sla.service.SlaService.SlaStatus;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;

import java.time.Instant;

/**
 * The public view of a ticket. Entity fields are copied explicitly, so the
 * REST contract never changes when the entity evolves and no lazy relation
 * can leak into JSON (no cycles, no N+1 surprises).
 *
 * <p>Null fields are omitted ({@code non_null} JSON inclusion): assignedAgent
 * (unassigned), resolution (not yet resolved), resolvedAt/closedAt
 * (not yet reached), escalatedAt (never escalated).</p>
 */
public record TicketResponse(
        Long id,
        String ticketNumber,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        CategorySummary category,
        UserSummary customer,
        UserSummary assignedAgent,
        String resolution,
        Instant createdAt,
        Instant updatedAt,
        Instant slaDeadlineAt,
        SlaSummary sla,
        Instant resolvedAt,
        Instant closedAt,
        Instant escalatedAt
) {

    public record CategorySummary(Long id, String code, String name) {}

    public record UserSummary(Long id, String fullName) {}

    /** Live SLA health, computed at read time (see SlaService). */
    public record SlaSummary(SlaStatus status, long minutesToDeadline) {}
}
