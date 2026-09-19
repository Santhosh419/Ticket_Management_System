package com.intellidesk.agent.service;

import com.intellidesk.agent.AssignmentProperties;
import com.intellidesk.agent.service.AgentScorer.CandidateFacts;
import com.intellidesk.agent.service.AgentScorer.Score;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure scoring-function tests: the algorithm must behave exactly as
 * documented in the README, deterministically.
 */
class AgentScorerTest {

    private final AgentScorer scorer = new AgentScorer(
            new AssignmentProperties(true, 0.50, 0.30, 0.20, 10));

    @Test
    void expertiseDominatesWorkload() {
        // expert with moderate load beats novice with no load
        Score expertLoaded = scorer.score(new CandidateFacts(5, 6), false, false);
        Score noviceIdle = scorer.score(new CandidateFacts(1, 0), false, false);

        assertThat(expertLoaded.total()).isGreaterThan(noviceIdle.total());
        // and the documented arithmetic holds: 0.5*1.0 + 0.3*0.4 + 0.2*1.0
        assertThat(expertLoaded.total()).isCloseTo(0.5 * 1.0 + 0.3 * 0.4 + 0.2 * 1.0, within(0.0001));
    }

    @Test
    void workloadPenaltyReducesScoreStepByStep() {
        Score idle = scorer.score(new CandidateFacts(3, 0), false, false);
        Score half = scorer.score(new CandidateFacts(3, 5), false, false);
        Score full = scorer.score(new CandidateFacts(3, 10), false, false);
        Score over = scorer.score(new CandidateFacts(3, 14), false, false);

        assertThat(idle.total()).isGreaterThan(half.total());
        assertThat(half.total()).isGreaterThan(full.total());
        // saturation: beyond capacity the penalty caps (no negative scores)
        assertThat(over.total()).isCloseTo(full.total(), within(0.0001));
        assertThat(over.workloadScore()).isEqualTo(0.0);
    }

    @Test
    void noSkillMeansZeroExpertiseTerm() {
        Score unskilled = scorer.score(new CandidateFacts(0, 0), false, false);

        assertThat(unskilled.expertise()).isEqualTo(0.0);
        assertThat(unskilled.total()).isCloseTo(0.3 * 1.0 + 0.2 * 1.0, within(0.0001));
    }

    @Test
    void criticalBoostGoesOnlyToRealExperts() {
        Score expertCritical = scorer.score(new CandidateFacts(4, 0), true, false);
        Score expertNormal = scorer.score(new CandidateFacts(4, 0), false, false);
        Score noviceCritical = scorer.score(new CandidateFacts(1, 0), true, false);
        Score noviceNormal = scorer.score(new CandidateFacts(1, 0), false, false);

        assertThat(expertCritical.total()).isEqualTo(expertNormal.total() + 0.20);
        // a novice gets NO boost on a critical ticket - experts are preferred
        assertThat(noviceCritical.total()).isEqualTo(noviceNormal.total());
    }

    @Test
    void highPriorityBoostIsHalfTheCriticalBoost() {
        Score expertHigh = scorer.score(new CandidateFacts(4, 0), false, true);
        Score expertNormal = scorer.score(new CandidateFacts(4, 0), false, false);

        assertThat(expertHigh.total()).isEqualTo(expertNormal.total() + 0.10);
    }

    @Test
    void availabilityCliffWhenCapacityReached() {
        Score atCapacity = scorer.score(new CandidateFacts(3, 10), false, false);
        Score oneBelow = scorer.score(new CandidateFacts(3, 9), false, false);

        assertThat(atCapacity.availability()).isEqualTo(0.0);
        assertThat(oneBelow.availability()).isEqualTo(1.0);
        assertThat(oneBelow.total()).isGreaterThan(atCapacity.total());
    }

    @Test
    void scoringIsDeterministic() {
        Score a = scorer.score(new CandidateFacts(4, 3), true, false);
        Score b = scorer.score(new CandidateFacts(4, 3), true, false);

        assertThat(a).isEqualTo(b);
    }
}
