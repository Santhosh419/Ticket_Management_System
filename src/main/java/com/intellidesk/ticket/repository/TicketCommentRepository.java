package com.intellidesk.ticket.repository;

import com.intellidesk.ticket.entity.TicketComment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {

    /** Author fetched eagerly per query: one join, no N+1 when mapping comments. */
    @EntityGraph(attributePaths = {"author"})
    List<TicketComment> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
