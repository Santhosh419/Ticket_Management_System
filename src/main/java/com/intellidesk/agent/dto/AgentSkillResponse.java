package com.intellidesk.agent.dto;

public record AgentSkillResponse(
        Long id,
        Long agentId,
        String agentName,
        Long categoryId,
        String categoryCode,
        int proficiencyLevel
) {}
