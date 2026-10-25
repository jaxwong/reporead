package com.reporead.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The app's sign-in while GitHub is asked: the PKCE challenge that /app/sign-in keeps in the browser session it opens,
 * until the OAuth callback issues the app's code and ends that session. AppAuthController starts it,
 * GitHubBrowserSignIn lets only such a session start a GitHub login, and AppSignInSuccess finishes it.
 */
final class PendingAppSignIn {
    private static final String CHALLENGE_ATTRIBUTE = "reporead.appCodeChallenge";

    private PendingAppSignIn() {}

    /**
     * Where the one app that uses this server receives its code. On an HTTPS origin, the release app's verified App
     * Link: Android hands it only to the app signed with the key static/.well-known/assetlinks.json names, so no other
     * app can claim it. On the Mac's loopback address (GitHubBrowserSignIn.callbackUri allows only those two), the
     * debug app's own scheme, as a loopback address has no domain to verify.
     */
    static String appRedirect(String publicUrl) {
        return publicUrl.startsWith("https://") ? publicUrl + AppAuthController.SIGNED_IN_PATH : "reporead-dev://auth";
    }

    /**
     * Opens the browser session that holds it. Tomcat refuses a session beyond GitHubBrowserSignIn.MAX_SIGN_IN_SESSIONS
     * with TooManyActiveSessionsException.
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
