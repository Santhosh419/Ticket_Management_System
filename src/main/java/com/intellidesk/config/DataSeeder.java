package com.intellidesk.config;

import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.sla.entity.SlaPolicy;
import com.intellidesk.sla.repository.SlaPolicyRepository;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Idempotent bootstrap data: support categories, SLA policies and the first
 * admin account.
 *
 * <p>Idempotency matters: this runs on EVERY startup (and in every test
 * context), so each step checks "already there?" before inserting. The admin
 * password comes from configuration/env vars and is stored BCrypt-hashed;
 * the logged warning reminds operators to override the default.</p>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /** code, name, description */
    private record SeedCategory(String code, String name, String description) {}

    private static final List<SeedCategory> CATEGORIES = List.of(
            new SeedCategory("PAYMENT", "Payment",
                    "Payment failures, refunds, duplicate charges, invoice issues"),
            new SeedCategory("ACCOUNT", "Account",
                    "Login problems, profile updates, account access"),
            new SeedCategory("TECHNICAL", "Technical",
                    "Bugs, errors, performance problems, integrations"),
            new SeedCategory("SECURITY", "Security",
                    "Suspicious activity, phishing reports, data privacy concerns"),
            new SeedCategory("ORDER", "Order",
                    "Order placement, modification and cancellation issues"),
            new SeedCategory("DELIVERY", "Delivery",
                    "Shipping delays, lost parcels, wrong items delivered"),
            new SeedCategory("OTHER", "Other",
                    "Anything that does not fit another category")
    );

    private final CategoryRepository categoryRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public DataSeeder(CategoryRepository categoryRepository,
                      SlaPolicyRepository slaPolicyRepository,
                      UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${intellidesk.seed.admin-email:admin@intellidesk.local}") String adminEmail,
                      @Value("${intellidesk.seed.admin-password:Admin@12345}") String adminPassword,
                      @Value("${intellidesk.seed.admin-name:System Administrator}") String adminName) {
        this.categoryRepository = categoryRepository;
        this.slaPolicyRepository = slaPolicyRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedCategories();
        seedSlaPolicies();
        seedAdminUser();
    }

    private void seedCategories() {
        int created = 0;
        for (SeedCategory seed : CATEGORIES) {
            if (categoryRepository.existsByCode(seed.code())) {
                continue;
            }
            categoryRepository.save(new Category(seed.code(), seed.name(), seed.description()));
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} support categories", created);
        }
    }

    private void seedSlaPolicies() {
        int created = 0;
        for (TicketPriority priority : TicketPriority.values()) {
            if (slaPolicyRepository.findByPriorityAndActiveTrue(priority).isPresent()) {
                continue;
            }
            slaPolicyRepository.save(new SlaPolicy(priority, hoursFor(priority)));
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} SLA policies", created);
        }
    }

    private static int hoursFor(TicketPriority priority) {
        // Single definition of the SLA targets; also documented in the README.
        return switch (priority) {
            case CRITICAL -> 2;
            case HIGH -> 8;
            case MEDIUM -> 24;
            case LOW -> 48;
        };
    }

    private void seedAdminUser() {
        String email = adminEmail.trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(
                email,
                passwordEncoder.encode(adminPassword),
                adminName,
                null,
                Role.ADMIN
        ));
        log.warn("Seeded initial admin account '{}'. Change its password immediately "
                + "or set INTELLIDESK_ADMIN_EMAIL / INTELLIDESK_ADMIN_PASSWORD.", email);
    }
}
