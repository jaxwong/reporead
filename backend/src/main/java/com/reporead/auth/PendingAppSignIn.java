package com.reporead.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The app's sign-in while GitHub is asked: the PKCE challenge that /app/sign-in keeps in the browser session it opens,
 * until the OAuth callback issues the app's code and ends that session. AppAuthController starts it, GitHubSecurity
 * lets only such a session start a GitHub login, and AppSignInSuccess finishes it.
 */
final class PendingAppSignIn {
    static final String CHALLENGE_ATTRIBUTE = "reporead.appCodeChallenge";

    private PendingAppSignIn() {}

    /**
     * The sign-in scheme of the one app that uses this server: the release app's on an HTTPS origin, the debug app's on
     * the Mac's loopback address (GitHubSecurity.callbackUri allows only those two). A server never sends a code to the
     * other app's scheme, which any app could register on a phone without that app.
     */
    static String appScheme(String publicUrl) {
        return publicUrl.startsWith("https://") ? "reporead" : "reporead-dev";
    }

    /**
     * Opens the browser session that holds it. Tomcat refuses a session beyond GitHubSecurity.MAX_SIGN_IN_SESSIONS with
     * TooManyActiveSessionsException.
     */
    static void start(HttpServletRequest request, String codeChallenge) {
        request.getSession(true).setAttribute(CHALLENGE_ATTRIBUTE, codeChallenge);
    }

    static boolean isPending(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(CHALLENGE_ATTRIBUTE) instanceof String;
    }

    /**
     * Ends the browser session and returns the challenge the app started it with. A GitHub login can only start from a
     * pending sign-in (isPending), so a callback without its challenge is an invariant failure.
     */
    static String finish(HttpServletRequest request) {
        var session = request.getSession(false);
        Object challenge = session == null ? null : session.getAttribute(CHALLENGE_ATTRIBUTE);
        // The browser session has done its job; only the app's bearer session may call /api.
        if (session != null) session.invalidate();
        if (!(challenge instanceof String codeChallenge)) {
            throw new IllegalStateException(
                    "GitHub sign-in completed without the app's PKCE challenge in its browser session");
        }
        return codeChallenge;
    }
}
