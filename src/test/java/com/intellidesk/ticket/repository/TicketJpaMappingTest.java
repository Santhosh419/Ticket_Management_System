package com.intellidesk.ticket.repository;

import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.sla.entity.SlaPolicy;
import com.intellidesk.sla.repository.SlaPolicyRepository;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.entity.TicketComment;
import com.intellidesk.ticket.entity.TicketHistory;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence-slice tests: verifies the real Hibernate mappings against a
 * database - entity graph shape, enum round-trips, constraints and the
 * append-only history pattern.
 */
@DataJpaTest
class TicketJpaMappingTest {

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SlaPolicyRepository slaPolicyRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketCommentRepository commentRepository;
    @Autowired private TicketHistoryRepository historyRepository;

    private User customer;
    private User agent;
    private Category payment;
    private SlaPolicy highPolicy;

    @BeforeEach
    void seedReferenceData() {
        customer = userRepository.save(
                new User("customer@test.local", "$2a$10$hashhashhashhashhashhashhashhashhashhashhashhash",
                        "Cara Customer", null, Role.CUSTOMER));
        agent = userRepository.save(
                new User("agent@test.local", "$2a$10$hashhashhashhashhashhashhashhashhashhashhashhash",
                        "Andy Agent", null, Role.AGENT));
        payment = categoryRepository.save(new Category("PAYMENT", "Payment", "Payment issues"));
        highPolicy = slaPolicyRepository.save(new SlaPolicy(TicketPriority.HIGH, 8));
    }

    @Test
    void ticketGraphPersistsAndReloadsWithAllRelations() {
        Ticket ticket = new Ticket(
                "TKD-2026-000001",
                "Payment deducted but order failed",
                "Rs.500 was deducted but the order was cancelled.",
                customer, payment, TicketPriority.HIGH, highPolicy,
                Instant.now().plus(8, ChronoUnit.HOURS));

        Ticket saved = ticketRepository.saveAndFlush(ticket);

        // Append-only audit entry
        historyRepository.save(new TicketHistory(
                saved, null, TicketStatus.OPEN, null, Instant.now(), "Ticket created"));

        commentRepository.save(new TicketComment(saved, customer, "Please refund my money."));
        commentRepository.save(new TicketComment(saved, agent, "Looking into it now."));

        // Detach everything, then re-read through the repository fetch graph
        userRepository.flush();
        Ticket reloaded = ticketRepository.findWithDetailsById(saved.getId()).orElseThrow();

        assertThat(reloaded.getTicketNumber()).isEqualTo("TKD-2026-000001");
        assertThat(reloaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(reloaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(reloaded.getReporter().getEmail()).isEqualTo("customer@test.local");
        assertThat(reloaded.getCategory().getCode()).isEqualTo("PAYMENT");
        assertThat(reloaded.getSlaPolicy().getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(reloaded.getSlaDeadlineAt()).isAfter(Instant.now());
        assertThat(reloaded.getAssignedAgent()).isNull();

        assertThat(commentRepository.findByTicketIdOrderByCreatedAtAsc(saved.getId()))
                .hasSize(2)
                .extracting(TicketComment::getMessage)
                .containsExactly("Please refund my money.", "Looking into it now.");

        assertThat(historyRepository.findByTicketIdOrderByChangedAtAsc(saved.getId()))
                .hasSize(1)
                .first()
                .satisfies(h -> {
                    assertThat(h.getOldStatus()).isNull();
                    assertThat(h.getNewStatus()).isEqualTo(TicketStatus.OPEN);
                    assertThat(h.getChangedBy()).isNull();
                });
    }

    @Test
    void duplicateTicketNumberIsRejected() {
        ticketRepository.saveAndFlush(new Ticket(
                "TKD-2026-000001", "First", "d", customer, payment,
                TicketPriority.LOW, slaPolicyRepository.save(new SlaPolicy(TicketPriority.LOW, 48)),
                Instant.now()));

        Ticket duplicate = new Ticket(
                "TKD-2026-000001", "Second", "d", customer, payment,
                TicketPriority.LOW, highPolicy, Instant.now());

        assertThatThrownBy(() -> ticketRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateEmailIsRejected() {
        User duplicate = new User("customer@test.local", "$2a$10$anotherhashhashhashhashhashhashhashhashhash",
                "Imposter", null, Role.CUSTOMER);

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optimisticLockingVersionIncrements() {
        Ticket saved = ticketRepository.saveAndFlush(new Ticket(
                "TKD-2026-000002", "Order failed", "d", customer, payment,
                TicketPriority.MEDIUM, slaPolicyRepository.save(new SlaPolicy(TicketPriority.MEDIUM, 24)),
                Instant.now()));

        long v1 = saved.getVersion();
        saved.changeStatus(TicketStatus.ASSIGNED);
        ticketRepository.saveAndFlush(saved);

        Ticket reloaded = ticketRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(v1 + 1);
    }
}
