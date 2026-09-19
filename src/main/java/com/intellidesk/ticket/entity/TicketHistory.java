package com.intellidesk.ticket.entity;

import com.intellidesk.common.domain.BaseEntity;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Append-only audit record for every meaningful ticket state change.
 *
 * <p>Rows are never updated or deleted (append-only audit trail). For system
 * events such as SLA escalations the actor may be null and the reason explains
 * what happened, e.g. "SLA breached - auto escalated by scheduler".</p>
 */
@Entity
@Table(
        name = "ticket_history",
        indexes = {
                @Index(name = "idx_history_ticket", columnList = "ticket_id"),
                @Index(name = "idx_history_changed_at", columnList = "changed_at")
        }
)
public class TicketHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_history_ticket"))
    private Ticket ticket;

    /** Null for the initial creation event (nothing -> OPEN). */
    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private TicketStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private TicketStatus newStatus;

    /** Null when the change was made by the system (scheduler/seeder). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by_id",
                foreignKey = @ForeignKey(name = "fk_history_changed_by"))
    private User changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(length = 255)
    private String reason;

    protected TicketHistory() {
        // Required by JPA
    }

    public TicketHistory(Ticket ticket, TicketStatus oldStatus, TicketStatus newStatus,
                         User changedBy, Instant changedAt, String reason) {
        this.ticket = ticket;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
        this.reason = reason;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public TicketStatus getOldStatus() {
        return oldStatus;
    }

    public TicketStatus getNewStatus() {
        return newStatus;
    }

    public User getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getReason() {
        return reason;
    }
}
