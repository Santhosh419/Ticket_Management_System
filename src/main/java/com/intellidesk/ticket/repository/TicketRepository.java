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
     * preventing lazy-loading N+1 problems when building the DTO.
     */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category", "slaPolicy"})
    Optional<Ticket> findWithDetailsById(Long id);

    /** Customer's own tickets (access rule enforced by the service). */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category"})
    Page<Ticket> findByReporterId(Long reporterId, Pageable pageable);

    /** Agent's assigned tickets. */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category"})
    Page<Ticket> findByAssignedAgentId(Long agentId, Pageable pageable);

    /** Admin: every ticket, newest first. */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category"})
    Page<Ticket> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Admin: filtered by status. */
    @EntityGraph(attributePaths = {"reporter", "assignedAgent", "category"})
    Page<Ticket> findByStatus(TicketStatus status, Pageable pageable);

    /** SLA monitor: breached work items, idempotent batch scan (paged). */
    List<Ticket> findByStatusInAndSlaDeadlineAtBefore(List<TicketStatus> statuses,
                                                      Instant deadline,
                                                      Pageable pageable);

    /** Assignment workload term: an agent's in-flight ticket count. */
    long countByAssignedAgentIdAndStatusIn(Long agentId, java.util.Collection<TicketStatus> statuses);
}
