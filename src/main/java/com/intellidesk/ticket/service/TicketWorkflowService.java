package com.intellidesk.ticket.service;

import com.intellidesk.common.exception.InvalidRequestException;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.common.exception.UnauthorizedAccessException;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * The controlled ticket state machine, enforced end to end:
 *
 * <ol>
 *   <li>DOMAIN rule: {@link TicketStatus#canTransitionTo} - which edges exist.</li>
 *   <li>ROLE rule (this class): WHO may walk each edge.</li>
 *   <li>DATA rule: ownership/capacity checks (reopen window, assigned agent).</li>
 *   <li>AUDIT: every accepted transition writes a history row - in the SAME
 *       transaction as the state change, so state and audit can never diverge.</li>
 * </ol>
 *
 * Timestamp side effects: resolvedAt (set/cleared), closedAt (set/cleared),
 * resolution text (required on resolve, cleared on reopen).
 */
@Service
public class TicketWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(TicketWorkflowService.class);

    private final TicketRepository ticketRepository;
    private final TicketAuditService auditService;
    private final Duration reopenWindow;

    public TicketWorkflowService(TicketRepository ticketRepository,
                                 TicketAuditService auditService,
                                 @Value("${intellidesk.workflow.reopen-window-days:7}") long reopenWindowDays) {
        this.ticketRepository = ticketRepository;
        this.auditService = auditService;
        this.reopenWindow = Duration.ofDays(reopenWindowDays);
    }

    @Transactional
    public Ticket transition(Long ticketId, TicketStatus target, User actor, String reason) {
        return transition(ticketId, target, actor, reason, null);
    }

    /**
     * Core transition. {@code resolution} is required when {@code target} is
     * RESOLVED, stored on the ticket, and ignored otherwise.
     */
    @Transactional
    public Ticket transition(Long ticketId, TicketStatus target, User actor,
                             String reason, String resolution) {
        Ticket ticket = ticketRepository.findWithDetailsById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        TicketStatus old = ticket.getStatus();

        // 1. domain edge must exist
        if (!old.canTransitionTo(target)) {
            throw new InvalidTicketStateException(
                    "Illegal transition %s -> %s. Allowed: %s"
                            .formatted(old, target, old.allowedTransitions()));
        }

        // 2. role + data rules per edge
        authorize(old, target, ticket, actor);

        // 3. business payload rules
        if (target == TicketStatus.RESOLVED
                && (resolution == null || resolution.isBlank())) {
            throw new InvalidRequestException(
                    "A resolution note is required when resolving a ticket");
        }

        // 4. apply side effects
        applySideEffects(ticket, target, resolution);

        ticket.changeStatus(target);
        Ticket saved = ticketRepository.save(ticket);

        auditService.record(saved, old, target, actor,
                reason == null || reason.isBlank() ? defaultReason(old, target) : reason.trim());

        log.info("Ticket {} transitioned {} -> {} by {}",
                saved.getTicketNumber(), old, target,
                actor == null ? TicketAuditService.SYSTEM_ACTOR : actor.getEmail());
        return saved;
    }

    /** Assignment is its own audited transition (used by the assignment service). */
    @Transactional
    public Ticket assignToAgent(Ticket ticket, User agent, User actor, String reason) {
        TicketStatus old = ticket.getStatus();
        if (!old.canTransitionTo(TicketStatus.ASSIGNED)) {
            throw new InvalidTicketStateException(
                    "Cannot assign a ticket in status " + old);
        }
        ticket.setAssignedAgent(agent);
        ticket.changeStatus(TicketStatus.ASSIGNED);
        Ticket saved = ticketRepository.save(ticket);
        auditService.record(saved, old, TicketStatus.ASSIGNED, actor,
                (reason == null ? "Assigned to " : reason + " - assigned to ")
                        + agent.getFullName());
        return saved;
    }

    /** SLA escalation (system actor): marks + stamps the ticket, writes audit. */
    @Transactional
    public Ticket escalateForSla(Ticket ticket, String reason) {
        TicketStatus old = ticket.getStatus();
        ticket.changeStatus(TicketStatus.ESCALATED);
        ticket.setEscalatedAt(Instant.now());
        Ticket saved = ticketRepository.save(ticket);
        auditService.record(saved, old, TicketStatus.ESCALATED, null, reason);
        return saved;
    }

    // ---- rules -------------------------------------------------------------

    private void authorize(TicketStatus from, TicketStatus to, Ticket ticket, User actor) {
        if (actor == null) {
            throw new UnauthorizedAccessException("Transitions require an authenticated actor");
        }
        boolean isAdmin = actor.getRole() == Role.ADMIN;
        boolean isAssignedAgent = actor.getRole() == Role.AGENT
                && ticket.getAssignedAgent() != null
                && actor.getId().equals(ticket.getAssignedAgent().getId());
        boolean isReporter = actor.getRole() == Role.CUSTOMER
                && actor.getId().equals(ticket.getReporter().getId());

        boolean allowed = switch (to) {
            // work edges: the assigned agent or an admin; the reporter may pull
            // their own RESOLVED ticket back into active work ("not fixed yet")
            case IN_PROGRESS -> isAssignedAgent || isAdmin
                    || (from == TicketStatus.RESOLVED && isReporter);
            case WAITING_FOR_CUSTOMER -> isAssignedAgent || isAdmin;
            case RESOLVED -> isAssignedAgent || isAdmin;
            // assignment edges: admin decision (or system auto-assign, actor = admin caller)
            case ASSIGNED -> isAdmin;
            // confirmation/close: the owner customer or an admin
            case CLOSED -> isReporter || isAdmin;
            // reopen: owner (closed: only inside the window, checked below) or admin
            case OPEN -> isReporter || isAdmin;
            // escalation handled by the engine/admin elsewhere; here only via API admin
            case ESCALATED -> isAdmin;
        };
        if (!allowed) {
            throw new UnauthorizedAccessException(
                    "Role %s may not move a ticket from %s to %s"
                            .formatted(actor.getRole(), from, to));
        }

        // reopen window: a CUSTOMER may reopen a CLOSED ticket only within the window
        if (from == TicketStatus.CLOSED && to == TicketStatus.OPEN && !isAdmin) {
            Instant closedAt = ticket.getClosedAt();
            if (closedAt == null || Instant.now().isAfter(closedAt.plus(reopenWindow))) {
                throw new InvalidTicketStateException(
                        "Reopen window (%d days) has expired for this closed ticket"
                                .formatted(reopenWindow.toDays()));
            }
        }
    }

    private void applySideEffects(Ticket ticket, TicketStatus target, String resolution) {
        switch (target) {
            case RESOLVED -> {
                ticket.setResolvedAt(Instant.now());
                ticket.setResolution(resolution.trim());
            }
            case CLOSED -> ticket.setClosedAt(Instant.now());
            case IN_PROGRESS -> {
                // reopen paths: the ticket is no longer resolved/closed
                if (ticket.getResolvedAt() != null) {
                    ticket.setResolvedAt(null);
                }
                if (ticket.getClosedAt() != null) {
                    ticket.setClosedAt(null);
                }
                ticket.setResolution(null);
            }
            case OPEN -> { // closed -> open reopen
                ticket.setClosedAt(null);
                ticket.setResolvedAt(null);
                ticket.setResolution(null);
                // OPEN means "back in the assignment queue": drop the stale agent
                ticket.setAssignedAgent(null);
            }
            case ASSIGNED, WAITING_FOR_CUSTOMER, ESCALATED -> { /* no extra stamps here */ }
        }
    }

    private static String defaultReason(TicketStatus from, TicketStatus to) {
        return "Status changed from %s to %s".formatted(from, to);
    }
}
