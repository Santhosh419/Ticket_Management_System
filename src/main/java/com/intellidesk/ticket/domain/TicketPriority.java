package com.intellidesk.ticket.domain;

/**
 * Ticket urgency. Numeric levels exist only for sorting/scoring in the
 * assignment algorithm; SLA durations live in {@code sla_policies},
 * never as magic numbers in code.
 */
public enum TicketPriority {

    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    TicketPriority(int level) {
        this.level = level;
    }

    /** Higher number = more urgent. Used by sorting and scoring algorithms. */
    public int getLevel() {
        return level;
    }
}
