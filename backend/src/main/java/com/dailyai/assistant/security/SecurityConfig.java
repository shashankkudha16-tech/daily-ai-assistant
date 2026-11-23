package com.dailyai.assistant.security;

import com.dailyai.assistant.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(AppUserDetailsService userDetailsService,
                                                               PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Always use the same message, whether the email is unknown or the
        // password is wrong. The default provider already hides user-not-found.
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AdminKeyAuthenticationFilter adminKeyFilter,
                                                   SchedulerKeyAuthenticationFilter schedulerKeyFilter,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        HttpSessionCsrfTokenRepository csrfRepository = new HttpSessionCsrfTokenRepository();
        csrfRepository.setHeaderName("X-XSRF-TOKEN");

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        // The SPA reads the raw token from /api/auth/csrf and sends it
                        // as a header. Only stateless admin-key requests skip CSRF;
                        // cookie-authenticated admin accounts still need a token.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(SecurityConfig::isKeyAuthenticatedRequest))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // The browser UI is bundled into the same Spring Boot service in
                        // the single-service deployment. Keep only static UI files public;
                        // all application APIs retain their existing authentication rules.
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**").permitAll()
                        .requestMatchers("/error", "/api/auth/csrf", "/api/auth/login",
                                "/api/auth/register", "/api/auth/logout",
                                "/api/guest/session", "/api/guest/recommendations",
                                "/api/guest/interactions", "/api/guest/search", "/api/guest/personalization",
                                "/api/guest/feedback",
                                "/api/guest/feedback/**",
                                "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/news/**", "/api/categories/**", "/api/tools/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Machine-only endpoints: reachable only with a valid X-Scheduler-Key.
                        .requestMatchers("/api/scheduler/**").hasRole("SCHEDULER")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .addFilterBefore(adminKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(schedulerKeyFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Stateless key requests do not carry a CSRF token. Admin-key calls skip CSRF
     * when their header is present; scheduler calls are always CSRF-exempt because
     * they are unreachable with a session (they require ROLE_SCHEDULER, which only
     * the scheduler key filter grants).
     */
    private static boolean isKeyAuthenticatedRequest(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/scheduler/")) {
            return true;
        }
        return path.startsWith("/api/admin/")
                && request.getHeader(AdminKeyAuthenticationFilter.HEADER) != null;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "Authorization", "X-XSRF-TOKEN",
                AdminKeyAuthenticationFilter.HEADER, SchedulerKeyAuthenticationFilter.HEADER));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(List.of("X-Guest-Migration-Status"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
