package com.intellidesk.ticket.mapper;

import com.intellidesk.sla.service.SlaService;
import com.intellidesk.ticket.dto.TicketResponse;
import com.intellidesk.ticket.entity.Ticket;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Entity -> DTO mapping. Deliberately a plain component (no mapping framework):
 * the mapping is 25 explicit lines - a library would hide the one place where
 * lazy associations are actually resolved.
 *
 * <p>Called inside transactional service methods, so LAZY proxies resolve
 * without extra round trips because the fetch graphs already loaded them.
 * The SLA summary is computed here from the frozen deadline - read-time
 * enrichment, never stored.</p>
 */
@Component
public class TicketMapper {

    private final SlaService slaService;

    public TicketMapper(SlaService slaService) {
        this.slaService = slaService;
    }

    /** Full view for privileged callers (agents/admins see the suggested response). */
    public TicketResponse toResponse(Ticket ticket) {
        return toResponse(ticket, true);
    }

    /**
     * @param includeAgentHints the suggested response is an internal agent hint;
     *        customer-facing views pass {@code false} so it is never exposed.
     *        Sentiment stays visible - it describes the customer's own message.
     */
    public TicketResponse toResponse(Ticket ticket, boolean includeAgentHints) {
        SlaService.SlaEvaluation sla = slaService.evaluate(ticket, Instant.now());
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
                ticket.getResolution(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getSlaDeadlineAt(),
                new TicketResponse.SlaSummary(sla.status(), sla.minutesToDeadline()),
                ticket.getResolvedAt(),
                ticket.getClosedAt(),
                ticket.getEscalatedAt(),
                ticket.getSentiment(),
                includeAgentHints ? ticket.getSuggestedResponse() : null
        );
    }
}
