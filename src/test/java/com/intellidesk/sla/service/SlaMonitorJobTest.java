package com.intellidesk.sla.service;

import com.intellidesk.category.entity.Category;
import com.intellidesk.sla.SlaProperties;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.ticket.service.TicketAuditService;
import com.intellidesk.ticket.service.TicketWorkflowService;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SLA watchdog: breach detection across the escalatable set and -
 * most importantly - IDEMPOTENCE (a ticket must not be escalated again on
 * every scheduler run).
 */
@ExtendWith(MockitoExtension.class)
class SlaMonitorJobTest {

    @Mock private TicketRepository ticketRepository;
    @Mock private TicketWorkflowService workflowService;
    @Mock private TicketAuditService auditService;

    private SlaMonitorJob monitor;

    private Ticket breached;
    private Ticket onTime;

    @BeforeEach
    void setUp() {
        monitor = new SlaMonitorJob(ticketRepository, workflowService, auditService,
                new SlaProperties(60_000, 60));

        breached = ticket(1L, TicketStatus.IN_PROGRESS, Instant.now().minus(1, ChronoUnit.HOURS));
        onTime = ticket(2L, TicketStatus.IN_PROGRESS, Instant.now().plus(5, ChronoUnit.HOURS));
    }

    private static Ticket ticket(long id, TicketStatus status, Instant deadline) {
        Ticket t = new Ticket("TKD-2026-00000" + id, "Payment failed", "Money gone, order cancelled",
                new User("c" + id + "@t.local", "$2a$10$hashhashhashhashhashhashhashhashhashhashhash",
                        "C", null, Role.CUSTOMER),
                new Category("PAYMENT", "Payment", "p"),
                TicketPriority.HIGH, null, deadline);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", Instant.now());
        ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
        t.changeStatus(status);
        return t;
    }

    @Test
    void escalatesBreachedTicketsOnly() {
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(breached))  // first page: the breach
                .thenReturn(List.of());         // second page: done
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(breached));
        when(auditService.alreadyRecorded(1L, TicketStatus.ESCALATED, SlaMonitorJob.ESCALATION_REASON))
                .thenReturn(false);
        when(workflowService.escalateForSla(breached, SlaMonitorJob.ESCALATION_REASON)).thenReturn(breached);

        int escalated = monitor.escalateBreachedTickets(Instant.now());

        assertThat(escalated).isEqualTo(1);
        verify(workflowService).escalateForSla(breached, SlaMonitorJob.ESCALATION_REASON);
        // the on-time ticket was never part of the breach query result
        verify(workflowService, never()).escalateForSla(onTime, SlaMonitorJob.ESCALATION_REASON);
    }

    @Test
    void isIdempotent_secondRunEscalatesNothing() {
        // 1st run: breach found and escalated
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(breached))
                .thenReturn(List.of());
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(breached));
        when(auditService.alreadyRecorded(1L, TicketStatus.ESCALATED, SlaMonitorJob.ESCALATION_REASON))
                .thenReturn(false);
        when(workflowService.escalateForSla(breached, SlaMonitorJob.ESCALATION_REASON)).thenReturn(breached);
        assertThat(monitor.escalateBreachedTickets(Instant.now())).isEqualTo(1);

        // 2nd run: the ticket is now ESCALATED -> not in the scan window at all,
        // and even if it were, the audit guard blocks the duplicate row.
        org.mockito.Mockito.reset(ticketRepository, workflowService, auditService);
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of());

        int secondRun = monitor.escalateBreachedTickets(Instant.now());

        assertThat(secondRun).isZero();
        verify(workflowService, never()).escalateForSla(any(), any());
    }

    @Test
    void auditDuplicateGuardBlocksRepeatedEscalation() {
        // simulate a future change that lets ESCALATED reappear in the scan:
        // the ticket is returned again, but the audit row already exists
        Ticket stillBreached = ticket(1L, TicketStatus.IN_PROGRESS, Instant.now().minus(2, ChronoUnit.HOURS));
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(stillBreached))
                .thenReturn(List.of());
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(stillBreached));
        when(auditService.alreadyRecorded(1L, TicketStatus.ESCALATED, SlaMonitorJob.ESCALATION_REASON))
                .thenReturn(true); // <- the guard

        int escalated = monitor.escalateBreachedTickets(Instant.now());

        assertThat(escalated).isZero();
        verify(workflowService, never()).escalateForSla(any(), any());
    }

    @Test
    void ticketTransitionedBySomeoneElseSinceTheScanIsSkipped() {
        Ticket becameResolved = ticket(1L, TicketStatus.RESOLVED, Instant.now().minus(1, ChronoUnit.HOURS));
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(breached))
                .thenReturn(List.of());
        // fresh read shows the ticket was RESOLVED between scan and processing
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(becameResolved));

        int escalated = monitor.escalateBreachedTickets(Instant.now());

        assertThat(escalated).isZero();
        verify(workflowService, never()).escalateForSla(any(), any());
    }

    @Test
    void scanCoversEveryEscalatableStatus() {
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of());

        monitor.escalateBreachedTickets(Instant.now());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TicketStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(ticketRepository).findByStatusInAndSlaDeadlineAtBefore(
                statuses.capture(), any(Instant.class), any(PageRequest.class));
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(TicketStatus.OPEN, TicketStatus.ASSIGNED,
                        TicketStatus.IN_PROGRESS, TicketStatus.WAITING_FOR_CUSTOMER)
                .doesNotContain(TicketStatus.ESCALATED, TicketStatus.RESOLVED, TicketStatus.CLOSED);
    }

    @Test
    void largeBacklogIsProcessedInPages() {
        List<Ticket> fullPage = new java.util.ArrayList<>();
        for (long i = 1; i <= 200; i++) {
            fullPage.add(ticket(i, TicketStatus.OPEN, Instant.now().minus(i, ChronoUnit.MINUTES)));
        }
        when(ticketRepository.findByStatusInAndSlaDeadlineAtBefore(anyList(), any(Instant.class), any(PageRequest.class)))
                .thenReturn(fullPage)   // page 0 (full)
                .thenReturn(List.of()); // page 1 (done)
        when(ticketRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.of(ticket(inv.getArgument(0, Long.class), TicketStatus.OPEN, Instant.now())));
        when(auditService.alreadyRecorded(anyLong(), any(TicketStatus.class), any())).thenReturn(false);
        when(workflowService.escalateForSla(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        int escalated = monitor.escalateBreachedTickets(Instant.now());

        assertThat(escalated).isEqualTo(200);
        verify(ticketRepository, times(2)).findByStatusInAndSlaDeadlineAtBefore(
                anyList(), any(Instant.class), any(PageRequest.class));
    }
}
