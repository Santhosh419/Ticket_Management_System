package com.intellidesk.ticket.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates human-readable ticket numbers, e.g. {@code TKD-2026-000123}.
 *
 * <p>Design decision: the number is DERIVED FROM THE PRIMARY KEY
 * ({@code TKD-<year>-%06d(id)}), which makes it unique by construction with
 * zero race conditions. The id only exists after the INSERT, so creation is a
 * two-step dance inside one transaction:</p>
 *
 * <ol>
 *   <li>persist with a unique temporary placeholder (satisfies NOT NULL + UNIQUE),</li>
 *   <li>flush (id assigned), overwrite with the final number, commit.</li>
 * </ol>
 *
 * <p>The intermediate value is never visible outside the transaction. We
 * deliberately do NOT use a sequential counter guessed before insert: it would
 * either race under concurrency or leak ticket volume (sequential numbers tell
 * competitors exactly how many tickets you receive - the reason production
 * helpdesks avoid them).</p>
 */
@Component
public class TicketNumberGenerator {

    private static final int MAX_LENGTH = 20;

    /** Unique temporary value used between INSERT and id assignment. */
    public String placeholder() {
        String hex = Long.toHexString(UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFFFFFL)
                .toUpperCase(Locale.ROOT);
        return ("T" + hex + "0000000000").substring(0, 13); // 13 chars, fits varchar(20)
    }

    /** Final number derived from the persisted primary key and creation year (UTC). */
    public String format(long id, Instant createdAt) {
        int year = createdAt.atZone(java.time.ZoneOffset.UTC).getYear();
        String number = "TKD-" + year + "-%06d".formatted(id);
        if (number.length() > MAX_LENGTH) {
            throw new IllegalStateException("Generated ticket number exceeds column length: " + number);
        }
        return number;
    }
}
