package com.reporead.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * The app's sign-in while GitHub is asked: the PKCE challenge and app scheme that /app/sign-in keeps in the browser
 * session it opens, until the OAuth callback issues the app's code and ends that session. AppAuthController starts it,
 * GitHubSecurity lets only such a session start a GitHub login, and AppSignInSuccess finishes it.
 */
final class PendingAppSignIn {
    /**
     * The schemes the apps register for the sign-in redirect: the release app's, and the debug app's, installed beside
     * it as a separate app. Only these, so a sign-in code is never handed to whatever else registered a scheme.
     */
    static final Set<String> APP_SCHEMES = Set.of("reporead", "reporead-dev");

    static final String CHALLENGE_ATTRIBUTE = "reporead.appCodeChallenge";
    static final String SCHEME_ATTRIBUTE = "reporead.appScheme";

    record Started(String codeChallenge, String appScheme) {}

    private PendingAppSignIn() {}

    /**
     * Opens the browser session that holds it. Tomcat refuses a session beyond GitHubSecurity.MAX_SIGN_IN_SESSIONS with
     * TooManyActiveSessionsException.
     */
    static void start(HttpServletRequest request, String codeChallenge, String appScheme) {
        var session = request.getSession(true);
        session.setAttribute(CHALLENGE_ATTRIBUTE, codeChallenge);
        session.setAttribute(SCHEME_ATTRIBUTE, appScheme);
    }

    static boolean isPending(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(CHALLENGE_ATTRIBUTE) instanceof String;
    }

    /**
     * Ends the browser session and returns what the app started it with. A GitHub login can only start from a pending
     * sign-in (isPending), so a callback without its challenge or a known scheme is an invariant failure.
     */
    static Started finish(HttpServletRequest request) {
        var session = request.getSession(false);
        Object challenge = session == null ? null : session.getAttribute(CHALLENGE_ATTRIBUTE);
        Object scheme = session == null ? null : session.getAttribute(SCHEME_ATTRIBUTE);
        // The browser session has done its job; only the app's bearer session may call /api.
        if (session != null) session.invalidate();
        if (!(challenge instanceof String codeChallenge)) {
            throw new IllegalStateException(
                    "GitHub sign-in completed without the app's PKCE challenge in its browser session");
        }
        if (!(scheme instanceof String appScheme) || !APP_SCHEMES.contains(appScheme)) {
            throw new IllegalStateException(
                    "GitHub sign-in completed without a known app scheme in its browser session; got " + scheme);
        }
        return new Started(codeChallenge, appScheme);
    }
}
