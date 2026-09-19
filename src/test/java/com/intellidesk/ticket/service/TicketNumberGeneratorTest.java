package com.intellidesk.ticket.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the ticket-number scheme: format contract, column
 * length safety, and placeholder uniqueness.
 */
class TicketNumberGeneratorTest {

    private final TicketNumberGenerator generator = new TicketNumberGenerator();

    @Test
    void formatEmbedsYearAndZeroPaddedId() {
        Instant createdAt = Instant.parse("2026-09-19T10:00:00Z");

        String number = generator.format(42L, createdAt);

        assertThat(number).isEqualTo("TKD-2026-000042");
    }

    @Test
    void formatUsesUtcYearEvenAtYearBoundary() {
        // 23:30 UTC on Dec 31 must produce the UTC year, not a local one
        String number = generator.format(7L, Instant.parse("2025-12-31T23:30:00Z"));
        assertThat(number).startsWith("TKD-2025-");
    }

    @Test
    void formatStaysWithinColumnLengthEvenForLargeIds() {
        String number = generator.format(123_456_789L, Instant.now());
        assertThat(number).hasSizeLessThanOrEqualTo(20);
        assertThat(number).isEqualTo("TKD-" + Instant.now().atZone(java.time.ZoneOffset.UTC).getYear() + "-123456789");
    }

    @Test
    void placeholdersAreUniqueAndShortEnough() {
        String a = generator.placeholder();
        String b = generator.placeholder();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).hasSizeLessThanOrEqualTo(20);
        assertThat(a.length()).isGreaterThanOrEqualTo(13);
    }

    @Test
    void placeholderIsNeverMistakableForAFinalNumber() {
        // "T..." prefix cannot match the final "TKD-" scheme -> avoids collisions
        assertThat(generator.placeholder()).doesNotStartWith("TKD-");
    }
}
