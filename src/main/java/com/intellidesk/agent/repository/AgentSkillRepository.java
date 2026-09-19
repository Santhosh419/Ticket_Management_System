package com.intellidesk.agent.repository;

import com.intellidesk.agent.entity.AgentSkill;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, Long> {

    @EntityGraph(attributePaths = {"agent", "category"})
    List<AgentSkill> findByCategoryId(Long categoryId);

    @EntityGraph(attributePaths = {"agent", "category"})
    List<AgentSkill> findByAgentId(Long agentId);

    /** Batch variant used by the assignment scorer (avoids per-agent N+1). */
    @EntityGraph(attributePaths = {"agent", "category"})
    List<AgentSkill> findByAgentIdIn(java.util.Collection<Long> agentIds);

    boolean existsByAgentIdAndCategoryId(Long agentId, Long categoryId);
}
