package com.intellidesk.ticket.dto;

import com.intellidesk.ticket.domain.TicketStatus;

import java.time.Instant;

/**
 * One row of the append-only audit trail. Actor is absent for system events
 * (scheduler, auto-assignment) - the reason string explains what happened.
 */
public record TicketHistoryResponse(
        Long id,
        TicketStatus oldStatus,
        TicketStatus newStatus,
        String changedBy,
        Instant changedAt,
        String reason
) {}
