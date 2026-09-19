package com.intellidesk.classification;

import com.intellidesk.classification.domain.Sentiment;
import com.intellidesk.ticket.domain.TicketPriority;

/**
 * The result of classifying one ticket text, regardless of WHO classified it
 * (rule engine today, an external AI provider tomorrow).
 *
 * @param categoryCode      seeded category code such as {@code PAYMENT}; the
 *                          service layer resolves it to the entity, so this
 *                          contract stays free of persistence types
 * @param priority          suggested support priority
 * @param sentiment         detected customer sentiment
 * @param suggestedResponse internal agent hint (null when the implementation
 *                          has nothing useful to suggest); never shown to
 *                          customers
 */
public record TicketClassification(
        String categoryCode,
        TicketPriority priority,
        Sentiment sentiment,
        String suggestedResponse
) {

    /**
     * Safe default used when classification is unavailable or fails: the ticket
     * lands in the general queue with a medium priority instead of the request
     * failing. part of the availability contract - see
     * {@code TicketService#classifySafely}.
     */
    public static TicketClassification fallback() {
        return new TicketClassification("OTHER", TicketPriority.MEDIUM, Sentiment.NEUTRAL, null);
    }
}
