package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.RefreshResultDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.DailyTask;
import com.dailyai.assistant.entity.LearningTopic;
import com.dailyai.assistant.entity.GuestTopicInterest;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.entity.UserTopicInterest;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.DailyTaskRepository;
import com.dailyai.assistant.repository.LearningTopicRepository;
import com.dailyai.assistant.repository.GuestTopicInterestRepository;
import com.dailyai.assistant.repository.GuestMigrationRepository;
import com.dailyai.assistant.repository.UserRepository;
import com.dailyai.assistant.repository.UserTopicInterestRepository;
import com.dailyai.assistant.service.AdminService;
import com.dailyai.assistant.service.GuestAccountMigrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final String PASSWORD = "StrongPass1!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private LearningTopicRepository topicRepository;
    @Autowired
    private DailyTaskRepository dailyTaskRepository;
    @Autowired private GuestTopicInterestRepository guestInterestRepository;
    @Autowired private GuestMigrationRepository guestMigrationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserTopicInterestRepository userInterestRepository;
    @Autowired private GuestAccountMigrationService guestAccountMigrationService;

    @MockBean
    private AdminService adminService;

    private Article article;
    private LearningTopic topic;
    private DailyTask task;

    @BeforeEach
    void seedContent() {
        String suffix = String.valueOf(System.nanoTime());
        article = new Article();
        article.setTitle("Shared public AI update");
        article.setUrl("https://example.com/phase7-" + suffix);
        article.setDescription("A public article used to verify ownership of bookmarks and reading history.");
        article.setPublishedAt(Instant.now());
        article = articleRepository.save(article);

        topic = new LearningTopic();
        topic.setLevel(1);
        topic.setLevelName("Fundamentals");
        topic.setTitle("Private topic progress");
        topic.setSlug("phase7-topic-" + suffix);
        topic.setOrderIndex(1);
        topic = topicRepository.save(topic);

        task = new DailyTask();
        task.setDayNumber(1);
        task.setTitle("Private daily task");
        task.setKind(DailyTask.Kind.LEARN);
        task.setEstimatedMinutes(10);
        task.setOrderIndex(1);
        task = dailyTaskRepository.save(task);
    }

    @Test
    void unauthenticatedPersonalApisAreRejectedButPublicNewsRemainsReadable() throws Exception {
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/progress/entries")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mentor/review")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/bookmarks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/learning/roadmap")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/news/latest")).andExpect(status().isOk());
    }

    @Test
    void guestCanReadArticleDetailsButNotAccountData() throws Exception {
        mockMvc.perform(get("/api/news/{id}", article.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Shared public AI update"));
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCookieIsPersistentAndSeparateBetweenVisitors() throws Exception {
        String firstCookie = guestCookie(mockMvc.perform(get("/api/guest/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(true))
                .andReturn());
        String secondCookie = guestCookie(mockMvc.perform(get("/api/guest/session"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(firstCookie).isNotEqualTo(secondCookie);

        String[] nameAndValue = firstCookie.split("=", 2);
        mockMvc.perform(get("/api/guest/session")
                        .cookie(new jakarta.servlet.http.Cookie(nameAndValue[0], nameAndValue[1])))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(firstCookie)));
    }

    @Test
    void guestHomepageDataAndSessionArePublicWhileAccountDashboardAndCsrfRemainProtected() throws Exception {
        String cookie = guestCookie(mockMvc.perform(get("/api/guest/session"))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/news/latest").param("limit", "12"))
                .andExpect(status().isOk());
        Csrf csrf = csrf(null);
        mockMvc.perform(post("/api/guest/recommendations").cookie(cookie(cookie))
                        .session(csrf.session()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/guest/recommendations").cookie(cookie(cookie)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestFeedbackIsCookieScopedAndRequiresCsrf() throws Exception {
        String guestCookie = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String payload = "{\"articleId\":" + article.getId() + ",\"action\":\"NOT_USEFUL\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/guest/feedback")
                        .cookie(cookie(guestCookie)).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        Csrf csrf = csrf(null);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/guest/feedback")
                        .cookie(cookie(guestCookie)).session(csrf.session()).header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/guest/feedback/{articleId}", article.getId()).cookie(cookie(guestCookie)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("NOT_USEFUL"));
        String otherGuest = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        mockMvc.perform(get("/api/guest/feedback/{articleId}", article.getId()).cookie(cookie(otherGuest)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").doesNotExist());
    }

    @Test
    void guestInteractionRequiresCsrfAndRecommendationsAreCookieIsolated() throws Exception {
        String first = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String second = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String[] firstParts = first.split("=", 2);
        String[] secondParts = second.split("=", 2);
        var firstCookie = new jakarta.servlet.http.Cookie(firstParts[0], firstParts[1]);
        var secondCookie = new jakarta.servlet.http.Cookie(secondParts[0], secondParts[1]);
        String payload = "{\"articleId\":" + article.getId() + ",\"type\":\"OPEN\"}";

        mockMvc.perform(post("/api/guest/interactions").cookie(firstCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        Csrf firstCsrf = csrf(null);
        mockMvc.perform(post("/api/guest/interactions").cookie(firstCookie).session(firstCsrf.session())
                        .header("X-XSRF-TOKEN", firstCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isNoContent());

        Csrf firstRead = csrf(firstCsrf.session());
        MvcResult firstRecommendations = mockMvc.perform(post("/api/guest/recommendations")
                        .cookie(firstCookie).session(firstRead.session())
                        .header("X-XSRF-TOKEN", firstRead.token()))
                .andExpect(status().isOk()).andReturn();
        var firstIds = objectMapper.readTree(firstRecommendations.getResponse().getContentAsString())
                .path("articles").findValuesAsText("id");
        assertThat(firstIds).doesNotContain(String.valueOf(article.getId()));

        Csrf secondCsrf = csrf(null);
        MvcResult secondRecommendations = mockMvc.perform(post("/api/guest/recommendations")
                        .cookie(secondCookie).session(secondCsrf.session())
                        .header("X-XSRF-TOKEN", secondCsrf.token()))
                .andExpect(status().isOk()).andReturn();
        var secondIds = objectMapper.readTree(secondRecommendations.getResponse().getContentAsString())
                .path("articles").findValuesAsText("id");
        assertThat(secondIds).contains(String.valueOf(article.getId()));
    }

    @Test
    void registrationMigratesStrongGuestInterestsAndRotatesIdentity() throws Exception {
        String oldGuestCookie = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String guestId = guestId(oldGuestCookie);
        saveGuestInterest(guestId, "generative-ai", 1.4, Instant.now());
        Csrf csrf = csrf(null);

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .cookie(cookie(oldGuestCookie)).session(csrf.session()).header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Guest Member", "guest-migration@example.com", PASSWORD, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Guest-Migration-Status", "MIGRATED"))
                .andExpect(jsonPath("$.guestId").doesNotExist())
                .andReturn();

        User user = userRepository.findByEmail("guest-migration@example.com").orElseThrow();
        assertThat(user.getPreferenceList()).contains("AI_ML", "GENERATIVE_AI");
        assertThat(userInterestRepository.findByUserIdAndTopic(user.getId(), "generative-ai"))
                .get().extracting(UserTopicInterest::getScore).isEqualTo(1.4);
        assertThat(guestInterestRepository.findByGuestId(guestId)).isEmpty();
        assertThat(guestMigrationRepository.findById(guestId)).isPresent();
        String newGuestCookie = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("DAILYAI_GUEST="))
                .map(value -> value.substring(0, value.indexOf(';'))).findFirst().orElseThrow();
        assertThat(newGuestCookie).isNotEqualTo(oldGuestCookie);
    }

    @Test
    void loginMergesGuestDataWithExistingPreferencesAndIsIdempotent() throws Exception {
        MockHttpSession session = register("Existing Member", "existing-guest@example.com");
        User user = userRepository.findByEmail("existing-guest@example.com").orElseThrow();
        user.setPreferences("DEVELOPER_TOOLS");
        userRepository.save(user);

        String guestCookie = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String guestId = guestId(guestCookie);
        saveGuestInterest(guestId, "generative-ai", 2.5, Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS));
        Csrf csrf = csrf(session);
        MvcResult response = mockMvc.perform(post("/api/auth/login").cookie(cookie(guestCookie)).session(csrf.session())
                        .header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"existing-guest@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Guest-Migration-Status", "MIGRATED"))
                .andReturn();

        user = userRepository.findById(user.getId()).orElseThrow();
        assertThat(user.getPreferenceList()).contains("DEVELOPER_TOOLS", "AI_ML", "GENERATIVE_AI");
        double mergedScore = userInterestRepository.findByUserIdAndTopic(user.getId(), "generative-ai")
                .orElseThrow().getScore();
        assertThat(mergedScore).isBetween(2.0, 2.5);
        double beforeRetry = userInterestRepository.findByUserIdAndTopic(user.getId(), "generative-ai")
                .orElseThrow().getScore();
        assertThat(guestAccountMigrationService.migrate(user.getId(), guestId))
                .isEqualTo(GuestAccountMigrationService.Status.ALREADY_MIGRATED);
        assertThat(userInterestRepository.findByUserIdAndTopic(user.getId(), "generative-ai")
                .orElseThrow().getScore()).isEqualTo(beforeRetry);
        assertThat(response.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("DAILYAI_GUEST=")).findFirst()).isPresent();
    }

    @Test
    void loginWithoutGuestDataSucceedsAndRegistrationRollsBackOnForeignReplay() throws Exception {
        MockHttpSession session = register("No Guest Data", "no-guest-data@example.com");
        String emptyGuestCookie = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        Csrf csrf = csrf(session);
        mockMvc.perform(post("/api/auth/login").cookie(cookie(emptyGuestCookie)).session(csrf.session())
                        .header("X-XSRF-TOKEN", csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-guest-data@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Guest-Migration-Status", "NO_DATA"));

        User owner = userRepository.findByEmail("no-guest-data@example.com").orElseThrow();
        String replayCookie = guestCookie(mockMvc.perform(get("/api/guest/session")).andReturn());
        String replayId = guestId(replayCookie);
        saveGuestInterest(replayId, "generative-ai", 1.0, Instant.now());
        guestAccountMigrationService.migrate(owner.getId(), replayId);

        Csrf registrationCsrf = csrf(null);
        mockMvc.perform(post("/api/auth/register").cookie(cookie(replayCookie)).session(registrationCsrf.session())
                        .header("X-XSRF-TOKEN", registrationCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Replay Attempt", "replay-attempt@example.com", PASSWORD, PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.existsByEmailIgnoreCase("replay-attempt@example.com")).isFalse();
        assertThat(guestInterestRepository.findByGuestId(replayId)).isEmpty();
    }

    @Test
    void migrationRejectsAnotherAccountAndRollsBackBeforeDeletingGuestData() {
        User owner = userRepository.saveAndFlush(new User("Migration Owner", "migration-owner@example.com"));
        User other = userRepository.saveAndFlush(new User("Other Owner", "other-owner@example.com"));
        String guestId = java.util.UUID.randomUUID().toString();
        saveGuestInterest(guestId, "machine-learning", 1.2, Instant.now());
        guestAccountMigrationService.migrate(owner.getId(), guestId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guestAccountMigrationService.migrate(other.getId(), guestId))
                .isInstanceOf(com.dailyai.assistant.exception.UnauthorizedException.class);
        assertThat(guestMigrationRepository.findById(guestId)).get()
                .extracting(value -> value.getUser().getId()).isEqualTo(owner.getId());
        assertThat(userInterestRepository.findByUserId(other.getId())).isEmpty();
    }

    private void saveGuestInterest(String guestId, String topic, double score, Instant updatedAt) {
        GuestTopicInterest interest = new GuestTopicInterest();
        interest.setGuestId(guestId);
        interest.setTopic(topic);
        interest.setScore(score);
        interest.setUpdatedAt(updatedAt);
        guestInterestRepository.saveAndFlush(interest);
    }

    private String guestId(String cookie) {
        return cookie.substring(cookie.indexOf('=') + 1);
    }

    private jakarta.servlet.http.Cookie cookie(String cookie) {
        String[] parts = cookie.split("=", 2);
        return new jakarta.servlet.http.Cookie(parts[0], parts[1]);
    }

    private String guestCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("DAILYAI_GUEST=", "HttpOnly", "Path=/", "Max-Age=31536000", "SameSite=Lax");
        return header.substring(0, header.indexOf(';'));
    }

    @Test
    void registrationValidatesInputAndPreventsDuplicateCaseInsensitiveEmail() throws Exception {
        Csrf first = csrf(null);
        mockMvc.perform(post("/api/auth/register")
                        .session(first.session())
                        .header("X-XSRF-TOKEN", first.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("First User", "Duplicate@Example.com", PASSWORD, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("duplicate@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        Csrf second = csrf(null);
        mockMvc.perform(post("/api/auth/register")
                        .session(second.session())
                        .header("X-XSRF-TOKEN", second.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Second User", "duplicate@example.com", PASSWORD, PASSWORD)))
                .andExpect(status().isConflict());

        Csrf weak = csrf(null);
        mockMvc.perform(post("/api/auth/register")
                        .session(weak.session())
                        .header("X-XSRF-TOKEN", weak.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Weak User", "weak@example.com", "short", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").exists());

        Csrf invalidEmail = csrf(null);
        mockMvc.perform(post("/api/auth/register")
                        .session(invalidEmail.session())
                        .header("X-XSRF-TOKEN", invalidEmail.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Invalid User", "not-an-email", PASSWORD, PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginLogoutAndSessionRestorationWork() throws Exception {
        MockHttpSession session = register("Session User", "session@example.com");

        // Session context is persisted and can be read on a later request.
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("session@example.com"));

        Csrf logoutCsrf = csrf(session);
        mockMvc.perform(post("/api/auth/logout")
                        .session(session)
                        .header("X-XSRF-TOKEN", logoutCsrf.token()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("DAILYAI_SESSION="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Path=/"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))));

        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

        Csrf loginCsrf = csrf(null);
        String preLoginSessionId = loginCsrf.session().getId();
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .session(loginCsrf.session())
                        .header("X-XSRF-TOKEN", loginCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"SESSION@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("session@example.com"))
                .andReturn();
        MockHttpSession authenticated = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(authenticated.getId()).isNotEqualTo(preLoginSessionId);
    }

    @Test
    void invalidCredentialsAreGenericAndRepeatedFailuresAreThrottled() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Csrf csrf = csrf(null);
            mockMvc.perform(post("/api/auth/login")
                            .session(csrf.session())
                            .header("X-XSRF-TOKEN", csrf.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"throttle@example.com\",\"password\":\"WrongPass1!\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("Invalid email or password"));
        }

        Csrf blocked = csrf(null);
        mockMvc.perform(post("/api/auth/login")
                        .session(blocked.session())
                        .header("X-XSRF-TOKEN", blocked.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"throttle@example.com\",\"password\":\"WrongPass1!\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void csrfIsRequiredForCookieAuthenticatedMutations() throws Exception {
        MockHttpSession session = register("Csrf User", "csrf@example.com");

        mockMvc.perform(put("/api/profile")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCannotReadOrModifyAnotherUsersRecordsById() throws Exception {
        MockHttpSession alice = register("Alice", "alice@example.com");
        MockHttpSession bob = register("Bob", "bob@example.com");

        Csrf aliceCsrf = csrf(alice);
        MvcResult added = mockMvc.perform(post("/api/bookmarks")
                        .session(alice)
                        .header("X-XSRF-TOKEN", aliceCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"ARTICLE\",\"itemId\":" + article.getId() + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        long bookmarkId = objectMapper.readTree(added.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/bookmarks").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/bookmarks").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Csrf bobCsrf = csrf(bob);
        mockMvc.perform(delete("/api/bookmarks/" + bookmarkId)
                        .session(bob)
                        .header("X-XSRF-TOKEN", bobCsrf.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/bookmarks").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        Csrf aliceUpdate = csrf(alice);
        mockMvc.perform(put("/api/profile")
                        .session(alice)
                        .header("X-XSRF-TOKEN", aliceUpdate.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Updated"));

        mockMvc.perform(get("/api/profile").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bob"));
    }

    @Test
    void learningProgressAndDailyTasksAreIsolatedByOwner() throws Exception {
        MockHttpSession alice = register("Alice Learn", "alice.learn@example.com");
        MockHttpSession bob = register("Bob Learn", "bob.learn@example.com");

        Csrf aliceCsrf = csrf(alice);
        mockMvc.perform(post("/api/learning/progress")
                        .session(alice)
                        .header("X-XSRF-TOKEN", aliceCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":" + topic.getId() + ",\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/learning/progress").session(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/learning/progress").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        Csrf taskCsrf = csrf(alice);
        mockMvc.perform(post("/api/learning/daily-plan/" + task.getId())
                        .session(alice)
                        .header("X-XSRF-TOKEN", taskCsrf.token())
                        .param("completed", "true"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/learning/daily-plan").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].tasks[0].completedInPlan").value(false));
    }

    @Test
    void progressJournalRequiresAuthenticationAndEntriesAreIsolatedByOwnerAndDate() throws Exception {
        MockHttpSession alice = register("Alice Journal", "alice.journal@example.com");
        MockHttpSession bob = register("Bob Journal", "bob.journal@example.com");
        String date = "2026-11-20";

        Csrf aliceCsrf = csrf(alice);
        mockMvc.perform(put("/api/progress/entries/" + date)
                        .session(alice)
                        .header("X-XSRF-TOKEN", aliceCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"learned\":\"Built a REST endpoint\",\"practiced\":\"Wrote tests\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned").value("Built a REST endpoint"));

        Csrf updateCsrf = csrf(alice);
        mockMvc.perform(put("/api/progress/entries/" + date)
                        .session(alice)
                        .header("X-XSRF-TOKEN", updateCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"learned\":\"Updated learning note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned").value("Updated learning note"));

        mockMvc.perform(get("/api/progress/entries").session(alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/progress/entries").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        Csrf uploadCsrf = csrf(alice);
        MockMultipartFile notes = new MockMultipartFile("file", "practice.txt", "text/plain",
                "Practiced Java collections".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/progress/entries/" + date + "/attachment")
                        .file(notes).session(alice).header("X-XSRF-TOKEN", uploadCsrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentText").value("Practiced Java collections"));

        Csrf bobCsrf = csrf(bob);
        mockMvc.perform(put("/api/progress/entries/" + date)
                        .session(bob)
                        .header("X-XSRF-TOKEN", bobCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"learned\":\"Separate private note\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/progress/entries").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].learned").value("Separate private note"));
    }

    @Test
    void careerSettingsArePersistedOnlyForTheirAccount() throws Exception {
        MockHttpSession alice = register("Alice Career", "alice.career@example.com");
        MockHttpSession bob = register("Bob Career", "bob.career@example.com");
        Csrf aliceCsrf = csrf(alice);
        mockMvc.perform(put("/api/profile/career")
                        .session(alice)
                        .header("X-XSRF-TOKEN", aliceCsrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"careerGoal\":\"Prepare for backend work\",\"targetRoles\":\"Java developer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careerGoal").value("Prepare for backend work"))
                .andExpect(jsonPath("$.targetRoles").value("Java developer"));
        mockMvc.perform(get("/api/profile").session(bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.careerGoal").value(""))
                .andExpect(jsonPath("$.targetRoles").value(""));
    }

    @Test
    void personalPlanRequiresAuthenticationAndIsScopedToItsOwner() throws Exception {
        mockMvc.perform(get("/api/learning/personal-plan/today"))
                .andExpect(status().isUnauthorized());
        MockHttpSession alice = register("Alice Plan", "alice.plan@example.com");
        MockHttpSession bob = register("Bob Plan", "bob.plan@example.com");
        Csrf aliceCsrf = csrf(alice);
        MvcResult generated = mockMvc.perform(post("/api/learning/personal-plan/today").session(alice)
                        .header("X-XSRF-TOKEN", aliceCsrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].section").value("LEARN"))
                .andExpect(jsonPath("$[0].rationale").isNotEmpty())
                .andExpect(jsonPath("$[0].dueDate").isNotEmpty()).andReturn();
        long aliceItemId = objectMapper.readTree(generated.getResponse().getContentAsString()).get(0).get("id").asLong();
        mockMvc.perform(get("/api/learning/personal-plan/today").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/learning/personal-plan/history").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        Csrf bobCsrf = csrf(bob);
        mockMvc.perform(patch("/api/learning/personal-plan/" + aliceItemId + "/status").session(bob)
                        .header("X-XSRF-TOKEN", bobCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void newsRegionAndPublicationPeriodFiltersAreAccepted() throws Exception {
        mockMvc.perform(get("/api/news").param("region", "INDIA").param("period", "today"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/news/search").param("q", "AI").param("region", "GLOBAL").param("period", "week"))
                .andExpect(status().isOk());
    }

    @Test
    void readingHistoryIsRecordedOnlyForTheAuthenticatedReader() throws Exception {
        MockHttpSession alice = register("Alice History", "alice.history@example.com");
        MockHttpSession bob = register("Bob History", "bob.history@example.com");

        mockMvc.perform(get("/api/news/" + article.getId()).session(alice))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/profile").session(alice))
                .andExpect(jsonPath("$.articlesRead").value(1));
        mockMvc.perform(get("/api/profile").session(bob))
                .andExpect(jsonPath("$.articlesRead").value(0));
    }

    @Test
    void adminEndpointsRequireAdminRoleOrAdminKey() throws Exception {
        when(adminService.refreshNews()).thenReturn(new RefreshResultDto(
                Instant.now(), 0, 0, 0, 0, 0, 0, 0, List.of()));
        MockHttpSession user = register("Normal User", "normal.admin@example.com");

        Csrf csrf = csrf(user);
        mockMvc.perform(post("/api/admin/news/refresh")
                        .session(user)
                        .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/news/refresh")
                        .header("X-Admin-Key", "test-admin-key"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/news/refresh")
                        .header("X-Admin-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession register(String name, String email) throws Exception {
        Csrf csrf = csrf(null);
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .session(csrf.session())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(name, email, PASSWORD, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var builder = get("/api/auth/csrf");
        if (session != null) {
            builder.session(session);
        }
        MvcResult result = mockMvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession used = (MockHttpSession) result.getRequest().getSession(false);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Csrf(used, body.get("token").asText());
    }

    private String registerJson(String name, String email, String password, String confirmPassword) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\""
                + password + "\",\"confirmPassword\":\"" + confirmPassword + "\"}";
    }

    private record Csrf(MockHttpSession session, String token) {
    }
}
