package com.dailyai.assistant.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dailyai_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        // V12 uses a PostgreSQL partial unique index, which H2 cannot parse. The
        // shared schema (V1-V11) is still fully applied and verified here. Later migrations are
        // verified against PostgreSQL separately, and their entity mapping is covered
        // by the H2 JPA tests that build the table from the entity.
        "spring.flyway.target=11",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.task.scheduling.enabled=false",
        "app.rss.fetch-on-startup=false"
})
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allMigrationsApplyAndKeepTheExistingOwner() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "select version, success from flyway_schema_history where version is not null order by installed_rank");
        assertThat(history).hasSize(11);
        assertThat(history).allMatch(row -> Boolean.TRUE.equals(row.get("success")));
        assertThat(history).anyMatch(row -> "11".equals(row.get("version")));

        List<String> ledgerColumns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = 'ai_usage_daily'",
                String.class);
        assertThat(ledgerColumns).contains("usage_date", "attempts", "successes", "failures",
                "input_tokens", "output_tokens", "created_at", "updated_at");
        assertThat(jdbcTemplate.queryForObject("select count(*) from ai_usage_daily", Integer.class)).isZero();

        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select id, name, email, preferences, password_hash, role, enabled from users order by id");
        assertThat(users).hasSize(1);
        Map<String, Object> owner = users.get(0);
        assertThat(((Number) owner.get("id")).longValue()).isEqualTo(1L);
        assertThat(owner.get("email")).isEqualTo("fresher@dailyai.local");
        assertThat(owner.get("password_hash")).isNull();
        assertThat(owner.get("role")).isEqualTo("USER");
        assertThat(owner.get("enabled")).isEqualTo(true);

        // The migration must not invent new credentials or seed a second account.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where password_hash is not null", Integer.class)).isZero();
    }
}
