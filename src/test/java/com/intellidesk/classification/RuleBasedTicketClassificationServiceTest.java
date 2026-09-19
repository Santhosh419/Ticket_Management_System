package com.intellidesk.classification;

import com.intellidesk.classification.domain.Sentiment;
import com.intellidesk.ticket.domain.TicketPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seven contract cases for classification (payment, technical, security,
 * account, high-priority, negative sentiment, unknown/general) plus the two
 * properties the whole design rests on: determinism and the
 * CRITICAL > HIGH > LOW priority tiers. Pure unit tests - no Spring, no I/O,
 * no external API.
 */
class RuleBasedTicketClassificationServiceTest {

    private final RuleBasedTicketClassificationService classifier =
            new RuleBasedTicketClassificationService();

    @Test
    void paymentTicketIsClassifiedAsPayment() {
        TicketClassification result = classifier.classify(
                "Refund not received",
                "I was charged twice for one order and the money never came back.");

        assertThat(result.categoryCode()).isEqualTo("PAYMENT");
        assertThat(result.priority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(result.suggestedResponse()).contains("payment gateway");
    }

    @Test
    void technicalTicketIsClassifiedAsTechnical() {
        TicketClassification result = classifier.classify(
                "Application crashes on upload",
                "Every time I try to upload a file the app crashes and shows an error.");

        assertThat(result.categoryCode()).isEqualTo("TECHNICAL");
        assertThat(result.sentiment()).isEqualTo(Sentiment.NEUTRAL);
    }

    @Test
    void securityTicketIsClassifiedAsSecurity() {
        TicketClassification result = classifier.classify(
                "Suspicious activity on my profile",
                "I received a phishing email and now someone else is logged in.");

        assertThat(result.categoryCode()).isEqualTo("SECURITY");
        assertThat(result.suggestedResponse()).contains("security team");
    }

    @Test
    void accountTicketIsClassifiedAsAccount() {
        TicketClassification result = classifier.classify(
                "Cannot sign in to my account",
                "I forgot my password after the password change and now I am locked out.");

        assertThat(result.categoryCode()).isEqualTo("ACCOUNT");
        assertThat(result.priority()).isEqualTo(TicketPriority.MEDIUM);
    }

    @Test
    void urgentIssueGetsHighPriority() {
        TicketClassification result = classifier.classify(
                "Urgent: payment gateway not working",
                "We are losing money every minute, please fix this immediately.");

        assertThat(result.priority()).isEqualTo(TicketPriority.HIGH);
        assertThat(result.categoryCode()).isEqualTo("PAYMENT"); // category and priority are independent
    }

    @Test
    void outageGetsCriticalPriorityEvenWithUrgentWording() {
        TicketClassification result = classifier.classify(
                "Everything is down",
                "Production is down for all users since the last release, this is urgent.");

        assertThat(result.priority()).isEqualTo(TicketPriority.CRITICAL);
    }

    @Test
    void negativeSentimentIsDetectedAndShapesTheSuggestedResponse() {
        TicketClassification result = classifier.classify(
                "Terrible experience",
                "This is the third time my order has not arrived and I am really frustrated.");

        assertThat(result.sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(result.suggestedResponse()).startsWith("We're sorry for the trouble");
        assertThat(result.categoryCode()).isEqualTo("ORDER");
    }

    @Test
    void unknownTextFallsBackToGeneralQueue() {
        TicketClassification result = classifier.classify(
                "A strange request",
                "Hello team, something odd happened today and I need your help with it.");

        assertThat(result.categoryCode()).isEqualTo("OTHER");
        assertThat(result.priority()).isEqualTo(TicketPriority.MEDIUM);
        assertThat(result.sentiment()).isEqualTo(Sentiment.NEUTRAL);
    }

    @Test
    void classificationIsDeterministicAcrossRuns() {
        TicketClassification first = classifier.classify(
                "Cannot access my invoices",
                "The billing page shows a 500 error whenever I open it.");
        TicketClassification second = classifier.classify(
                "Cannot access my invoices",
                "The billing page shows a 500 error whenever I open it.");

        assertThat(second).isEqualTo(first);
        assertThat(second.categoryCode()).isEqualTo("PAYMENT"); // billing + invoices keywords
    }
}
