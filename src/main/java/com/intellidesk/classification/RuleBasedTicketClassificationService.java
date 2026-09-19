package com.intellidesk.classification;

import com.intellidesk.classification.domain.Sentiment;
import com.intellidesk.ticket.domain.TicketPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic, offline implementation of {@link TicketClassificationService}.
 *
 * <p>How it decides (all pure string matching, no I/O, no randomness):</p>
 * <ol>
 *   <li><b>Category</b> - each category scores one point per DISTINCT keyword
 *       hit (word-boundary matched, case-insensitive) in {@code title + " " +
 *       description}. Highest score wins; ties are broken by the fixed
 *       declaration order below, so the same text always yields the same
 *       category. No hits at all -> OTHER.</li>
 *   <li><b>Priority</b> - phrase scan: CRITICAL phrases (outage, production
 *       down, data loss, ...) beat HIGH phrases (urgent, cannot access, ...),
 *       which beat LOW phrases (no hurry, cosmetic, ...). Default MEDIUM. The
 *       caller still lets an explicit request priority override this.</li>
 *   <li><b>Sentiment</b> - NEGATIVE keywords beat POSITIVE keywords, otherwise
 *       NEUTRAL.</li>
 *   <li><b>Suggested response</b> - a canned per-category template with a
 *       sentiment-aware prefix. Useful as an agent hint, never sent to the
 *       customer automatically.</li>
 * </ol>
 *
 * <p>An {@code AiTicketClassificationService} can replace this class by
 * declaring {@code @ConditionalOnProperty(name = "intellidesk.classification.provider",
 * havingValue = "ai")} - business logic never changes. Any exception an AI
 * implementation throws is caught by the caller and degrades to
 * {@link TicketClassification#fallback()}, keeping ticket creation available.</p>
 */
@Service
@ConditionalOnProperty(name = "intellidesk.classification.provider",
        havingValue = "rule-based", matchIfMissing = true)
public class RuleBasedTicketClassificationService implements TicketClassificationService {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedTicketClassificationService.class);

    /** Fixed tie-break order: earlier categories win score ties. */
    private static final List<String> CATEGORY_ORDER =
            List.of("PAYMENT", "SECURITY", "ACCOUNT", "TECHNICAL", "ORDER", "DELIVERY", "OTHER");

    private static final Map<String, Pattern> CATEGORY_PATTERNS = buildCategoryPatterns();

    private static final Pattern CRITICAL = phrasePattern(
            "outage", "production down", "production is down", "system down", "server down",
            "portal down", "website down", "all users", "entire team", "everyone affected",
            "data loss", "completely unusable", "security breach");
    private static final Pattern HIGH = phrasePattern(
            "urgent", "asap", "immediately", "emergency", "severe", "serious", "critical",
            "cannot access", "can't access", "cant access", "blocked", "losing money",
            "lost money", "money lost", "not working at all", "business impact");
    private static final Pattern LOW = phrasePattern(
            "not urgent", "no hurry", "whenever you get a chance", "whenever possible",
            "minor", "cosmetic", "just a question", "small doubt");

    private static final Pattern NEGATIVE = phrasePattern(
            "angry", "furious", "frustrated", "frustrating", "terrible", "horrible", "worst",
            "disgusting", "unacceptable", "pathetic", "disappointed", "disappointing",
            "annoyed", "annoying", "ridiculous", "awful", "fed up", "useless", "waste of");
    private static final Pattern POSITIVE = phrasePattern(
            "thanks", "thank you", "appreciate", "appreciated", "great", "awesome",
            "excellent", "wonderful", "good job", "kudos", "love");

    private static final Map<String, String> RESPONSE_TEMPLATES = Map.of(
            "PAYMENT", "Verify the transaction in the payment gateway dashboard and confirm the refund/settlement status with the customer.",
            "TECHNICAL", "Reproduce the reported error, check recent releases and error logs, then update the customer with the findings.",
            "SECURITY", "Escalate to the security team, secure the affected account (force password reset, revoke sessions) and review access logs.",
            "ACCOUNT", "Verify the customer's identity, then unlock or reset the account access and confirm the fix with the customer.",
            "ORDER", "Check the order status in the order system and coordinate with the fulfilment team for a correction.",
            "DELIVERY", "Track the shipment with the courier, confirm the current status and update the expected delivery date.",
            "OTHER", "Review the request, reproduce it if applicable, and respond to the customer with concrete next steps.");

    @Override
    public TicketClassification classify(String title, String description) {
        String text = ((title == null ? "" : title) + " " + (description == null ? "" : description))
                .toLowerCase();

        String category = classifyCategory(text);
        TicketPriority priority = classifyPriority(text);
        Sentiment sentiment = classifySentiment(text);
        String suggested = sentimentPrefix(sentiment) + RESPONSE_TEMPLATES.get(category);

        log.debug("Classified text as category={}, priority={}, sentiment={}", category, priority, sentiment);
        return new TicketClassification(category, priority, sentiment, suggested);
    }

    private String classifyCategory(String text) {
        String best = "OTHER";
        int bestScore = 0;
        for (String code : CATEGORY_ORDER) {
            Pattern pattern = CATEGORY_PATTERNS.get(code);
            if (pattern == null) {
                continue; // OTHER is the no-hit default, not a keyword set
            }
            int score = countDistinctHits(pattern, text);
            if (score > bestScore) {
                bestScore = score;
                best = code;
            }
        }
        return best;
    }

    private TicketPriority classifyPriority(String text) {
        if (CRITICAL.matcher(text).find()) {
            return TicketPriority.CRITICAL;
        }
        if (HIGH.matcher(text).find()) {
            return TicketPriority.HIGH;
        }
        if (LOW.matcher(text).find()) {
            return TicketPriority.LOW;
        }
        return TicketPriority.MEDIUM;
    }

    private Sentiment classifySentiment(String text) {
        if (NEGATIVE.matcher(text).find()) {
            return Sentiment.NEGATIVE;
        }
        if (POSITIVE.matcher(text).find()) {
            return Sentiment.POSITIVE;
        }
        return Sentiment.NEUTRAL;
    }

    /** Number of DISTINCT alternatives of the pattern present in the text. */
    private int countDistinctHits(Pattern pattern, String text) {
        java.util.Set<String> hits = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            hits.add(matcher.group().toLowerCase());
        }
        return hits.size();
    }

    private static Pattern phrasePattern(String... phrases) {
        return Pattern.compile("\\b(" + String.join("|", phrases) + ")\\b");
    }

    private static Map<String, Pattern> buildCategoryPatterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        patterns.put("PAYMENT", phrasePattern("payment", "refund", "charged", "charge", "transaction",
                "invoice", "billing", "card", "deducted", "money", "paid", "checkout",
                "subscription", "gateway", "upi", "cashback"));
        patterns.put("SECURITY", phrasePattern("hack", "hacked", "hacking", "phishing", "unauthorized",
                "unauthorised", "breach", "compromised", "suspicious", "fraud", "malware",
                "stolen", "stole", "spam", "impersonat"));
        patterns.put("ACCOUNT", phrasePattern("account", "log in", "login", "sign in", "signin",
                "sign up", "signup", "register", "registration", "password", "forgot",
                "profile", "credentials", "2fa", "two-factor", "locked out", "email change"));
        patterns.put("TECHNICAL", phrasePattern("error", "bug", "crash", "crashes", "crashed",
                "not working", "broken", "fails", "failing", "failed", "timeout", "exception",
                "slow", "loading", "hangs", "stuck", "blank", "freeze", "freezes",
                "unresponsive", "500 error"));
        patterns.put("ORDER", phrasePattern("order", "purchase", "bought", "item", "cart",
                "cancelled", "cancellation", "placed"));
        patterns.put("DELIVERY", phrasePattern("delivery", "deliver", "shipped", "shipment",
                "shipping", "tracking", "courier", "package", "parcel", "arrived",
                "dispatched", "pincode"));
        return patterns;
    }

    private static String sentimentPrefix(Sentiment sentiment) {
        return switch (sentiment) {
            case NEGATIVE -> "We're sorry for the trouble - ";
            case POSITIVE -> "Thanks for reaching out! ";
            case NEUTRAL -> "";
        };
    }
}
