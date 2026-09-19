package com.intellidesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * IntelliDesk entry point.
 *
 * <p>{@link EnableJpaAuditing} is declared here (on the @SpringBootConfiguration) so that
 * JPA auditing (createdAt / updatedAt) is also active inside narrow test slices such as
 * {@code @DataJpaTest}, which always include the boot configuration class.</p>
 */
@SpringBootApplication
@EnableJpaAuditing
public class IntelliDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelliDeskApplication.class, args);
    }
}
