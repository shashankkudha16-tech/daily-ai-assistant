package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.AuthUserDto;
import com.dailyai.assistant.dto.LoginRequest;
import com.dailyai.assistant.dto.RegisterRequest;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.exception.TooManyRequestsException;
import com.dailyai.assistant.exception.UnauthorizedException;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.security.AppUserPrincipal;
import com.dailyai.assistant.security.LoginAttemptService;
import com.dailyai.assistant.security.GuestIdentity;
import com.dailyai.assistant.service.AuthService;
import com.dailyai.assistant.service.CurrentUserService;
import com.dailyai.assistant.service.GuestAccountMigrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String SESSION_COOKIE = "DAILYAI_SESSION";

    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttemptService;
    private final GuestAccountMigrationService guestAccountMigrationService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final boolean sessionCookieSecure;
    private final boolean registrationEnabled;
    private final String sessionCookieSameSite;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthService authService,
                          CurrentUserService currentUserService,
                          AuthenticationManager authenticationManager,
                          LoginAttemptService loginAttemptService,
                          GuestAccountMigrationService guestAccountMigrationService,
                          SessionAuthenticationStrategy sessionAuthenticationStrategy,
                          @Value("${server.servlet.session.cookie.secure:false}") boolean sessionCookieSecure,
                          @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled,
                          @Value("${server.servlet.session.cookie.same-site:lax}") String sessionCookieSameSite) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.authenticationManager = authenticationManager;
        this.loginAttemptService = loginAttemptService;
        this.guestAccountMigrationService = guestAccountMigrationService;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.sessionCookieSecure = sessionCookieSecure;
        this.registrationEnabled = registrationEnabled;
        this.sessionCookieSameSite = sessionCookieSameSite;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken(), "headerName", csrfToken.getHeaderName());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<AuthUserDto> register(@Valid @RequestBody RegisterRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        if (!registrationEnabled) {
            throw new ResourceNotFoundException("Registration is disabled.");
        }
        String guestId = GuestIdentity.findGuestId(httpRequest);
        AuthService.RegistrationResult registration = authService.registerWithGuest(request, guestId);
        User user = registration.user();
        GuestAccountMigrationService.Status migrationStatus = registration.migrationStatus();
        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        establishSession(authentication, httpRequest, httpResponse);
        rotateGuestCookieAfterSuccess(guestId, migrationStatus, httpRequest, httpResponse);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Guest-Migration-Status", migrationStatus.name())
                .body(authService.toDto(user));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthUserDto> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        String email = AuthService.normalizeEmail(request.email());
        String clientAddress = httpRequest.getRemoteAddr();
        if (loginAttemptService.isBlocked(email, clientAddress)) {
            throw new TooManyRequestsException("Too many login attempts. Please try again later.");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        } catch (AuthenticationException ex) {
            loginAttemptService.recordFailure(email, clientAddress);
            // Deliberately identical for unknown email, wrong password and disabled
            // accounts so the response cannot be used to enumerate accounts.
            throw new UnauthorizedException("Invalid email or password");
        }

        String guestId = GuestIdentity.findGuestId(httpRequest);
        GuestAccountMigrationService.Status migrationStatus = GuestAccountMigrationService.Status.NO_GUEST;
        if (guestId != null) {
            AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
            try {
                migrationStatus = guestAccountMigrationService.migrate(principal.getId(), guestId);
            } catch (RuntimeException ex) {
                // Valid credentials still sign in if the optional guest merge is unavailable.
                migrationStatus = GuestAccountMigrationService.Status.FAILED;
            }
        }

        loginAttemptService.reset(email, clientAddress);
        establishSession(authentication, httpRequest, httpResponse);
        rotateGuestCookieAfterSuccess(guestId, migrationStatus, httpRequest, httpResponse);
        return ResponseEntity.ok()
                .header("X-Guest-Migration-Status", migrationStatus.name())
                .body(authService.toDto(currentUserService.getCurrentUser()));
    }

    private void rotateGuestCookieAfterSuccess(String guestId, GuestAccountMigrationService.Status status,
                                               HttpServletRequest request, HttpServletResponse response) {
        if (guestId == null || status == GuestAccountMigrationService.Status.FAILED) return;
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(GuestSessionController.COOKIE_NAME,
                        java.util.UUID.randomUUID().toString())
                .path("/")
                .httpOnly(true)
                .secure(sessionCookieSecure || request.isSecure())
                .maxAge(Duration.ofDays(365));
        String sameSite = normalizeSameSite(sessionCookieSameSite);
        if (sameSite != null) cookie.sameSite(sameSite);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        ResponseCookie expired = expiredSessionCookie(sessionCookieSecure, sessionCookieSameSite);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }

    /**
     * Expires the session cookie with the same attributes used to create it
     * (server.servlet.session.cookie.*), so every browser drops the session.
     */
    static ResponseCookie expiredSessionCookie(boolean secure, String sameSite) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(SESSION_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .maxAge(Duration.ZERO);
        String normalized = normalizeSameSite(sameSite);
        if (normalized != null) {
            cookie.sameSite(normalized);
        }
        return cookie.build();
    }

    /** Canonicalises SameSite so the logout cookie mirrors the session cookie. */
    static String normalizeSameSite(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> "Lax";
        };
    }

    @GetMapping("/me")
    public AuthUserDto me() {
        return authService.toDto(currentUserService.getCurrentUser());
    }

    private void establishSession(Authentication authentication, HttpServletRequest request,
                                  HttpServletResponse response) {
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
