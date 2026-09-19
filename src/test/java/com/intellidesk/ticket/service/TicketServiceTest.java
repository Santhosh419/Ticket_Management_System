package com.intellidesk.ticket.service;

import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.common.exception.InvalidRequestException;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.common.exception.UnauthorizedAccessException;
import com.intellidesk.sla.entity.SlaPolicy;
import com.intellidesk.sla.repository.SlaPolicyRepository;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.dto.CreateTicketRequest;
import com.intellidesk.ticket.dto.TicketResponse;
import com.intellidesk.ticket.dto.UpdateTicketRequest;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.mapper.TicketMapper;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business-rule unit tests for the ticket service: SLA computation on
 * create, the full access matrix on read, and the update authorization
 * rules. No Spring context, no database - Mockito + real mapper.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock private TicketRepository ticketRepository;
@Mock private com.intellidesk.ticket.service.TicketAuditService auditService;
    @Mock private CategoryRepository categoryRepository;
    @Mock private SlaPolicyRepository slaPolicyRepository;
    @Mock private com.intellidesk.agent.service.AssignmentService assignmentService;

    private final TicketMapper mapper = new TicketMapper(
            new com.intellidesk.sla.service.SlaService(
                    new com.intellidesk.sla.SlaProperties(60000, 60)));
    private final TicketNumberGenerator numberGenerator = new TicketNumberGenerator();

    private TicketService ticketService;

    private User customer;
    private User otherCustomer;
    private User agent;
    private User admin;
    private Category payment;
    private Category other;
    private SlaPolicy highPolicy;
    private SlaPolicy criticalPolicy;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(
                ticketRepository, categoryRepository, slaPolicyRepository,
                numberGenerator, mapper, assignmentService,
                // auto-assignment OFF in these tests; assignment has its own suite
                new com.intellidesk.agent.AssignmentProperties(false, 0.5, 0.3, 0.2, 10),
                auditService);

        customer = user(1L, "customer@test.local", Role.CUSTOMER);
        otherCustomer = user(2L, "other@test.local", Role.CUSTOMER);
        agent = user(3L, "agent@test.local", Role.AGENT);
        admin = user(4L, "admin@test.local", Role.ADMIN);
        payment = new Category("PAYMENT", "Payment", "Payment issues");
        ReflectionTestUtils.setField(payment, "id", 10L);
        other = new Category("OTHER", "Other", "Anything else");
        ReflectionTestUtils.setField(other, "id", 11L);
        highPolicy = new SlaPolicy(TicketPriority.HIGH, 8);
        criticalPolicy = new SlaPolicy(TicketPriority.CRITICAL, 2);
    }

    private static User user(long id, String email, Role role) {
        User u = new User(email, "$2a$10$hashhashhashhashhashhashhashhashhashhashhashhash", "User " + id, null, role);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private Ticket persistedTicket(long id, User reporter, TicketStatus status) {
        Ticket t = new Ticket("TKD-2026-000100", "Payment deducted", "Money gone, order cancelled",
                reporter, payment, TicketPriority.HIGH, highPolicy,
                Instant.now().plus(8, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", Instant.now());
        ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
        t.changeStatus(status);
        return t;
    }

    // ---- create ------------------------------------------------------------

    @Test
    void createComputesSlaDeadlineAssignsCustomerAndFinalNumber() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(payment));
        when(slaPolicyRepository.findByPriorityAndActiveTrue(TicketPriority.HIGH))
                .thenReturn(Optional.of(highPolicy));
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket t = invocation.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 42L);
            ReflectionTestUtils.setField(t, "createdAt", Instant.now());
            ReflectionTestUtils.setField(t, "updatedAt", Instant.now());
            return t;
        });

        TicketResponse response = ticketService.create(
                new CreateTicketRequest("Payment deducted twice", "I was charged two times for one order.",
                        10L, TicketPriority.HIGH), customer);

        // two flushes BY DESIGN: INSERT with placeholder number, then UPDATE with the
        // final id-derived number - both inside the same transaction
        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository, times(2)).saveAndFlush(captor.capture());

        Ticket saved = captor.getValue();
        assertThat(saved.getTicketNumber()).isEqualTo("TKD-2026-000042");
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(saved.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(saved.getSlaDeadlineAt()).isCloseTo(Instant.now().plus(8, ChronoUnit.HOURS), within(5, ChronoUnit.SECONDS));
        assertThat(response.ticketNumber()).isEqualTo("TKD-2026-000042");
        assertThat(response.category().code()).isEqualTo("PAYMENT");
        assertThat(response.customer().id()).isEqualTo(1L);
        assertThat(response.assignedAgent()).isNull();
    }

    @Test
    void createWithUnknownCategoryIsRejectedAsBadRequest() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.create(
                new CreateTicketRequest("Some title here", "Some long enough description.", 999L, TicketPriority.LOW),
                customer))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("999");

        verify(ticketRepository, never()).saveAndFlush(any(Ticket.class));
    }

    @Test
    void createWithMissingSlaPolicyIsAServerMisconfiguration() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(payment));
        when(slaPolicyRepository.findByPriorityAndActiveTrue(TicketPriority.HIGH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.create(
                new CreateTicketRequest("Some title here", "Some long enough description.", 10L, TicketPriority.HIGH),
                customer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SLA policy");
    }

    // ---- getById access matrix ----------------------------------------------

    @Test
    void ownerCustomerCanReadOwnTicket() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThat(ticketService.getById(100L, customer).id()).isEqualTo(100L);
    }

    @Test
    void otherCustomerIsForbidden() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.getById(100L, otherCustomer))
                .isInstanceOf(UnauthorizedAccessException.class);
    }

    @Test
    void unassignedAgentIsForbiddenButAssignedAgentCanRead() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.ASSIGNED);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.getById(100L, agent))
                .isInstanceOf(UnauthorizedAccessException.class);

        ticket.setAssignedAgent(agent);
        assertThat(ticketService.getById(100L, agent).assignedAgent().id()).isEqualTo(3L);
    }

    @Test
    void adminCanReadAnyTicket() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThat(ticketService.getById(100L, admin).id()).isEqualTo(100L);
    }

    @Test
    void missingTicketIs404NotFound() {
        when(ticketRepository.findWithDetailsById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getById(999999L, admin))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- update ------------------------------------------------------------

    @Test
    void customerCanEditOwnOpenTicket() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketResponse response = ticketService.update(100L,
                new UpdateTicketRequest("Updated title that is long enough", null, null, null), customer);

        assertThat(response.title()).isEqualTo("Updated title that is long enough");
        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.HIGH); // unchanged
    }

    @Test
    void customerCannotChangePriorityOrCategory() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest(null, null, 11L, TicketPriority.CRITICAL), customer))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("admins");
    }

    @Test
    void customerCannotEditOnceWorkHasStarted() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.ASSIGNED);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest("New title long enough", null, null, null), customer))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("OPEN");
    }

    @Test
    void customerCannotEditSomebodyElsesTicket() {
        Ticket ticket = persistedTicket(100L, otherCustomer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest("New title long enough", null, null, null), customer))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("your own");
    }

    @Test
    void agentFieldEditsAreRejected() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.ASSIGNED);
        ticket.setAssignedAgent(agent);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest("New title long enough", null, null, null), agent))
                .isInstanceOf(UnauthorizedAccessException.class)
                .hasMessageContaining("workflow");
    }

    @Test
    void adminCanChangeCategoryAndPriorityWithSlaRecompute() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        Instant oldDeadline = ticket.getSlaDeadlineAt();
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(other));
        when(slaPolicyRepository.findByPriorityAndActiveTrue(TicketPriority.CRITICAL))
                .thenReturn(Optional.of(criticalPolicy));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> inv.getArgument(0));

        TicketResponse response = ticketService.update(100L,
                new UpdateTicketRequest(null, null, 11L, TicketPriority.CRITICAL), admin);

        assertThat(response.priority()).isEqualTo(TicketPriority.CRITICAL);
        assertThat(response.category().code()).isEqualTo("OTHER");
        assertThat(ticket.getSlaPolicy()).isSameAs(criticalPolicy);
        assertThat(ticket.getSlaDeadlineAt())
                .isCloseTo(Instant.now().plus(2, ChronoUnit.HOURS), within(5, ChronoUnit.SECONDS))
                .isBefore(oldDeadline);
    }

    @Test
    void emptyUpdateIsRejected() {
        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest(null, null, null, null), admin))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("At least one field");
    }

    @Test
    void adminUpdateOfUnknownCategoryIsRejected() {
        Ticket ticket = persistedTicket(100L, customer, TicketStatus.OPEN);
        when(ticketRepository.findWithDetailsById(100L)).thenReturn(Optional.of(ticket));
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.update(100L,
                new UpdateTicketRequest(null, null, 999L, null), admin))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Category");
    }
}
