package com.intellidesk.agent.service;

import com.intellidesk.agent.dto.AgentSkillRequest;
import com.intellidesk.agent.dto.AgentSkillResponse;
import com.intellidesk.agent.dto.AgentWorkloadResponse;
import com.intellidesk.agent.entity.AgentSkill;
import com.intellidesk.agent.repository.AgentSkillRepository;
import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.common.exception.InvalidRequestException;
import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin management of agent expertise and the workload overview that makes
 * the assignment algorithm's inputs visible (auditable) to humans.
 */
@Service
public class AgentAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgentAdminService.class);

    private final AgentSkillRepository agentSkillRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final TicketRepository ticketRepository;

    public AgentAdminService(AgentSkillRepository agentSkillRepository,
                             UserRepository userRepository,
                             CategoryRepository categoryRepository,
                             TicketRepository ticketRepository) {
        this.agentSkillRepository = agentSkillRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public AgentSkillResponse addSkill(Long agentId, AgentSkillRequest request) {
        User agent = loadActiveAgent(agentId);
        Category category = categoryRepository.findById(request.categoryId())
                .filter(Category::isActive)
                .orElseThrow(() -> new InvalidRequestException(
                        "Category " + request.categoryId() + " does not exist or is inactive"));

        AgentSkill skill = agentSkillRepository
                .findByAgentId(agentId).stream()
                .filter(s -> s.getCategory().getId().equals(category.getId()))
                .findFirst()
                .map(existing -> { // upsert: update proficiency instead of failing
                    existing.setProficiencyLevel(request.proficiencyLevel());
                    return existing;
                })
                .orElseGet(() -> agentSkillRepository.save(
                        new AgentSkill(agent, category, request.proficiencyLevel())));

        log.info("Skill set: agent={} category={} level={}", agent.getEmail(),
                category.getCode(), request.proficiencyLevel());
        return toResponse(skill);
    }

    @Transactional
    public void removeSkill(Long agentId, Long categoryId) {
        loadActiveAgent(agentId);
        AgentSkill skill = agentSkillRepository.findByAgentId(agentId).stream()
                .filter(s -> s.getCategory().getId().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Skill not found for agent " + agentId + " and category " + categoryId));
        agentSkillRepository.delete(skill);
        log.info("Skill removed: agent={} category={}", agentId, categoryId);
    }

    @Transactional(readOnly = true)
    public List<AgentSkillResponse> getSkills(Long agentId) {
        loadActiveAgent(agentId);
        return agentSkillRepository.findByAgentId(agentId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentWorkloadResponse> getAgentWorkloads() {
        List<User> agents = userRepository.findByRoleAndActiveTrue(Role.AGENT);
        if (agents.isEmpty()) {
            return List.of();
        }
        // two batched reads instead of two queries per agent (N+1)
        List<Long> agentIds = agents.stream().map(User::getId).toList();
        Map<Long, List<AgentWorkloadResponse.SkillSummary>> skillsByAgent = new HashMap<>();
        for (AgentSkill skill : agentSkillRepository.findByAgentIdIn(agentIds)) {
            skillsByAgent.computeIfAbsent(skill.getAgent().getId(), k -> new java.util.ArrayList<>())
                    .add(new AgentWorkloadResponse.SkillSummary(
                            skill.getCategory().getId(),
                            skill.getCategory().getCode(),
                            skill.getProficiencyLevel()));
        }
        Map<Long, Long> workloadByAgent = new HashMap<>();
        for (Object[] row : ticketRepository.countActiveByAgentIdIn(
                agentIds, TicketStatus.ACTIVE_WORK_STATUSES)) {
            workloadByAgent.put((Long) row[0], (Long) row[1]);
        }

        return agents.stream()
                .map(agent -> new AgentWorkloadResponse(
                        agent.getId(),
                        agent.getFullName(),
                        agent.getEmail(),
                        agent.isActive(),
                        skillsByAgent.getOrDefault(agent.getId(), List.of()),
                        workloadByAgent.getOrDefault(agent.getId(), 0L)))
                .toList();
    }

    private User loadActiveAgent(Long agentId) {
        User user = userRepository.findById(agentId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", agentId));
        if (user.getRole() != Role.AGENT) {
            throw new InvalidRequestException("User " + agentId + " is not an agent");
        }
        if (!user.isActive()) {
            throw new InvalidRequestException("Agent " + agentId + " is deactivated");
        }
        return user;
    }

    private AgentSkillResponse toResponse(AgentSkill skill) {
        return new AgentSkillResponse(
                skill.getId(),
                skill.getAgent().getId(),
                skill.getAgent().getFullName(),
                skill.getCategory().getId(),
                skill.getCategory().getCode(),
                skill.getProficiencyLevel());
    }
}
