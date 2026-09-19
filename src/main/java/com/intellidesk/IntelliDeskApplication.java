package com.intellidesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * IntelliDesk entry point.
 *
 * <p>{@link EnableJpaAuditing} is declared here (on the @SpringBootConfiguration) so that
 * JPA auditing (createdAt / updatedAt) is also active inside narrow test slices such as
 * {@code @DataJpaTest}, which always include the boot configuration class.</p>
 *
 * <p>{@link ConfigurationPropertiesScan} registers record-based configuration
 * properties such as {@code JwtProperties}.</p>
 */
@SpringBootApplication
@EnableJpaAuditing
@ConfigurationPropertiesScan
public class IntelliDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelliDeskApplication.class, args);
    }
}
