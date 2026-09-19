package com.intellidesk.ticket.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state machine is pure domain logic - no Spring context needed.
 * These exact rules are enforced by the workflow service in Phase 4.
 */
class TicketStatusTransitionTest {

    @Test
    void happyPathTransitionsAreAllowed() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.ASSIGNED)).isTrue();
        assertThat(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.IN_PROGRESS)).isTrue();
        assertThat(TicketStatus.IN_PROGRESS.canTransitionTo(TicketStatus.WAITING_FOR_CUSTOMER)).isTrue();
        assertThat(TicketStatus.WAITING_FOR_CUSTOMER.canTransitionTo(TicketStatus.IN_PROGRESS)).isTrue();
        assertThat(TicketStatus.IN_PROGRESS.canTransitionTo(TicketStatus.RESOLVED)).isTrue();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.CLOSED)).isTrue();
    }

    @Test
    void escalationPathsAreAllowedFromEveryActiveState() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.ESCALATED)).isTrue();
        assertThat(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.ESCALATED)).isTrue();
        assertThat(TicketStatus.IN_PROGRESS.canTransitionTo(TicketStatus.ESCALATED)).isTrue();
        assertThat(TicketStatus.WAITING_FOR_CUSTOMER.canTransitionTo(TicketStatus.ESCALATED)).isTrue();
        assertThat(TicketStatus.ESCALATED.canTransitionTo(TicketStatus.IN_PROGRESS)).isTrue();
        assertThat(TicketStatus.ESCALATED.canTransitionTo(TicketStatus.ASSIGNED)).isTrue();
    }

    @Test
    void reopenPathsAreAllowed() {
        // Customer reopens a resolved ticket -> straight back to work.
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.IN_PROGRESS)).isTrue();
        // Closed tickets can be reopened to OPEN within the reopen window.
        assertThat(TicketStatus.CLOSED.canTransitionTo(TicketStatus.OPEN)).isTrue();
    }

    @Test
    void arbitraryTransitionsAreRejected() {
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.RESOLVED)).isFalse();
        assertThat(TicketStatus.OPEN.canTransitionTo(TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.CLOSED.canTransitionTo(TicketStatus.RESOLVED)).isFalse();
        assertThat(TicketStatus.RESOLVED.canTransitionTo(TicketStatus.ESCALATED)).isFalse();
        assertThat(TicketStatus.ASSIGNED.canTransitionTo(TicketStatus.CLOSED)).isFalse();
        assertThat(TicketStatus.ESCALATED.canTransitionTo(TicketStatus.CLOSED)).isFalse();
    }

    @Test
    void closedIsTheOnlyTerminalState() {
        assertThat(TicketStatus.CLOSED.isTerminal()).isTrue();
        for (TicketStatus status : TicketStatus.values()) {
            if (status != TicketStatus.CLOSED) {
                assertThat(status.isTerminal()).isFalse();
            }
        }
    }

    @Test
    void allowedTransitionsAreUnmodifiableViews() {
        Set<TicketStatus> transitions = TicketStatus.OPEN.allowedTransitions();
        assertThat(transitions).containsExactlyInAnyOrder(TicketStatus.ASSIGNED, TicketStatus.ESCALATED);
    }
}
