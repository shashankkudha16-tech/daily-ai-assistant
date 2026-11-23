package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.GuestInteractionRequest;
import com.dailyai.assistant.dto.GuestFeedbackRequest;
import com.dailyai.assistant.dto.GuestRecommendationsDto;
import com.dailyai.assistant.dto.GuestSearchRequest;
import com.dailyai.assistant.security.GuestIdentity;
import com.dailyai.assistant.service.GuestPersonalizationService;
import com.dailyai.assistant.service.GuestAccountMigrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Issues the persistent guest cookie and exposes cookie-scoped personalization actions. */
@RestController
@RequestMapping("/api/guest")
public class GuestSessionController {

    static final String COOKIE_NAME = GuestIdentity.COOKIE_NAME;
    static final Duration COOKIE_AGE = Duration.ofDays(365);

    private final boolean secure;
    private final String sameSite;
    private final GuestPersonalizationService personalizationService;
    private final GuestAccountMigrationService migrationService;

    public GuestSessionController(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite,
            GuestPersonalizationService personalizationService,
            GuestAccountMigrationService migrationService) {
        this.secure = secure;
        this.sameSite = sameSite;
        this.personalizationService = personalizationService;
        this.migrationService = migrationService;
    }

    @GetMapping("/session")
    public ResponseEntity<Map<String, Boolean>> establish(HttpServletRequest request) {
        String guestId = GuestIdentity.findGuestId(request);
        if (guestId == null || migrationService.isRetired(guestId)) guestId = UUID.randomUUID().toString();
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(COOKIE_NAME, guestId)
                .path("/")
                .httpOnly(true)
                .secure(secure || request.isSecure())
                .sameSite(AuthController.normalizeSameSite(sameSite))
                .maxAge(COOKIE_AGE);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.build().toString())
                .body(Map.of("guest", true));
    }

    @PostMapping("/recommendations")
    public GuestRecommendationsDto recommendations(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "12") int limit) {
        return personalizationService.recommendations(GuestIdentity.requireGuestId(request), limit);
    }

    @PostMapping("/interactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void interaction(@Valid @RequestBody GuestInteractionRequest interaction, HttpServletRequest request) {
        personalizationService.recordInteraction(GuestIdentity.requireGuestId(request), interaction);
    }

    @PatchMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@Valid @RequestBody GuestFeedbackRequest feedback, HttpServletRequest request) {
        personalizationService.recordFeedback(GuestIdentity.requireGuestId(request),
                feedback.articleId(), feedback.action());
    }

    @GetMapping("/feedback/{articleId}")
    public Map<String, String> feedback(@PathVariable Long articleId,
                                        HttpServletRequest request) {
        String action = personalizationService.feedback(GuestIdentity.requireGuestId(request), articleId);
        return action == null ? Map.of() : Map.of("action", action);
    }

    @PostMapping("/search")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void search(@Valid @RequestBody GuestSearchRequest query, HttpServletRequest request) {
        personalizationService.recordSearch(GuestIdentity.requireGuestId(request), query.query());
    }

    @DeleteMapping("/personalization")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(HttpServletRequest request) {
        personalizationService.reset(GuestIdentity.requireGuestId(request));
    }
}
