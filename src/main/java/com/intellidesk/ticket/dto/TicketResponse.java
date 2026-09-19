package com.intellidesk.ticket.dto;

import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;

import java.time.Instant;

/**
 * The public view of a ticket. Entity fields are copied explicitly, so the
 * REST contract never changes when the entity evolves and no lazy relation
 * can leak into JSON (no cycles, no N+1 surprises).
 *
 * <p>Enrichment data (slaPolicy, version) stays internal; clients see the
 * deadline, not the policy machinery.</p>
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
        Instant createdAt,
        Instant updatedAt,
        Instant slaDeadlineAt,
        Instant resolvedAt,
        Instant closedAt
) {

    public record CategorySummary(Long id, String code, String name) {}

    public record UserSummary(Long id, String fullName) {}
}
