package com.intellidesk.agent.service;

import org.springframework.stereotype.Component;

/**
 * The deterministic, explainable scoring function - NO repository access, no
 * clock, no randomness. Given one candidate's facts it produces one score,
 * so identical inputs always produce identical rankings (a hard requirement:
 * admins must be able to reproduce and defend every assignment).
 *
 * <pre>
 * score = W_exp * expertise          + W_work * workloadScore      + W_avail * availability
 *         (0..1)                     (0..1)                        (0 or 1)
 *       + priorityBoost
 *
 *   expertise      = proficiency / 5                      (0 when no skill entry)
 *   workloadScore  = 1 - min(1, activeTickets / maxPerAgent)
 *   availability   = 1 if agent has capacity (active < maxPerAgent) else 0
 *   priorityBoost  = CRITICAL: +0.20 if expertise >= 0.6, else +0.10 if HIGH and >= 0.6
 *
 *   default weights: W_exp = 0.50, W_work = 0.30, W_avail = 0.20
 * </pre>
 *
 * Rationale: expertise dominates (a skilled agent resolves faster even under
 * load); workload is the second factor (spreading load keeps throughput even);
 * availability is a hard-ish bonus so saturated agents lose unless their
 * expertise advantage is large. The critical boost makes emergencies prefer
 * proven experts explicitly instead of relying on weight tuning.
 */
@Component
public class AgentScorer {

    public record CandidateFacts(int proficiency, int activeTickets) {}

    public record Score(double total, double expertise, double workloadScore,
                        double availability, double priorityBoost) {}

    /** Bound for treating an agent as an expert (0.6 == proficiency 3/5). */
    static final double EXPERT_THRESHOLD = 0.6;
    static final double CRITICAL_BOOST = 0.20;
    static final double HIGH_BOOST = 0.10;

    private final double weightExpertise;
    private final double weightWorkload;
    private final double weightAvailability;
    private final int maxActiveTickets;

    public AgentScorer(com.intellidesk.agent.AssignmentProperties properties) {
        this.weightExpertise = properties.weightExpertise();
        this.weightWorkload = properties.weightWorkload();
        this.weightAvailability = properties.weightAvailability();
        this.maxActiveTickets = properties.maxActiveTicketsPerAgent();
    }

    /**
     * @param urgent true for CRITICAL, half-true for HIGH (see priorityBoost).
     */
    public Score score(CandidateFacts facts, boolean critical, boolean high) {
        double expertise = clamp01((double) facts.proficiency() / 5.0);
        double saturation = maxActiveTickets == 0 ? 1.0
                : (double) facts.activeTickets() / maxActiveTickets;
        double workloadScore = 1.0 - Math.min(1.0, Math.max(0.0, saturation));
        double availability = saturation < 1.0 ? 1.0 : 0.0;

        double boost = 0.0;
        if (expertise >= EXPERT_THRESHOLD) {
            if (critical) {
                boost = CRITICAL_BOOST;
            } else if (high) {
                boost = HIGH_BOOST;
            }
        }

        double total = weightExpertise * expertise
                + weightWorkload * workloadScore
                + weightAvailability * availability
                + boost;
        return new Score(round4(total), round4(expertise), round4(workloadScore),
                availability, boost);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
