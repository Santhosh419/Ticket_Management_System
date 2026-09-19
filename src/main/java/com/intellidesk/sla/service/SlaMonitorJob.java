package com.intellidesk.sla.service;

import com.intellidesk.sla.SlaProperties;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.ticket.service.TicketWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduled SLA watchdog.
 *
 * <p>Idempotency - the interesting property. Three independent guards ensure
 * a ticket is never escalated twice by the same breach:</p>
 * <ol>
 *   <li>The scan query only selects tickets whose status is in
 *       ESCALATABLE_STATUSES - ESCALATED is not among them, so an escalated
 *       ticket simply never comes up again.</li>
 *   <li>{@code escalateForSla} is only reachable for tickets that are still
 *       escalatable when the row is processed (re-checked inside the
 *       transaction, protecting against races with concurrent transitions).</li>
 *   <li>The audit service refuses to write a duplicate (status=ESCALATED,
 *       reason="SLA breached ...") row - even if a future change reintroduces
 *       ESCALATED into the scan window (e.g. to escalate re-breached tickets),
 *       the SAME breach can still not be recorded twice.</li>
 * </ol>
 *
 * <p>Batching: the scan pages through breaches (fixed page size, ordered by
 * deadline) so a large backlog is drained over multiple runs instead of one
 * unbounded query - predictable DB load.</p>
 */
@Component
public class SlaMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(SlaMonitorJob.class);

    static final String ESCALATION_REASON = "SLA breached - auto-escalated by monitor";
    private static final int SCAN_PAGE_SIZE = 200;

    private final TicketRepository ticketRepository;
    private final TicketWorkflowService workflowService;
    private final com.intellidesk.ticket.service.TicketAuditService auditService;
    private final SlaProperties slaProperties;

    public SlaMonitorJob(TicketRepository ticketRepository,
                         TicketWorkflowService workflowService,
                         com.intellidesk.ticket.service.TicketAuditService auditService,
                         SlaProperties slaProperties) {
        this.ticketRepository = ticketRepository;
        this.workflowService = workflowService;
        this.auditService = auditService;
        this.slaProperties = slaProperties;
    }

    /**
     * The scheduled entry point. fixedDelay (not rate) guarantees no overlap
     * between runs even if a run is slow.
     */
    @Scheduled(fixedDelayString = "${intellidesk.sla.scan-interval-ms:60000}")
    public void scanAndEscalate() {
        int escalated = escalateBreachedTickets(Instant.now());
        if (escalated > 0) {
            log.warn("SLA monitor escalated {} ticket(s)", escalated);
        } else {
            log.debug("SLA monitor run finished: no breaches");
        }
    }

    /** Single scan pass; returns the number of tickets escalated. Testable without waiting. */
    @Transactional
    public int escalateBreachedTickets(Instant now) {
        int total = 0;
        int page = 0;
        while (true) {
            List<Ticket> breaches = ticketRepository.findByStatusInAndSlaDeadlineAtBefore(
                    TicketStatus.ESCALATABLE_STATUSES.stream().toList(),
                    now,
                    PageRequest.of(page, SCAN_PAGE_SIZE, Sort.by("slaDeadlineAt").ascending()));
            if (breaches.isEmpty()) {
                break;
            }
            for (Ticket ticket : breaches) {
                if (escalateOne(ticket)) {
                    total++;
                }
            }
            if (breaches.size() < SCAN_PAGE_SIZE) {
                break;
            }
            page++;
        }
        return total;
    }

    private boolean escalateOne(Ticket ticket) {
        // Re-check status inside the transaction: another actor may have
        // transitioned the ticket since the page was read.
        Ticket fresh = ticketRepository.findById(ticket.getId()).orElse(null);
        if (fresh == null || !TicketStatus.ESCALATABLE_STATUSES.contains(fresh.getStatus())) {
            return false;
        }
        // Duplicate guard: never record the same breach twice.
        if (auditService.alreadyRecorded(fresh.getId(), TicketStatus.ESCALATED, ESCALATION_REASON)) {
            log.debug("Skipping ticket {}: already escalated for this breach", fresh.getTicketNumber());
            return false;
        }
        workflowService.escalateForSla(fresh, ESCALATION_REASON);
        return true;
    }

    long scanIntervalMs() {
        return slaProperties.scanIntervalMs();
    }
}
