package com.intellidesk.ticket.dto;

import com.intellidesk.ticket.domain.TicketPriority;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Partial ticket update (PATCH semantics): every field is optional and only
 * provided fields are applied. "At least one field" is enforced in the
 * service - bean validation cannot express it cleanly on a record.
 *
 * <p>Authorization is deliberately split: CUSTOMER may send title/description
 * (while the ticket is OPEN); only ADMIN may change category/priority. A
 * customer attempting the admin fields gets a 403, enforced in the service.</p>
 */
public record UpdateTicketRequest(

        @Size(min = 5, max = 150, message = "Title must be 5-150 characters")
        String title,

        @Size(min = 15, max = 5000, message = "Description must be 15-5000 characters")
        String description,

        @Positive(message = "CategoryId must be a positive number")
        Long categoryId,

        TicketPriority priority
) {}
