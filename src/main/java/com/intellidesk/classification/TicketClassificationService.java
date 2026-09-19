package com.intellidesk.classification;

/**
 * The classification seam of IntelliDesk: turns free ticket text into the
 * structured routing decision (category, priority, sentiment, suggested
 * response).
 *
 * <h2>Why an interface</h2>
 * Business logic (ticket creation, SLA, assignment) depends ONLY on this
 * contract, never on an implementation. Implementations are selected by
 * configuration:
 * <pre>
 * intellidesk.classification.provider: rule-based   # default, deterministic, offline
 * intellidesk.classification.provider: ai           # future AiTicketClassificationService
 * </pre>
 * A future AI implementation only has to:
 * <ul>
 *   <li>annotate itself with
 *       {@code @ConditionalOnProperty(name = "intellidesk.classification.provider", havingValue = "ai")}</li>
 *   <li>read its credentials from the environment (never hardcode keys)</li>
 *   <li>throw freely on outages - {@code TicketService} wraps every call in a
 *       fallback, so the ticket system stays available when the provider is
 *       down (graceful degradation)</li>
 * </ul>
 *
 * <p>Implementations MUST be deterministic for the same input at least within
 * one process lifetime, because the result is persisted with the ticket and
 * drives assignment. No implementation may make network calls inside unit
 * tests; the rule-based one makes none at all.</p>
 */
public interface TicketClassificationService {

    /** Classifies the given ticket text. Implementations should not return null. */
    TicketClassification classify(String title, String description);
}
