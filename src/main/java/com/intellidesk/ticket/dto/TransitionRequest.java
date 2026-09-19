package com.intellidesk.ticket.dto;

import com.intellidesk.ticket.domain.TicketStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/tickets/{id}/status.
 * {@code resolution} is REQUIRED when target = RESOLVED (enforced in the
 * workflow service, because it depends on the target value).
 */
public record TransitionRequest(

        @NotNull(message = "Target status is required")
        TicketStatus target,

        @Size(max = 255, message = "Reason must be at most 255 characters")
        String reason,

        @Size(max = 5000, message = "Resolution must be at most 5000 characters")
        String resolution
) {}
