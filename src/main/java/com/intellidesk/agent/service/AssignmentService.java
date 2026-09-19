package com.intellidesk.agent.service;

import com.intellidesk.agent.repository.AgentSkillRepository;
import com.intellidesk.category.entity.Category;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.ticket.service.TicketWorkflowService;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Smart assignment: pick the best active agent for a ticket and hand it over
 * to the workflow service for the audited ASSIGNED transition.
 *
 * <p>Selection contract (deterministic):</p>
 * <ol>
 *   <li>Candidate pool = ACTIVE agents with a skill for the ticket's category.
 *       FALLBACK: if none exists (fresh system, rare category), the pool is all
 *       active agents - a ticket must never be stranded; the audit reason says
 *       "no skilled agent available".</li>
 *   <li>Each candidate is scored by {@link AgentScorer} (expertise / workload /
 *       availability / priority boost).</li>
 *   <li>Highest score wins; ties break by lower agent id (stable, explainable).</li>
 * </ol>
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final AgentSkillRepository agentSkillRepository;
    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final AgentScorer scorer;
    private final TicketWorkflowService workflowService;

    public AssignmentService(AgentSkillRepository agentSkillRepository,
                             UserRepository userRepository,
                             TicketRepository ticketRepository,
                             AgentScorer scorer,
                             TicketWorkflowService workflowService) {
        this.agentSkillRepository = agentSkillRepository;
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.scorer = scorer;
        this.workflowService = workflowService;
    }

    /**
     * Assigns the ticket to the best candidate.
     *
     * @param actor admin triggering manually, or null for auto-assignment
     * @return the assigned ticket (status ASSIGNED)
     */
    @Transactional
    public Ticket assign(Long ticketId, User actor) {
        Ticket ticket = ticketRepository.findWithDetailsById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        if (ticket.getStatus() != TicketStatus.OPEN) {
            throw new InvalidTicketStateException(
                    "Only OPEN tickets can be assigned (current status: %s)".formatted(ticket.getStatus()));
        }

        User winner = selectAgent(ticket);
        String reason = (actor == null ? "Auto-assigned" : "Assigned by admin")
                + " (best score for " + ticket.getCategory().getCode() + ")";
        Ticket assigned = workflowService.assignToAgent(ticket, winner, actor, reason);
        log.info("Ticket {} -> agent {} ({})", assigned.getTicketNumber(),
                winner.getEmail(), reason);
        return assigned;
    }

    /** Visible for tests: the full selection logic on a loaded ticket. */
    User selectAgent(Ticket ticket) {
        Category category = ticket.getCategory();
        List<User> skilled = agentSkillRepository.findByCategoryId(category.getId()).stream()
                .map(skill -> skill.getAgent())
                .filter(a -> a.isActive() && a.getRole() == Role.AGENT)
                .distinct()
                .toList();

        boolean fallback = skilled.isEmpty();
        List<User> pool = fallback
                ? userRepository.findByRoleAndActiveTrue(Role.AGENT)
                : skilled;

        if (pool.isEmpty()) {
            throw new InvalidTicketStateException(
                    "No active agents available for assignment");
        }

        boolean critical = ticket.getPriority() == TicketPriority.CRITICAL;
        boolean high = ticket.getPriority() == TicketPriority.HIGH;

        // min over (score desc, id asc) = highest score; ties -> lower agent id
        User best = pool.stream()
                .min(Comparator.comparingDouble((User agent) ->
                                scorer.score(factsOf(agent, category), critical, high).total())
                        .reversed()
                        .thenComparingLong(User::getId))
                .orElseThrow();

        log.debug("Assignment pool for ticket {}: {} candidates{}; winner={}",
                ticket.getTicketNumber(), pool.size(),
                fallback ? " (FALLBACK: no skills for " + category.getCode() + ")" : "",
                best.getEmail());
        return best;
    }

    private AgentScorer.CandidateFacts factsOf(User agent, Category category) {
        int proficiency = agentSkillRepository.findByAgentId(agent.getId()).stream()
                .filter(skill -> skill.getCategory().getId().equals(category.getId()))
                .findFirst()
                .map(com.intellidesk.agent.entity.AgentSkill::getProficiencyLevel)
                .orElse(0);
        long active = ticketRepository.countByAssignedAgentIdAndStatusIn(
                agent.getId(), TicketStatus.ACTIVE_WORK_STATUSES);
        return new AgentScorer.CandidateFacts(proficiency, Math.toIntExact(active));
    }

}
