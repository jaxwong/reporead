package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.github.GitHubApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    static final String CHALLENGE_ATTRIBUTE = "reporead.appCodeChallenge";
    static final String SCHEME_ATTRIBUTE = "reporead.appScheme";
    /**
     * The schemes the apps register for the sign-in redirect: the release app's, and the debug app's, installed beside
     * it as a separate app. Only these, so a sign-in code is never handed to whatever else registered a scheme.
     */
    static final Set<String> APP_SCHEMES = Set.of("reporead", "reporead-dev");

    private final AppSessions sessions;
    private final OAuth2AuthorizedClientService clients;
    private final GitHubApi github;

    public AppAuthController(AppSessions sessions, OAuth2AuthorizedClientService clients, GitHubApi github) {
        this.sessions = sessions;
        this.clients = clients;
        this.github = github;
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
        if (!APP_SCHEMES.contains(scheme)) {
            throw new ApiFailure(
                    Code.INVALID_REQUEST,
                    "scheme must be one of the RepoRead apps' schemes: "
                            + String.join(", ", APP_SCHEMES.stream().sorted().toList()) + ".");
        }
        try {
            var session = request.getSession(true);
            session.setAttribute(CHALLENGE_ATTRIBUTE, codeChallenge);
            session.setAttribute(SCHEME_ATTRIBUTE, scheme);
        } catch (TooManyActiveSessionsException full) {
            LOG.warn("Sign-in refused: too many sign-ins in progress; limit={}", full.getMaxActiveSessions());
            throw new ApiFailure(Code.SIGN_IN_BUSY, "Too many sign-ins are in progress; try again in a few minutes.");
        }
        return new RedirectView("/oauth2/authorization/github");
    }

    record TokenRequest(String code, String codeVerifier) {}

    @PostMapping("/api/app-auth/token")
    AppSessions.IssuedSession token(@RequestBody TokenRequest body) {
        var issued = sessions.exchange(body.code(), body.codeVerifier())
                .orElseThrow(
                        () -> new ApiFailure(
                                Code.INVALID_SIGN_IN_CODE,
                                "The sign-in code is invalid, expired, already used, or does not match this app; sign in again."));
        LOG.info("App session issued; userId={}", issued.user().id());
        return issued;
    }

    @DeleteMapping("/api/app-auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @AuthenticationPrincipal AppUser user) {
        int remaining = sessions.revoke(authorization.substring("Bearer ".length()), user.id());
        // The stored GitHub token belongs to the GitHub user and serves all their devices. It goes with the last
        // session, revoked on GitHub and deleted here, so a signed-out user leaves no working credential without
        // signing their other devices out of GitHub. A device signing in at the same moment can lose its new token;
        // its next GitHub request then asks for sign-in.
        if (remaining == 0) {
            github.tokenToRevoke(user.githubUserId())
                    .ifPresent(token -> github.revokeAuthorization(user.githubUserId(), token));
            clients.removeAuthorizedClient("github", Long.toString(user.githubUserId()));
        }
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
