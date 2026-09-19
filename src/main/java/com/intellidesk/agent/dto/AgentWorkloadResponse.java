package com.intellidesk.agent.dto;

import java.util.List;

/**
 * Agent overview for admins: who exists, what they know, how loaded they are.
 * Powers the "who should I assign manually?" decision and makes the
 * automatic assignment auditable against visible data.
 */
public record AgentWorkloadResponse(
        Long agentId,
        String fullName,
        String email,
        boolean active,
        List<SkillSummary> skills,
        long activeTickets
) {
    public record SkillSummary(Long categoryId, String categoryCode, int proficiencyLevel) {}
}
