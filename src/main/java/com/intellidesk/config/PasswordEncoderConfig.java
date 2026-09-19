package com.intellidesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Single source of truth for password hashing. BCrypt is deliberately slow
 * and salted per password, so leaked hashes resist brute-force attacks.
 * The strength factor (10 rounds by default) is a deliberate trade-off
 * between login latency and protection.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
