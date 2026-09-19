package com.intellidesk.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.intellidesk.ticket.domain.TicketPriority;

/**
 * Ticket creation payload.
 *
 * <p>Category and priority are EXPLICIT inputs in Phase 3. Phase 7 replaces
 * them with AI classification - the controller/service signature stays the
 * same, only the caller changes. Status is never accepted: new tickets always
 * start OPEN, decided by the domain, not the client.</p>
 */
public record CreateTicketRequest(

        @NotBlank(message = "Title is required")
        @Size(min = 5, max = 150, message = "Title must be 5-150 characters")
        String title,

        @NotBlank(message = "Description is required")
        @Size(min = 15, max = 5000, message = "Description must be 15-5000 characters")
        String description,

        @NotNull(message = "Category is required")
        @Positive(message = "CategoryId must be a positive number")
        Long categoryId,

        @NotNull(message = "Priority is required")
        TicketPriority priority
) {}
