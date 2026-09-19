package com.intellidesk.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * "Agent X knows category Y at level Z" - payload for the admin skills API.
 */
public record AgentSkillRequest(

        @NotNull(message = "CategoryId is required")
        @Positive(message = "CategoryId must be a positive number")
        Long categoryId,

        @NotNull(message = "ProficiencyLevel is required")
        @Min(value = 1, message = "ProficiencyLevel must be 1-5")
        @Max(value = 5, message = "ProficiencyLevel must be 1-5")
        Integer proficiencyLevel
) {}
