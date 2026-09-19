package com.intellidesk.config;

import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.sla.repository.SlaPolicyRepository;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the FULL application context on an in-memory database and verifies
 * that the idempotent seeder produced a usable starting state.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSeederIntegrationTest {

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SlaPolicyRepository slaPolicyRepository;
    @Autowired private UserRepository userRepository;

    private final BCryptPasswordEncoder rawEncoder = new BCryptPasswordEncoder();

    @Test
    void contextAndSeededReferenceDataAreAvailable() {
        assertThat(categoryRepository.count()).isGreaterThanOrEqualTo(7);
        assertThat(categoryRepository.existsByCode("PAYMENT")).isTrue();
        assertThat(categoryRepository.existsByCode("OTHER")).isTrue();

        for (TicketPriority priority : TicketPriority.values()) {
            assertThat(slaPolicyRepository.findByPriorityAndActiveTrue(priority))
                    .as("SLA policy for %s", priority)
                    .isPresent();
        }

        assertThat(slaPolicyRepository.findByPriorityAndActiveTrue(TicketPriority.CRITICAL)
                .orElseThrow().getResolutionHours()).isEqualTo(2);
        assertThat(slaPolicyRepository.findByPriorityAndActiveTrue(TicketPriority.LOW)
                .orElseThrow().getResolutionHours()).isEqualTo(48);
    }

    @Test
    void seededAdminHasBcryptHashAndAdminRole() {
        var admin = userRepository.findByEmail("test-admin@intellidesk.local").orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getPasswordHash())
                .startsWith("$2")
                .hasSizeGreaterThan(55);
        // Plain password was never stored; hash matches the configured seed password.
        assertThat(rawEncoder.matches("TestAdmin@123", admin.getPasswordHash())).isTrue();
        assertThat(rawEncoder.matches("TestAdmin@123", "TestAdmin@123")).isFalse();
    }
}
