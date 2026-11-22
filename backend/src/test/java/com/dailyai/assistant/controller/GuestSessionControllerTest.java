package com.dailyai.assistant.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GuestSessionControllerTest {

    @Test
    void secureDeploymentIssuesSecureHttpOnlyCookieWithConfiguredSameSite() {
        GuestSessionController controller = controller(true, "none");

        ResponseEntity<?> response = controller.establish(new MockHttpServletRequest());

        String cookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).contains("DAILYAI_GUEST=", "HttpOnly", "Secure", "SameSite=None", "Max-Age=31536000");
    }

    @Test
    void renewsValidCookieAndRotatesMalformedCookie() {
        GuestSessionController controller = controller(false, "lax");
        MockHttpServletRequest initial = new MockHttpServletRequest();
        String first = cookieValue(controller.establish(initial).getHeaders().getFirst("Set-Cookie"));

        MockHttpServletRequest valid = new MockHttpServletRequest();
        valid.setCookies(new Cookie(GuestSessionController.COOKIE_NAME, first));
        String renewed = cookieValue(controller.establish(valid).getHeaders().getFirst("Set-Cookie"));
        assertThat(renewed).isEqualTo(first);

        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.setCookies(new Cookie(GuestSessionController.COOKIE_NAME, "not-an-id"));
        String rotated = cookieValue(controller.establish(malformed).getHeaders().getFirst("Set-Cookie"));
        assertThat(rotated).isNotEqualTo(first);
    }

    @Test
    void secureForwardedRequestGetsSecureCookie() {
        GuestSessionController controller = controller(false, "lax");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);

        assertThat(controller.establish(request).getHeaders().getFirst("Set-Cookie"))
                .contains("; Secure;");
    }

    private String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private GuestSessionController controller(boolean secure, String sameSite) {
        return new GuestSessionController(secure, sameSite,
                org.mockito.Mockito.mock(com.dailyai.assistant.service.GuestPersonalizationService.class),
                org.mockito.Mockito.mock(com.dailyai.assistant.service.GuestAccountMigrationService.class));
    }
}
