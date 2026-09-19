package com.intellidesk.sla.service;

import com.intellidesk.category.entity.Category;
import com.intellidesk.sla.SlaProperties;
import com.intellidesk.sla.service.SlaService.SlaStatus;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SLA mathematics: deadline computation per priority and the
 * on-track / at-risk / breached evaluation boundaries.
 */
class SlaServiceTest {

    private final SlaService slaService = new SlaService(new SlaProperties(60_000, 60));

    private Ticket ticketWithDeadline(Instant deadline) {
        Ticket ticket = new Ticket("TKD-2026-000042", "Payment failed", "Money gone, order cancelled",
                new User("c@t.local", "$2a$10$hashhashhashhashhashhashhashhashhashhashhash", "C", null, Role.CUSTOMER),
                new Category("PAYMENT", "Payment", "p"),
                TicketPriority.HIGH, null, deadline);
        ReflectionTestUtils.setField(ticket, "id", 1L);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        ReflectionTestUtils.setField(ticket, "updatedAt", Instant.now());
        return ticket;
    }

    @Test
    void deadlineCalculationMatchesSeededPolicies() {
        Instant now = Instant.parse("2026-09-19T10:00:00Z");

        assertThat(slaService.computeDeadline(2, now)).isEqualTo(now.plus(2, ChronoUnit.HOURS));   // CRITICAL
        assertThat(slaService.computeDeadline(8, now)).isEqualTo(now.plus(8, ChronoUnit.HOURS));   // HIGH
        assertThat(slaService.computeDeadline(24, now)).isEqualTo(now.plus(24, ChronoUnit.HOURS)); // MEDIUM
        assertThat(slaService.computeDeadline(48, now)).isEqualTo(now.plus(48, ChronoUnit.HOURS)); // LOW
    }

    @Test
    void onTrackFarFromDeadline() {
        Ticket ticket = ticketWithDeadline(Instant.now().plus(10, ChronoUnit.HOURS));

        var evaluation = slaService.evaluate(ticket, Instant.now());

        assertThat(evaluation.status()).isEqualTo(SlaStatus.ON_TRACK);
        assertThat(evaluation.minutesToDeadline()).isPositive();
    }

    @Test
    void atRiskInsideTheConfiguredWindow() {
        // 30 minutes left, at-risk window is 60 minutes
        Instant deadline = Instant.now().plus(30, ChronoUnit.MINUTES);
        Ticket ticket = ticketWithDeadline(deadline);

        var evaluation = slaService.evaluate(ticket, Instant.now());

        assertThat(evaluation.status()).isEqualTo(SlaStatus.AT_RISK);
        assertThat(evaluation.minutesToDeadline()).isBetween(29L, 31L);
    }

    @Test
    void breachedExactlyAtAndAfterDeadline() {
        Instant deadline = Instant.now().minusSeconds(1);
        Ticket ticket = ticketWithDeadline(deadline);

        assertThat(slaService.evaluate(ticket, Instant.now()).status()).isEqualTo(SlaStatus.BREACHED);
        // boundary: AT the deadline it is already breached (strictly-on-time counts as kept)
        assertThat(slaService.evaluate(ticketWithDeadline(Instant.now()), Instant.now()).status())
                .isEqualTo(SlaStatus.BREACHED);
    }

    @Test
    void minutesToDeadlineIsNegativeWhenBreached() {
        Ticket ticket = ticketWithDeadline(Instant.now().minus(2, ChronoUnit.HOURS));

        var evaluation = slaService.evaluate(ticket, Instant.now());

        assertThat(evaluation.status()).isEqualTo(SlaStatus.BREACHED);
        assertThat(evaluation.minutesToDeadline()).isCloseTo(-120L, within(1L));
    }
}
