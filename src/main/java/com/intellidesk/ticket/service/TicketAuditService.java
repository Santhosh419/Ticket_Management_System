package com.intellidesk.ticket.service;

import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.dto.TicketHistoryResponse;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.entity.TicketHistory;
import com.intellidesk.ticket.repository.TicketHistoryRepository;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Writes and reads the append-only ticket history.
 *
 * <p>Everything that changes ticket state goes through {@link #record},
 * so the audit trail is complete by construction: workflow transitions,
 * assignments and SLA escalations all funnel through here.</p>
 */
@Service
public class TicketAuditService {

    private static final Logger log = LoggerFactory.getLogger(TicketAuditService.class);

    public static final String SYSTEM_ACTOR = "System";

    private final TicketHistoryRepository historyRepository;
    private final TicketRepository ticketRepository;

    public TicketAuditService(TicketHistoryRepository historyRepository,
                              TicketRepository ticketRepository) {
        this.historyRepository = historyRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public void record(Ticket ticket, TicketStatus oldStatus, TicketStatus newStatus,
                       User actor, String reason) {
        historyRepository.save(new TicketHistory(
                ticket, oldStatus, newStatus, actor, Instant.now(), reason));
        log.info("AUDIT ticket={} {} -> {} by={} reason={}",
                ticket.getTicketNumber(), oldStatus, newStatus,
                actor == null ? SYSTEM_ACTOR : actor.getEmail(), reason);
    }

    /** Idempotency guard: true if this (status, reason) audit row already exists. */
    @Transactional(readOnly = true)
    public boolean alreadyRecorded(Long ticketId, TicketStatus newStatus, String reason) {
        return historyRepository.existsByTicketIdAndNewStatusAndReason(ticketId, newStatus, reason);
    }

    @Transactional(readOnly = true)
    public List<TicketHistoryResponse> getHistory(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));
        return historyRepository.findByTicketIdOrderByChangedAtAsc(ticket.getId()).stream()
                .map(h -> new TicketHistoryResponse(
                        h.getId(),
                        h.getOldStatus(),
                        h.getNewStatus(),
                        h.getChangedBy() == null ? SYSTEM_ACTOR : h.getChangedBy().getFullName(),
                        h.getChangedAt(),
                        h.getReason()))
                .toList();
    }
}
