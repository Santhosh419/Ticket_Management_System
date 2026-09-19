package com.intellidesk.ticket.mapper;

import com.intellidesk.ticket.dto.TicketResponse;
import com.intellidesk.ticket.entity.Ticket;
import org.springframework.stereotype.Component;

/**
 * Entity -> DTO mapping. Deliberately a plain component (no mapping framework):
 * the mapping is 20 explicit lines - a library would hide the one place where
 * lazy associations are actually resolved.
 *
 * <p>Called inside transactional service methods, so LAZY proxies resolve
 * without extra round trips because the fetch graphs already loaded them.</p>
 */
@Component
public class TicketMapper {

    public TicketResponse toResponse(Ticket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketNumber(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getStatus(),
                ticket.getPriority(),
                new TicketResponse.CategorySummary(
                        ticket.getCategory().getId(),
                        ticket.getCategory().getCode(),
                        ticket.getCategory().getName()),
                new TicketResponse.UserSummary(
                        ticket.getReporter().getId(),
                        ticket.getReporter().getFullName()),
                ticket.getAssignedAgent() == null ? null : new TicketResponse.UserSummary(
                        ticket.getAssignedAgent().getId(),
                        ticket.getAssignedAgent().getFullName()),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getSlaDeadlineAt(),
                ticket.getResolvedAt(),
                ticket.getClosedAt()
        );
    }
}
