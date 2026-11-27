package com.dailyai.assistant.migration;

import com.dailyai.assistant.DailyAiAssistantApplication;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.entity.MentorReview;
import com.dailyai.assistant.entity.PersonalLearningPlanItem;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.entity.UserProgressEntry;
import com.dailyai.assistant.repository.ArticleExplanationRepository;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import com.dailyai.assistant.repository.MentorReviewRepository;
import com.dailyai.assistant.repository.PersonalLearningPlanRepository;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import com.dailyai.assistant.repository.UserRepository;
import com.dailyai.assistant.repository.ArticleSpecifications;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GuestFeedbackPostgresMigrationTest {
    private static final String MIGRATIONS = "classpath:db/migration";

    @Test
    void migrationsFromCleanV18AndV21SchemasPreserveDataAndValidateJpaOnPostgres() throws Exception {
        String configuredUrl = System.getProperty("dailyai.test.postgres.url");
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            boolean schemaMode = Boolean.getBoolean("dailyai.test.postgres.use-schemas");
            String password = System.getenv("DAILYAI_TEST_POSTGRES_PASSWORD");
            if (password == null) password = System.getProperty("dailyai.test.postgres.password", "");
            verifyAgainstPostgres(configuredUrl,
                    System.getProperty("dailyai.test.postgres.username", "postgres"),
                    password, schemaMode);
            return;
        }

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            verifyAgainstPostgres(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), false);
        }
    }

    private void verifyAgainstPostgres(String adminUrl, String username, String password, boolean schemaMode) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String cleanTarget = "dailyai_clean_" + suffix;
        String existingTarget = "dailyai_v18_" + suffix;
        String v21Target = "dailyai_v21_" + suffix;
        if (schemaMode) {
            createSchema(adminUrl, username, password, cleanTarget);
            createSchema(adminUrl, username, password, existingTarget);
            createSchema(adminUrl, username, password, v21Target);
        } else {
            createDatabase(adminUrl, username, password, cleanTarget);
            createDatabase(adminUrl, username, password, existingTarget);
            createDatabase(adminUrl, username, password, v21Target);
        }
        try {
            String cleanUrl = schemaMode ? schemaUrl(adminUrl, cleanTarget) : databaseUrl(adminUrl, cleanTarget);
            String existingUrl = schemaMode ? schemaUrl(adminUrl, existingTarget) : databaseUrl(adminUrl, existingTarget);
            DataSource cleanDataSource = dataSource(cleanUrl, username, password);
            DataSource existingDataSource = dataSource(existingUrl, username, password);

            Flyway cleanFlyway = flyway(cleanDataSource, null, schemaMode ? cleanTarget : null);
            cleanFlyway.migrate();
            assertThat(cleanFlyway.info().current().getVersion().getVersion()).isEqualTo("27");
            assertDefaultsAndConstraints(new JdbcTemplate(cleanDataSource));
            assertAiDiagnosticSchema(new JdbcTemplate(cleanDataSource));
            verifyJpaStartupAndRoundTrip(cleanUrl, username, password, null, null, null);
            cleanFlyway.validate();
            cleanFlyway.migrate(); // repeated application must be a no-op
            assertThat(cleanFlyway.info().current().getVersion().getVersion()).isEqualTo("27");

            Flyway v18Flyway = flyway(existingDataSource, "18", schemaMode ? existingTarget : null);
            v18Flyway.migrate();
            assertThat(v18Flyway.info().current().getVersion().getVersion()).isEqualTo("18");
            JdbcTemplate existing = new JdbcTemplate(existingDataSource);
            Long categoryId = existing.queryForObject(
                    "insert into categories(name, slug) values ('Migration fixture', 'migration-fixture') returning id", Long.class);
            Long articleId = existing.queryForObject("""
                    insert into articles(title, url, description, source, category_id)
                    values ('Existing V18 article', 'https://migration.test/existing',
                            'Existing article description', 'Migration fixture', ?)
                    returning id
                    """, Long.class, categoryId);
            Long explanationId = existing.queryForObject("""
                    insert into article_explanations(article_id, what_happened, importance, generated_by)
                    values (?, 'Existing explanation text', 'Existing explanation importance', 'BASIC')
                    returning id
                    """, Long.class, articleId);
            existing.update("insert into guest_article_interactions(guest_id, article_id, useful, feedback_action) values (?, ?, false, 'NOT_USEFUL')",
                    "migration-guest", articleId);

            Flyway upgradeFlyway = flyway(existingDataSource, null, schemaMode ? existingTarget : null);
            upgradeFlyway.migrate();
            upgradeFlyway.validate();
            assertThat(upgradeFlyway.info().current().getVersion().getVersion()).isEqualTo("27");
            assertThat(existing.queryForObject("select count(*) from articles where id=? and title=? and description=?",
                    Integer.class, articleId, "Existing V18 article", "Existing article description")).isEqualTo(1);
            assertThat(existing.queryForObject("select count(*) from article_explanations where id=? and article_id=? and what_happened=? and importance=?",
                    Integer.class, explanationId, articleId, "Existing explanation text", "Existing explanation importance")).isEqualTo(1);
            assertThat(existing.queryForObject("select feedback_action from guest_article_interactions where guest_id='migration-guest'", String.class))
                    .isEqualTo("NOT_USEFUL");
            assertAiDiagnosticSchema(existing);
            assertThat(existing.queryForObject("select source_completeness from article_explanations where id=?", String.class, explanationId))
                    .isEqualTo("UNKNOWN");
            assertDefaultsAndConstraints(existing);
            verifyJpaStartupAndRoundTrip(existingUrl, username, password, articleId,
                    "Existing V18 article", "Existing explanation text");
            Integer articleCountAfterStartup = existing.queryForObject("select count(*) from articles", Integer.class);
            Integer explanationCountAfterStartup = existing.queryForObject("select count(*) from article_explanations", Integer.class);
            upgradeFlyway.migrate(); // repeated application after V18 upgrade must be a no-op
            assertThat(upgradeFlyway.info().current().getVersion().getVersion()).isEqualTo("27");
            assertThat(existing.queryForObject("select count(*) from articles", Integer.class)).isEqualTo(articleCountAfterStartup);
            assertThat(existing.queryForObject("select count(*) from article_explanations", Integer.class)).isEqualTo(explanationCountAfterStartup);

            String v21Url = schemaMode ? schemaUrl(adminUrl, v21Target) : databaseUrl(adminUrl, v21Target);
            DataSource v21DataSource = dataSource(v21Url, username, password);
            Flyway v21Flyway = flyway(v21DataSource, "21", schemaMode ? v21Target : null);
            v21Flyway.migrate();
            assertThat(v21Flyway.info().current().getVersion().getVersion()).isEqualTo("21");
            JdbcTemplate v21 = new JdbcTemplate(v21DataSource);
            Long v21ArticleId = v21.queryForObject(
                    "insert into articles(title, url) values ('Existing V21 article', 'https://migration.test/v21') returning id", Long.class);
            v21.update("insert into article_explanations(article_id, what_happened, generated_by) values (?, 'Existing V21 explanation', 'BASIC')", v21ArticleId);
            Flyway v21Upgrade = flyway(v21DataSource, null, schemaMode ? v21Target : null);
            v21Upgrade.migrate();
            v21Upgrade.validate();
            assertThat(v21Upgrade.info().current().getVersion().getVersion()).isEqualTo("27");
            assertThat(v21.queryForObject("select title from articles where id=?", String.class, v21ArticleId)).isEqualTo("Existing V21 article");
            assertThat(v21.queryForObject("select what_happened from article_explanations where article_id=?", String.class, v21ArticleId))
                    .isEqualTo("Existing V21 explanation");
            assertThat(v21.queryForObject("select count(*) from user_progress_entries", Integer.class)).isZero();
            v21Upgrade.migrate();
            assertThat(v21Upgrade.info().current().getVersion().getVersion()).isEqualTo("27");
            verifyJpaStartupAndRoundTrip(v21Url, username, password, v21ArticleId,
                    "Existing V21 article", "Existing V21 explanation");
        } finally {
            if (schemaMode) {
                dropSchema(adminUrl, username, password, cleanTarget);
                dropSchema(adminUrl, username, password, existingTarget);
                dropSchema(adminUrl, username, password, v21Target);
            } else {
                dropDatabase(adminUrl, username, password, cleanTarget);
                dropDatabase(adminUrl, username, password, existingTarget);
                dropDatabase(adminUrl, username, password, v21Target);
            }
        }
    }

    private void assertAiDiagnosticSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='ai_request_diagnostics'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='ai_request_diagnostics' and column_name in ('prompt_tokens','completion_tokens','total_tokens','outcome','field_validation_result','factuality_rejection_reason')", Integer.class))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=current_schema() and table_name='ai_request_diagnostics' and constraint_name in ('ck_ai_request_diagnostic_tokens','ck_ai_request_diagnostic_status')", Integer.class))
                .isEqualTo(2);
    }

    private void verifyJpaStartupAndRoundTrip(String jdbcUrl, String username, String password, Long oldArticleId,
                                              String oldArticleTitle, String oldExplanationText) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DailyAiAssistantApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "spring.jpa.hibernate.ddl-auto", "validate",
                        "app.rss.fetch-on-startup", "false",
                        "app.auth.bootstrap-email", "",
                        "app.auth.bootstrap-password", "",
                        "app.ai.enabled", "false",
                        "spring.task.scheduling.enabled", "false",
                        "logging.level.root", "WARN"))
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password)) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.url")).isEqualTo(jdbcUrl);
            ArticleRepository articles = context.getBean(ArticleRepository.class);
            ArticleExplanationRepository explanations = context.getBean(ArticleExplanationRepository.class);
            CategoryRepository categories = context.getBean(CategoryRepository.class);
            UserRepository users = context.getBean(UserRepository.class);
            UserProgressEntryRepository progressEntries = context.getBean(UserProgressEntryRepository.class);
            MentorReviewRepository mentorReviews = context.getBean(MentorReviewRepository.class);
            PersonalLearningPlanRepository personalPlans = context.getBean(PersonalLearningPlanRepository.class);
            if (oldArticleId != null) {
                Article old = articles.findById(oldArticleId).orElseThrow();
                assertThat(old.getTitle()).isEqualTo(oldArticleTitle);
                assertThat(old.getRegion()).isEqualTo("UNKNOWN");
                ArticleExplanation oldExplanation = explanations.findByArticleId(oldArticleId).orElseThrow();
                assertThat(oldExplanation.getWhatHappened()).isEqualTo(oldExplanationText);
                assertThat(oldExplanation.getSourceCompleteness()).isEqualTo("UNKNOWN");
            }

            Category category = categories.save(new Category("JPA migration check", "jpa-migration-check-" + UUID.randomUUID(), null));
            Article article = new Article();
            article.setTitle("JPA round trip");
            article.setUrl("https://migration.test/jpa-" + UUID.randomUUID());
            article.setDescription("PostgreSQL JPA mapping round trip");
            article.setSource("Migration check");
            article.setRegion("INDIA");
            article.setFetchedAt(Instant.now());
            article.setPublishedAt(Instant.now().minusSeconds(3600));
            article.setCategory(category);
            article.setRssContent("Explicitly permitted content");
            article.setRssContentAiAllowed(true);
            article.setRssContentTruncated(true);
            article.setPublisherSourceContent("Publisher article body round trip.");
            article.setPublisherSourceType("FULL_ARTICLE");
            article.setPublisherSourceCompleteness("COMPLETE");
            articles.saveAndFlush(article);

            ArticleExplanation explanation = new ArticleExplanation();
            explanation.setArticle(article);
            explanation.setWhatHappened("Saved through JPA");
            explanation.setWhyItHappened("Reason from test source");
            explanation.setUsefulToReader("Test reader value");
            explanation.setRecommendedAction("No action needed");
            explanation.setSourceType("RSS_CONTENT");
            explanation.setSourceCompleteness("TRUNCATED");
            explanations.saveAndFlush(explanation);

            Article loaded = articles.findById(article.getId()).orElseThrow();
            assertThat(loaded.isRssContentAiAllowed()).isTrue();
            assertThat(loaded.isRssContentTruncated()).isTrue();
            assertThat(loaded.getRssContent()).isEqualTo("Explicitly permitted content");
            assertThat(loaded.getPublisherSourceContent()).isEqualTo("Publisher article body round trip.");
            assertThat(loaded.getPublisherSourceType()).isEqualTo("FULL_ARTICLE");
            assertThat(loaded.getPublisherSourceCompleteness()).isEqualTo("COMPLETE");
            assertThat(loaded.getRegion()).isEqualTo("INDIA");
            assertPostgresNewsFilters(articles, categories, category);
            ArticleExplanation loadedExplanation = explanations.findByArticleId(article.getId()).orElseThrow();
            assertThat(loadedExplanation.getSourceCompleteness()).isEqualTo("TRUNCATED");
            assertThat(loadedExplanation.getWhyItHappened()).isEqualTo("Reason from test source");

            User journalUser = users.saveAndFlush(new User("Journal fixture", "journal-" + UUID.randomUUID() + "@example.test"));
            UserProgressEntry journalEntry = new UserProgressEntry();
            journalEntry.setUser(journalUser);
            journalEntry.setEntryDate(java.time.LocalDate.of(2026, 11, 25));
            journalEntry.setLearned("PostgreSQL migrations");
            journalEntry.setAttachmentName("notes.txt");
            journalEntry.setAttachmentType("text/plain");
            journalEntry.setAttachmentText("Extracted journal attachment");
            progressEntries.saveAndFlush(journalEntry);
            UserProgressEntry loadedJournal = progressEntries.findByUserIdAndEntryDate(journalUser.getId(), journalEntry.getEntryDate()).orElseThrow();
            assertThat(loadedJournal.getLearned()).isEqualTo("PostgreSQL migrations");
            assertThat(loadedJournal.getAttachmentText()).isEqualTo("Extracted journal attachment");
            journalUser.setCareerGoal("Prepare for Java backend roles");
            journalUser.setTargetRoles("Java developer");
            users.saveAndFlush(journalUser);
            assertThat(users.findById(journalUser.getId()).orElseThrow().getCareerGoal()).isEqualTo("Prepare for Java backend roles");
            MentorReview review = new MentorReview();
            review.setUser(journalUser);
            review.setReviewDate(java.time.LocalDate.of(2026, 11, 25));
            review.setStatus("BASIC");
            review.setPromptVersion("mentor-v1");
            review.setContextHash("0".repeat(64));
            review.setReportJson("{\"status\":\"BASIC\"}");
            mentorReviews.saveAndFlush(review);
            assertThat(mentorReviews.findByUserIdAndReviewDate(journalUser.getId(), review.getReviewDate()).orElseThrow().getReportJson())
                    .contains("BASIC");
            PersonalLearningPlanItem planItem = new PersonalLearningPlanItem();
            planItem.setUser(journalUser);
            planItem.setPlanDate(java.time.LocalDate.of(2026, 11, 26));
            planItem.setDueDate(java.time.LocalDate.of(2026, 11, 26));
            planItem.setSection(PersonalLearningPlanItem.Section.STAY_UPDATED);
            planItem.setTitle("Read the migration article");
            planItem.setDescription("Read the original source.");
            planItem.setRationale("This source matches the saved interest.");
            planItem.setPriority(PersonalLearningPlanItem.Priority.LOW);
            planItem.setEstimatedMinutes(15);
            planItem.setRelatedSkill("PostgreSQL");
            planItem.setSourceArticle(article);
            personalPlans.saveAndFlush(planItem);
            assertThat(personalPlans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(journalUser.getId(), planItem.getPlanDate()))
                    .singleElement().satisfies(saved -> {
                        assertThat(saved.getSourceArticle().getId()).isEqualTo(article.getId());
                        assertThat(saved.getRelatedSkill()).isEqualTo("PostgreSQL");
                        assertThat(saved.getRationale()).contains("saved interest");
                    });
        }
    }

    private void assertPostgresNewsFilters(ArticleRepository articles, CategoryRepository categories, Category indiaCategory) {
        Instant now = Instant.now();
        Category global = categories.saveAndFlush(new Category("Filter Global", "filter-global-" + UUID.randomUUID(), null));
        Category unknown = categories.saveAndFlush(new Category("Filter Unknown", "filter-unknown-" + UUID.randomUUID(), null));
        Article indiaToday = filterArticle(articles, indiaCategory, "India Today", "INDIA", now.minusSeconds(1800));
        Article globalToday = filterArticle(articles, global, "Global Today", "GLOBAL", now.minusSeconds(900));
        Article globalWeek = filterArticle(articles, global, "Global Week", "GLOBAL", now.minusSeconds(3 * 86400L));
        Article unknownOld = filterArticle(articles, unknown, "Unknown Old", "UNKNOWN", now.minusSeconds(10 * 86400L));
        Long indiaId = indiaToday.getId(), globalTodayId = globalToday.getId();
        Long globalWeekId = globalWeek.getId(), unknownOldId = unknownOld.getId();
        PageRequest page = PageRequest.of(0, 100);
        var noFilters = articles.findAll(ArticleSpecifications.filters(null, null, null, null), page);
        assertThat(noFilters.getContent()).extracting(Article::getId).contains(indiaId, globalTodayId, globalWeekId, unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, "INDIA", null, null), page).getContent())
                .extracting(Article::getId).contains(indiaId).doesNotContain(globalTodayId, globalWeekId, unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, "GLOBAL", null, null), page).getContent())
                .extracting(Article::getId).contains(globalTodayId, globalWeekId).doesNotContain(indiaId, unknownOldId);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        var week = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(6)
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        assertThat(articles.findAll(ArticleSpecifications.filters(null, null, today, null), page).getContent())
                .extracting(Article::getId).contains(indiaId, globalTodayId).doesNotContain(globalWeekId, unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, null, week, null), page).getContent())
                .extracting(Article::getId).contains(indiaId, globalTodayId, globalWeekId).doesNotContain(unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, "INDIA", today, null), page).getContent())
                .extracting(Article::getId).contains(indiaId).doesNotContain(globalTodayId, globalWeekId, unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, "UNKNOWN", null, null), page).getContent())
                .extracting(Article::getId).contains(unknownOldId);
        assertThat(articles.findAll(ArticleSpecifications.filters(null, "INDIA", now.plusSeconds(86400), null), page))
                .isEmpty();
    }

    private Article filterArticle(ArticleRepository repository, Category category, String title, String region, Instant publishedAt) {
        Article article = new Article();
        article.setTitle(title); article.setUrl("https://filter.test/" + UUID.randomUUID());
        article.setDescription("Filter integration fixture"); article.setSource("PostgreSQL test");
        article.setCategory(category); article.setRegion(region); article.setPublishedAt(publishedAt);
        article.setFetchedAt(Instant.now());
        return repository.saveAndFlush(article);
    }

    private void assertDefaultsAndConstraints(JdbcTemplate jdbc) {
        Long articleId = jdbc.queryForObject("""
                insert into articles(title, url) values ('Default verification', 'https://migration.test/defaults-' || gen_random_uuid())
                returning id
                """, Long.class);
        jdbc.update("insert into article_explanations(article_id, generated_by) values (?, 'BASIC')", articleId);
        var articleDefaults = jdbc.queryForMap("""
                select rss_content_truncated, rss_content_ai_allowed, publisher_source_content,
                       publisher_source_type, publisher_source_completeness, region
                from articles where id=?
                """, articleId);
        assertThat(articleDefaults).containsEntry("rss_content_truncated", false).containsEntry("rss_content_ai_allowed", false);
        assertThat(articleDefaults).containsEntry("publisher_source_content", null)
                .containsEntry("publisher_source_type", null)
                .containsEntry("publisher_source_completeness", null)
                .containsEntry("region", "UNKNOWN");
        assertThat(jdbc.queryForObject("select source_completeness from article_explanations where article_id=?", String.class, articleId))
                .isEqualTo("UNKNOWN");

        assertColumn(jdbc, "articles", "rss_content_truncated", "NO", "false");
        assertColumn(jdbc, "articles", "rss_content_ai_allowed", "NO", "false");
        assertColumn(jdbc, "article_explanations", "source_completeness", "NO", "'UNKNOWN'");
        assertColumn(jdbc, "user_progress_entries", "entry_date", "NO", null);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name='user_progress_entries'", Integer.class))
                .isEqualTo(13);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname='uk_progress_entry_user_date' and connamespace=current_schema()::regnamespace", Integer.class))
                .isEqualTo(1);
        assertColumn(jdbc, "users", "career_goal", "NO", "''::text");
        assertColumn(jdbc, "users", "target_roles", "NO", "''::text");
        assertColumn(jdbc, "articles", "region", "NO", "'UNKNOWN'");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='mentor_reviews'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=current_schema() and table_name='personal_learning_plan_items'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname='uk_mentor_review_user_date' and connamespace=current_schema()::regnamespace", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema() and table_name='article_explanations'
                  and column_name in ('why_it_happened', 'useful_to_reader', 'recommended_action', 'source_type')
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema=current_schema() and table_name='articles'
                  and column_name in ('publisher_source_content', 'publisher_source_type', 'publisher_source_completeness')
                """, Integer.class)).isEqualTo(3);
    }

    private void assertColumn(JdbcTemplate jdbc, String table, String column, String nullable, String defaultFragment) {
        var metadata = jdbc.queryForMap("""
                select is_nullable, column_default from information_schema.columns
                where table_schema=current_schema() and table_name=? and column_name=?
                """, table, column);
        assertThat(metadata.get("is_nullable")).isEqualTo(nullable);
        if (defaultFragment != null) assertThat(metadata.get("column_default").toString()).contains(defaultFragment);
    }

    private Flyway flyway(DataSource dataSource, String target, String schema) {
        var configuration = Flyway.configure().dataSource(dataSource).locations(MIGRATIONS);
        if (schema != null) configuration.schemas(schema).defaultSchema(schema);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private void createSchema(String url, String username, String password, String schema) throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
    }

    private void dropSchema(String url, String username, String password, String schema) throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private DataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password);
    }

    private void createDatabase(String adminUrl, String username, String password, String database) throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
    }

    private void dropDatabase(String adminUrl, String username, String password, String database) throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + database + " with (force)");
        }
    }

    private String databaseUrl(String adminUrl, String database) {
        int pathStart = adminUrl.lastIndexOf('/');
        int queryStart = adminUrl.indexOf('?', pathStart);
        String suffix = queryStart < 0 ? "" : adminUrl.substring(queryStart);
        return adminUrl.substring(0, pathStart + 1) + database + suffix;
    }

    private String schemaUrl(String databaseUrl, String schema) {
        return databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }
}
