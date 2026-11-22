package com.dailyai.assistant.security;

import com.dailyai.assistant.exception.UnauthorizedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/** Resolves the opaque guest bearer identifier only from its HttpOnly cookie. */
public final class GuestIdentity {
    public static final String COOKIE_NAME = "DAILYAI_GUEST";
    private static final Pattern UUID_COOKIE = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private GuestIdentity() { }

    public static String findGuestId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null
                    && UUID_COOKIE.matcher(cookie.getValue()).matches()) {
                return cookie.getValue().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    public static String requireGuestId(HttpServletRequest request) {
        String id = findGuestId(request);
        if (id == null) throw new UnauthorizedException("A guest session is required. Refresh the page and try again.");
        return id;
    }
}
