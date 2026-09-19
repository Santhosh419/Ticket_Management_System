package com.intellidesk.sla.repository;

import com.intellidesk.sla.entity.SlaPolicy;
import com.intellidesk.ticket.domain.TicketPriority;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, Long> {

    Optional<SlaPolicy> findByPriorityAndActiveTrue(TicketPriority priority);
}
