// Owns app-auth HTTP routes; AppSessions owns session facts and GitHubCredentials owns lifecycle serialization.

package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.RepoReadProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.apache.catalina.session.TooManyActiveSessionsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * Android sign-in: the app opens /app/sign-in in a Custom Tab with a PKCE S256 challenge, GitHub OAuth runs in that
 * browser session, and the callback redirects to this server's app (PendingAppSignIn.appRedirect) with a single-use
 * code that only the app's verifier can exchange for a bearer session. See GitHubSecurity for the callback side.
 */
@RestController
public class AppAuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AppAuthController.class);
    /** The release app, which static/.well-known/assetlinks.json names as the owner of the sign-in link. */
    private static final String RELEASE_APP = "com.reporead.android";

    private final AppSessions sessions;
    private final GitHubCredentials credentials;
    /** This server's host, from configuration rather than the request's Host header, as for the OAuth callback. */
    private final String host;

    public AppAuthController(AppSessions sessions, GitHubCredentials credentials, RepoReadProperties properties) {
        this.sessions = sessions;
        this.credentials = credentials;
        this.host = URI.create(properties.publicUrl()).getHost();
    }

    @GetMapping("/app/sign-in")
    RedirectView signIn(@RequestParam("code_challenge") String codeChallenge, HttpServletRequest request) {
        if (!AppSessions.TOKEN.matcher(codeChallenge).matches()) {
            throw new ApiFailure(
                    Code.INVALID_CODE_CHALLENGE, "code_challenge must be a 43-character S256 PKCE challenge.");
        }
        try {
            PendingAppSignIn.start(request, codeChallenge);
        } catch (TooManyActiveSessionsException full) {
            LOG.warn("Sign-in refused: too many sign-ins in progress; limit={}", full.getMaxActiveSessions());
            throw new ApiFailure(Code.SIGN_IN_BUSY, "Too many sign-ins are in progress; try again in a few minutes.");
        }
        return new RedirectView("/oauth2/authorization/github");
    }

    /** Owns the PKCE handoff request shape, not session issuance policy. */
    record TokenRequest(String code, String codeVerifier) {}

    @PostMapping("/api/app-auth/token")
    AppSessions.IssuedSession token(@RequestBody TokenRequest body) {
        var issued = credentials.changing(
                () -> sessions.exchange(body.code(), body.codeVerifier())
                        .orElseThrow(
                                () -> new ApiFailure(
                                        Code.INVALID_SIGN_IN_CODE,
                                        "The sign-in code is invalid, expired, already used, or does not match this app; sign in again.")));
        LOG.info("App session issued; userId={}", issued.user().id());
        return issued;
    }

    @DeleteMapping("/api/app-auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(Authentication authentication, @AuthenticationPrincipal AppUser user) {
        // BearerAuthentication, the API's only authenticator, keeps the session token as the credentials.
        credentials.withSession((String) authentication.getCredentials(), user, () -> {
            int remaining = sessions.revoke((String) authentication.getCredentials(), user.id());
            if (remaining == 0) credentials.revokeAndForget(user.githubUserId());
            LOG.info(
                    "App session revoked; userId={} otherSessions={} githubTokenRemoved={}",
                    user.id(),
                    remaining,
                    remaining == 0);
            return null;
        });
    }

    @GetMapping("/api/auth/me")
    AppUser me(@AuthenticationPrincipal AppUser user) {
        return user;
    }

    /**
     * Where sign-in ends on the hosted server. Android opens this verified link in the release app; a browser that
     * keeps it (Chrome may, after a redirect nobody tapped) shows this page instead. Its link names the app's package,
     * so tapping it opens that app, and only that app, with the code.
     */
    @GetMapping(value = PendingAppSignIn.SIGNED_IN, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> signedIn(@RequestParam("code") String code) {
        if (!AppSessions.TOKEN.matcher(code).matches()) {
            throw new ApiFailure(Code.INVALID_REQUEST, "code must be a RepoRead sign-in code.");
        }
        String app = "intent://" + host + PendingAppSignIn.SIGNED_IN + "?code=" + code + "#Intent;scheme=https;package="
                + RELEASE_APP + ";end";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'")
                .body("<!doctype html><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                        + "<title>RepoRead</title><p>Signed in to GitHub.</p><p><a href=\"" + app
                        + "\">Return to RepoRead</a></p>");
    }

    @GetMapping(value = "/auth/failed", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String failed() {
        return "<h1>Sign-in failed</h1><p>No session was created. Close this page, return to RepoRead, and sign in again.</p>";
    }
}
