package com.intellidesk.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

/**
 * Tunables of the assignment algorithm. Weights are normalized to [0..1]
 * by construction (see AgentScorer) - changing them here changes the
 * behaviour without touching code, and tests pin the documented defaults.
 */
@Validated
@ConfigurationProperties(prefix = "intellidesk.assignment")
public record AssignmentProperties(
        boolean autoAssignOnCreate,

        @DecimalMin(value = "0.0", message = "weight-expertise must be >= 0")
        @DecimalMax(value = "1.0", message = "weight-expertise must be <= 1")
        double weightExpertise,

        @DecimalMin(value = "0.0", message = "weight-workload must be >= 0")
        @DecimalMax(value = "1.0", message = "weight-workload must be <= 1")
        double weightWorkload,

        @DecimalMin(value = "0.0", message = "weight-availability must be >= 0")
        @DecimalMax(value = "1.0", message = "weight-availability must be <= 1")
        double weightAvailability,

        @Positive(message = "max-active-tickets-per-agent must be positive")
        int maxActiveTicketsPerAgent
) {}
