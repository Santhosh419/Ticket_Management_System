package com.intellidesk.ticket.repository;

import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.TicketHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    @EntityGraph(attributePaths = {"changedBy"})
    List<TicketHistory> findByTicketIdOrderByChangedAtAsc(Long ticketId);

    /** Idempotency guard for the SLA scheduler (Phase 6): no duplicate escalation rows. */
    boolean existsByTicketIdAndNewStatusAndReason(Long ticketId, TicketStatus newStatus, String reason);
}
