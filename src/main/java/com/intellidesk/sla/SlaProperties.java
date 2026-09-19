package com.intellidesk.sla;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Externalized SLA-engine settings (see application.yml for defaults).
 */
@Validated
@ConfigurationProperties(prefix = "intellidesk.sla")
public record SlaProperties(

        @Positive(message = "scan-interval-ms must be positive")
        long scanIntervalMs,

        @Positive(message = "at-risk-minutes must be positive")
        long atRiskMinutes
) {}
