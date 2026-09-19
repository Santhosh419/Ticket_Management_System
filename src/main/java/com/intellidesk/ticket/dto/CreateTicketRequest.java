package com.intellidesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.intellidesk.ticket.domain.TicketPriority;

/**
 * Ticket creation payload.
 *
 * <p>Category and priority are OPTIONAL: when omitted, they come from the
 * classification layer ({@code TicketClassificationService} - rule-based by
 * default, replaceable by an AI implementation via configuration). Explicit
 * values always win, which keeps the Phase 3 contract intact. Status is never
 * accepted: new tickets always start OPEN, decided by the domain, not the
 * client.</p>
 */
public record CreateTicketRequest(

        @NotBlank(message = "Title is required")
        @Size(min = 5, max = 150, message = "Title must be 5-150 characters")
        String title,

        @NotBlank(message = "Description is required")
        @Size(min = 15, max = 5000, message = "Description must be 15-5000 characters")
        String description,

        @Positive(message = "CategoryId must be a positive number")
        Long categoryId,

        TicketPriority priority
) {}
