package com.reporead.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

/**
 * Completes the browser half of app sign-in: saves the user, issues a single-use code bound to the app's PKCE
 * challenge, ends the browser session, and redirects to the app. A login not started by /app/sign-in gets no code.
 */
final class AppSignInSuccess implements AuthenticationSuccessHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AppSignInSuccess.class);
    static final String APP_REDIRECT = "reporead://auth?code=";
    private final AppSessions sessions;

    AppSignInSuccess(AppSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
        throws IOException {
        var session = request.getSession(false);
        Object challenge = session == null ? null : session.getAttribute(AppAuthController.CHALLENGE_ATTRIBUTE);
        // The browser session has done its job; only the app's bearer session may call /api.
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        if (!(challenge instanceof String codeChallenge)) {
            LOG.warn("GitHub sign-in completed without an app challenge; no app code issued");
            response.sendRedirect("/auth/failed");
            return;
        }
        var user = (OAuth2User) authentication.getPrincipal();
        Object id = user.getAttribute("id");
        Object login = user.getAttribute("login");
        if (!(id instanceof Number number) || number.longValue() <= 0 || !(login instanceof String name) || name.isBlank()) {
            throw new IllegalStateException("GitHub identity requires a positive numeric id and nonblank login in the OAuth callback");
        }
        long userId = sessions.saveUser(number.longValue(), name);
        String code = sessions.issueSignInCode(userId, codeChallenge);
        LOG.info("GitHub sign-in completed; userId={} app code issued", userId);
        response.sendRedirect(APP_REDIRECT + code);
    }
}
