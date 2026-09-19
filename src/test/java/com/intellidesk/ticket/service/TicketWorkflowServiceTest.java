package com.intellidesk.ticket.service;

import com.intellidesk.category.entity.Category;
import com.intellidesk.common.exception.InvalidRequestException;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.common.exception.UnauthorizedAccessException;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The state machine enforcement tests: valid edges apply side effects + audit,
 * invalid edges are refused with precise exceptions, and every edge checks the
 * role/ownership rule that guards it.
 */
@ExtendWith(MockitoExtension.class)
class TicketWorkflowServiceTest {

    @Mock private TicketRepository ticketRepository;
    @Mock private TicketAuditService auditService;

    private TicketWorkflowService workflowService;

    private User customer;
    private User otherCustomer;
    private User agent;
    private User admin;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        workflowService = new TicketWorkflowService(ticketRepository, auditService, 7);

        customer = user(1L, Role.CUSTOMER);
        otherCustomer = user(2L, Role.CUSTOMER);
        agent = user(3L, Role.AGENT);
        admin = user(4L, Role.ADMIN);

        ticket = new Ticket("TKD-2026-000001", "Payment deducted", "Money gone, order cancelled",
                customer, new Category("PAYMENT", "Payment", "p"),
                TicketPriority.HIGH, null, Instant.now().plus(8, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(ticket, "id", 100L);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        ReflectionTestUtils.setField(ticket, "updatedAt", Instant.now());
        ticket.setAssignedAgent(agent);

        org.mockito.Mockito.lenient().when(ticketRepository.findWithDetailsById(100L))
                .thenReturn(Optional.of(ticket));
        org.mockito.Mockito.lenient().when(ticketRepository.save(any(Ticket.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static User user(long id, Role role) {
        User u = new User("user" + id + "@test.local", "$2a$10$hashhashhashhashhashhashhashhashhashhashhash",
                "User " + id, null, role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    // ---- valid transitions ---------------------------------------------------

    @Test
    void assignedAgentStartsWorkAndAuditIsWritten() {
        ticket.changeStatus(TicketStatus.ASSIGNED);

        Ticket result = workflowService.transition(100L, TicketStatus.IN_PROGRESS, agent, "Starting");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(auditService).record(result, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, agent, "Starting");
    }

    @Test
    void resolveRequiresResolutionNoteAndStampsTimestamps() {
        ticket.changeStatus(TicketStatus.IN_PROGRESS);

        Ticket result = workflowService.transition(100L, TicketStatus.RESOLVED, agent, "Fixed",
                "Refunded the duplicate charge and fixed the billing race.");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(result.getResolvedAt()).isNotNull();
        assertThat(result.getResolution()).contains("Refunded");
        verify(auditService).record(any(), eq(TicketStatus.IN_PROGRESS),
                eq(TicketStatus.RESOLVED), eq(agent), eq("Fixed"));
    }

    @Test
    void resolveWithoutNoteIsRejected() {
        ticket.changeStatus(TicketStatus.IN_PROGRESS);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.RESOLVED, agent, null, "  "))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("resolution note");

        verify(ticketRepository, never()).save(any());
    }

    @Test
    void ownerClosesResolvedTicket() {
        ticket.changeStatus(TicketStatus.RESOLVED);
        ReflectionTestUtils.setField(ticket, "resolvedAt", Instant.now());

        Ticket result = workflowService.transition(100L, TicketStatus.CLOSED, customer, "Confirmed");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(result.getClosedAt()).isNotNull();
    }

    @Test
    void customerReopensResolvedTicketWhichClearsResolutionData() {
        ticket.changeStatus(TicketStatus.RESOLVED);
        ReflectionTestUtils.setField(ticket, "resolvedAt", Instant.now());
        ticket.setResolution("Old fix");

        Ticket result = workflowService.transition(100L, TicketStatus.IN_PROGRESS, customer, "Not satisfied");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(result.getResolvedAt()).isNull();
        assertThat(result.getResolution()).isNull();
    }

    @Test
    void customerReopensClosedTicketInsideWindow() {
        ticket.changeStatus(TicketStatus.CLOSED);
        ReflectionTestUtils.setField(ticket, "closedAt", Instant.now().minus(1, ChronoUnit.DAYS));

        Ticket result = workflowService.transition(100L, TicketStatus.OPEN, customer, "Problem returned");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(result.getClosedAt()).isNull();
    }

    @Test
    void customerReopenAfterWindowIsRejected() {
        ticket.changeStatus(TicketStatus.CLOSED);
        ReflectionTestUtils.setField(ticket, "closedAt", Instant.now().minus(8, ChronoUnit.DAYS));

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.OPEN, customer, null))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("Reopen window");
    }

    @Test
    void escalatedTicketReturnsToWorkViaAssignedAgent() {
        ticket.changeStatus(TicketStatus.ESCALATED);
        ReflectionTestUtils.setField(ticket, "escalatedAt", Instant.now());

        Ticket result = workflowService.transition(100L, TicketStatus.IN_PROGRESS, agent, "Taking over");

        assertThat(result.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        // escalatedAt is intentionally KEPT as the record of the last escalation
        assertThat(result.getEscalatedAt()).isNotNull();
    }

    // ---- invalid transitions -------------------------------------------------

    @Test
    void openCannotJumpStraightToResolved() {
        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.RESOLVED, admin, "skip", "note"))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("OPEN -> RESOLVED");

        verify(ticketRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void closedCannotBeResolvedAgain() {
        ticket.changeStatus(TicketStatus.CLOSED);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.RESOLVED, admin, null, "note"))
                .isInstanceOf(InvalidTicketStateException.class);
    }

    @Test
    void resolvedCannotBeEscalated() {
        ticket.changeStatus(TicketStatus.RESOLVED);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.ESCALATED, admin, null))
                .isInstanceOf(InvalidTicketStateException.class);
    }

    // ---- role rules ----------------------------------------------------------

    @Test
    void customerCannotStartWork() {
        ticket.changeStatus(TicketStatus.ASSIGNED);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.IN_PROGRESS, customer, null))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("CUSTOMER");
    }

    @Test
    void unassignedAgentCannotResolve() {
        ticket.changeStatus(TicketStatus.IN_PROGRESS);
        ticket.setAssignedAgent(otherCustomer);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.RESOLVED, agent, null, "note"))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void otherCustomerCannotCloseSomebodyElsesTicket() {
        ticket.changeStatus(TicketStatus.RESOLVED);

        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.CLOSED, otherCustomer, null))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void customerCannotAssign() {
        assertThatThrownBy(() -> workflowService.transition(100L, TicketStatus.ASSIGNED, customer, null))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void adminCanDoEverythingTheMatrixAllows() {
        ticket.changeStatus(TicketStatus.ASSIGNED);
        assertThat(workflowService.transition(100L, TicketStatus.IN_PROGRESS, admin, null).getStatus())
                .isEqualTo(TicketStatus.IN_PROGRESS);
        Ticket resolved = workflowService.transition(100L, TicketStatus.RESOLVED, admin, null, "Admin resolved");
        assertThat(resolved.getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(workflowService.transition(100L, TicketStatus.CLOSED, admin, null).getStatus())
                .isEqualTo(TicketStatus.CLOSED);
    }

    // ---- assignment + escalation helpers --------------------------------------

    @Test
    void assignToAgentSetsAgentStatusAndAudit() {
        Ticket result = workflowService.assignToAgent(ticket, agent, null, "Auto-assigned");

        assertThat(result.getAssignedAgent()).isEqualTo(agent);
        assertThat(result.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        verify(auditService).record(any(), eq(TicketStatus.OPEN), eq(TicketStatus.ASSIGNED),
                isNull(), any());
    }

    @Test
    void assignToAgentRejectsResolvedTicket() {
        ticket.changeStatus(TicketStatus.RESOLVED);

        assertThatThrownBy(() -> workflowService.assignToAgent(ticket, agent, null, null))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void escalateForSlaStampsEscalatedAtAndWritesSystemAudit() {
        ticket.changeStatus(TicketStatus.IN_PROGRESS);

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        Ticket result = workflowService.escalateForSla(ticket, TicketWorkflowServiceTestConstants.REASON);

        verify(ticketRepository).save(captor.capture());
        assertThat(result.getStatus()).isEqualTo(TicketStatus.ESCALATED);
        assertThat(result.getEscalatedAt()).isNotNull();
        verify(auditService).record(any(), eq(TicketStatus.IN_PROGRESS),
                eq(TicketStatus.ESCALATED), isNull(),
                eq(TicketWorkflowServiceTestConstants.REASON));
    }

    /** Constants shared with the monitor test to keep the reason byte-identical. */
    static final class TicketWorkflowServiceTestConstants {
        static final String REASON = "SLA breached - auto-escalated by monitor";
    }
}
