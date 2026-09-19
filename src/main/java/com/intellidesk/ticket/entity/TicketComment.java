package com.intellidesk.ticket.entity;

import com.intellidesk.common.domain.BaseEntity;
import com.intellidesk.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A conversation entry on a ticket. Author and ticket are LAZY associations;
 * comments are always read through repository queries with fetch graphs,
 * never by navigating unbounded object chains.
 */
@Entity
@Table(
        name = "ticket_comments",
        indexes = @Index(name = "idx_ticket_comments_ticket", columnList = "ticket_id")
)
public class TicketComment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_comments_ticket"))
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_comments_author"))
    private User author;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    protected TicketComment() {
        // Required by JPA
    }

    public TicketComment(Ticket ticket, User author, String message) {
        this.ticket = ticket;
        this.author = author;
        this.message = message;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public User getAuthor() {
        return author;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
