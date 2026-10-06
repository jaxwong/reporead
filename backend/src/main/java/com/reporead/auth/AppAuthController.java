package com.reporead.auth;

import com.reporead.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * Android sign-in: the app opens /app/sign-in in a Custom Tab with a PKCE S256 challenge, GitHub OAuth runs in that
 * browser session, and the callback redirects to reporead://auth with a single-use code that only the app's verifier
 * can exchange for a bearer session. See GitHubSecurity for the callback side.
 */
@RestController
public class AppAuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AppAuthController.class);
    static final String CHALLENGE_ATTRIBUTE = "reporead.appCodeChallenge";
    private final AppSessions sessions;

    public AppAuthController(AppSessions sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/app/sign-in")
    RedirectView signIn(@RequestParam("code_challenge") String codeChallenge, HttpServletRequest request) {
        if (!AppSessions.TOKEN.matcher(codeChallenge).matches()) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_CODE_CHALLENGE", "code_challenge must be a 43-character S256 PKCE challenge.");
        }
        request.getSession(true).setAttribute(CHALLENGE_ATTRIBUTE, codeChallenge);
        return new RedirectView("/oauth2/authorization/github");
    }

    record TokenRequest(String code, String codeVerifier) {}

    @PostMapping("/api/app-auth/token")
    AppSessions.IssuedSession token(@RequestBody TokenRequest body) {
        var issued = sessions.exchange(body.code(), body.codeVerifier()).orElseThrow(() ->
            new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_SIGN_IN_CODE", "The sign-in code is invalid, expired, already used, or does not match this app; sign in again."));
        LOG.info("App session issued; userId={}", issued.user().id());
        return issued;
    }

    @DeleteMapping("/api/app-auth/session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @AuthenticationPrincipal AppUser user) {
        sessions.revoke(authorization.substring("Bearer ".length()));
        LOG.info("App session revoked; userId={}", user.id());
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
