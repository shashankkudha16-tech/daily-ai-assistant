package com.dailyai.assistant.migration;

import com.dailyai.assistant.entity.Role;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dailyai_bootstrap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        // V12 adds a PostgreSQL-only partial unique index that H2 cannot parse.
        "spring.flyway.target=11",
        // This H2-only legacy bootstrap fixture intentionally stops before V12 because
        // that migration creates a PostgreSQL partial index. Keep the fixture's entity
        // schema aligned with the current User mapping while exercising the old rows.
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.task.scheduling.enabled=false",
        "app.rss.fetch-on-startup=false",
        "app.auth.bootstrap-email=fresher@dailyai.local",
        "app.auth.bootstrap-password=BootstrapPass1!",
        "app.auth.bootstrap-role=ADMIN"
})
class LegacyAccountBootstrapTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void bootstrapProvisionsTheExistingOwnerFromConfiguredEnvironment() {
        User owner = userRepository.findById(1L).orElseThrow();
        assertThat(owner.getEmail()).isEqualTo("fresher@dailyai.local");
        assertThat(owner.getPasswordHash()).isNotNull();
        assertThat(passwordEncoder.matches("BootstrapPass1!", owner.getPasswordHash())).isTrue();
        assertThat(owner.getRole()).isEqualTo(Role.ADMIN);
        assertThat(owner.isEnabled()).isTrue();
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
