package com.intellidesk.ticket.repository;

import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByTicketNumber(String ticketNumber);

    boolean existsByTicketNumber(String ticketNumber);

    /**
     * Detail fetch joins every association used by TicketResponse in one query,
     * preventing lazy-loading N+1 problems when building DTOs.
     */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category", "slaPolicy"})
    Optional<Ticket> findWithDetailsById(Long id);

    List<Ticket> findByStatusInAndSlaDeadlineAtBefore(List<TicketStatus> statuses,
                                                      Instant deadline,
                                                      Pageable pageable);

    // Slice-style filtered queries used by search/pagination (Phase 8).
    Page<Ticket> findByReporterId(Long reporterId, Pageable pageable);

    Page<Ticket> findByAssignedAgentId(Long agentId, Pageable pageable);

    Page<Ticket> findByStatus(TicketStatus status, Pageable pageable);
}
