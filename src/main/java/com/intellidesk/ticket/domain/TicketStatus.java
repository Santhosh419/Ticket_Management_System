package com.intellidesk.ticket.domain;

import java.util.Map;
import java.util.Set;

/**
 * Ticket lifecycle states and the ONLY legal transitions between them.
 *
 * <pre>
 * OPEN -------------------&gt; ASSIGNED
 * ASSIGNED ---------------&gt; IN_PROGRESS
 * IN_PROGRESS ------------&gt; WAITING_FOR_CUSTOMER
 * WAITING_FOR_CUSTOMER ---&gt; IN_PROGRESS
 * IN_PROGRESS ------------&gt; RESOLVED
 * WAITING_FOR_CUSTOMER ---&gt; RESOLVED
 * RESOLVED ---------------&gt; CLOSED            (customer confirms the fix)
 * RESOLVED ---------------&gt; IN_PROGRESS     (reopen: customer not satisfied)
 * CLOSED -----------------&gt; OPEN            (reopen inside the reopen window)
 * OPEN / ASSIGNED / IN_PROGRESS / WAITING_FOR_CUSTOMER ---&gt; ESCALATED (SLA engine)
 * ESCALATED --------------&gt; IN_PROGRESS / ASSIGNED  (senior agent picks it up)
 * </pre>
 *
 * <p>Keeping the transition map next to the enum makes the state machine a pure,
 * unit-testable domain concept: services cannot invent a transition that the
 * domain model does not allow.</p>
 */
public enum TicketStatus {

    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    WAITING_FOR_CUSTOMER,
    RESOLVED,
    CLOSED,
    ESCALATED;

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED_TRANSITIONS = Map.of(
            OPEN, Set.of(ASSIGNED, ESCALATED),
            ASSIGNED, Set.of(IN_PROGRESS, ESCALATED),
            IN_PROGRESS, Set.of(WAITING_FOR_CUSTOMER, RESOLVED, ESCALATED),
            WAITING_FOR_CUSTOMER, Set.of(IN_PROGRESS, RESOLVED, ESCALATED),
            RESOLVED, Set.of(CLOSED, IN_PROGRESS),
            CLOSED, Set.of(OPEN),
            ESCALATED, Set.of(IN_PROGRESS, ASSIGNED)
    );

    /** True if moving from this status to {@code target} is legal. */
    public boolean canTransitionTo(TicketStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Read-only view of the legal target statuses. */
    public Set<TicketStatus> allowedTransitions() {
        return Set.copyOf(ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()));
    }

    /** Closed tickets may only be re-opened, nothing else. */
    public boolean isTerminal() {
        return this == CLOSED;
    }
}
