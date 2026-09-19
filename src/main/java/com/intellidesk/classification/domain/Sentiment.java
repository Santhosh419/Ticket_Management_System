package com.intellidesk.classification.domain;

/**
 * Outcome of the sentiment analysis step of classification.
 *
 * <p>A deliberately tiny enum: downstream code (assignment, dashboards later)
 * switches on these three values only. Richer emotion models would live inside
 * a specific implementation, never in this shared contract.</p>
 */
public enum Sentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE
}
