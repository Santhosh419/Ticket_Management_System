package com.intellidesk.sla.entity;

import com.intellidesk.common.domain.BaseEntity;
import com.intellidesk.ticket.domain.TicketPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * SLA resolution target for a ticket priority, e.g. CRITICAL = 2 hours.
 *
 * <p>SLA durations are configuration data stored in this table - never hard-coded.
 * When a ticket is created, its deadline is computed once from the policy that was
 * active at that moment and frozen into {@code tickets.sla_deadline_at}. Later
 * policy edits therefore never rewrite the deadlines of tickets that already exist
 * (which would silently move the goal posts - a classic auditability bug).</p>
 */
@Entity
@Table(
        name = "sla_policies",
        uniqueConstraints = @UniqueConstraint(name = "uk_sla_policies_priority", columnNames = "priority")
)
public class SlaPolicy extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketPriority priority;

    @Column(name = "resolution_hours", nullable = false)
    private int resolutionHours;

    @Column(nullable = false)
    private boolean active = true;

    protected SlaPolicy() {
        // Required by JPA
    }

    public SlaPolicy(TicketPriority priority, int resolutionHours) {
        this.priority = priority;
        this.resolutionHours = resolutionHours;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public void setPriority(TicketPriority priority) {
        this.priority = priority;
    }

    public int getResolutionHours() {
        return resolutionHours;
    }

    public void setResolutionHours(int resolutionHours) {
        this.resolutionHours = resolutionHours;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
