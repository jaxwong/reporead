package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.RepoReadProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Android sign-in: the app opens /app/sign-in in a Custom Tab with a PKCE S256 challenge and its own URL scheme, GitHub
 * OAuth runs in that browser session, and the callback redirects to <scheme>://auth with a single-use code that only
 * the app's verifier can exchange for a bearer session. See GitHubSecurity for the callback side.
 */
@RestController
public class AppAuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AppAuthController.class);
    private final AppSessions sessions;
    private final GitHubCredentials credentials;
    private final String appScheme;

    public AppAuthController(AppSessions sessions, GitHubCredentials credentials, RepoReadProperties properties) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.appScheme = PendingAppSignIn.appScheme(properties.publicUrl());
    }

    @GetMapping("/app/sign-in")
    RedirectView signIn(
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam("scheme") String scheme,
            HttpServletRequest request) {
        if (!AppSessions.TOKEN.matcher(codeChallenge).matches()) {
            throw new ApiFailure(
                    Code.INVALID_CODE_CHALLENGE, "code_challenge must be a 43-character S256 PKCE challenge.");
        }
        if (!scheme.equals(appScheme)) {
            throw new ApiFailure(
                    Code.INVALID_REQUEST,
                    "scheme must be " + appScheme + ", the scheme of the app this server signs in.");
        }
        try {
            PendingAppSignIn.start(request, codeChallenge);
        } catch (TooManyActiveSessionsException full) {
            LOG.warn("Sign-in refused: too many sign-ins in progress; limit={}", full.getMaxActiveSessions());
            throw new ApiFailure(Code.SIGN_IN_BUSY, "Too many sign-ins are in progress; try again in a few minutes.");
        }
        return new RedirectView("/oauth2/authorization/github");
    }

    /** {@code expectedUserId}: the account whose data the app holds, which only that account may sign in to. */
    record TokenRequest(String code, String codeVerifier, Long expectedUserId) {}

    @PostMapping("/api/app-auth/token")
    AppSessions.IssuedSession token(@RequestBody TokenRequest body) {
        var issued = sessions.exchange(body.code(), body.codeVerifier(), body.expectedUserId())
                .orElseThrow(
                        () -> new ApiFailure(
                                Code.INVALID_SIGN_IN_CODE,
                                "The sign-in code is invalid, expired, already used, or does not match this app; sign in again."));
        LOG.info("App session issued; userId={}", issued.user().id());
        return issued;
    }

    @DeleteMapping("/api/app-auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(Authentication authentication, @AuthenticationPrincipal AppUser user) {
        // BearerAuthentication, the API's only authenticator, keeps the session token as the credentials.
        int remaining = sessions.revoke((String) authentication.getCredentials(), user.id());
        // The stored GitHub token belongs to the GitHub user and serves all their devices. It goes with the last
        // session, revoked on GitHub and deleted here, so a signed-out user leaves no working credential without
        // signing their other devices out of GitHub. A device signing in at the same moment can lose its new token;
        // its next GitHub request then asks for sign-in.
        if (remaining == 0) credentials.revokeAndForget(user.githubUserId());
        LOG.info(
                "App session revoked; userId={} otherSessions={} githubTokenRemoved={}",
                user.id(),
                remaining,
                remaining == 0);
    }

    @GetMapping("/api/auth/me")
    AppUser me(@AuthenticationPrincipal AppUser user) {
        return user;
    }

    @GetMapping(value = "/auth/failed", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String failed() {
        return "<h1>Sign-in failed</h1><p>No session was created. Close this page, return to RepoRead, and sign in again.</p>";
    }
}
