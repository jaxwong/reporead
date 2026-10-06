package com.reporead.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;

@RestController
public class AuthController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private final OAuth2AuthorizedClientService clients;
    private final GitHubRepositoryAccess repositories;

    public AuthController(OAuth2AuthorizedClientService clients, GitHubRepositoryAccess repositories) {
        this.clients = clients;
        this.repositories = repositories;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    String home() {
        return "<h1>RepoRead Stage 0</h1><p>Local GitHub user sign-in proof; not the Android login flow.</p>"
            + "<a href='/oauth2/authorization/github'>Sign in with GitHub</a>";
    }

    @GetMapping("/api/auth/me")
    Identity identity(@AuthenticationPrincipal OAuth2User user) {
        Object id = user.getAttribute("id");
        Object login = user.getAttribute("login");
        if (!(id instanceof Number number) || number.longValue() <= 0 || !(login instanceof String name) || name.isBlank()) {
            throw new IllegalStateException("GitHub identity requires a positive numeric id and nonblank login at /api/auth/me");
        }
        return new Identity(number.longValue(), name);
    }

    @GetMapping("/api/auth/installations/{installationId}/repositories")
    GitHubRepositoryAccess.AccessibleRepositories repositories(@PathVariable long installationId,
                                                              OAuth2AuthenticationToken authentication) {
        if (installationId <= 0) throw new GitHubRepositoryAccess.Failure(HttpStatus.BAD_REQUEST,
            "INVALID_INSTALLATION_ID", "installationId must be a positive integer.");
        OAuth2AuthorizedClient client = clients.loadAuthorizedClient(authentication.getAuthorizedClientRegistrationId(), authentication.getName());
        if (client == null || (client.getAccessToken().getExpiresAt() != null &&
            !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))) {
            throw new GitHubRepositoryAccess.Failure(HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED", "No current GitHub user token is available; sign in again.");
        }
        LOG.info("Checking GitHub repository eligibility; userId={} installationId={}", authentication.getName(), installationId);
        return repositories.fetch(installationId, client.getAccessToken().getTokenValue());
    }

    @ExceptionHandler(GitHubRepositoryAccess.Failure.class)
    ResponseEntity<AccessError> accessFailure(GitHubRepositoryAccess.Failure failure, HttpServletRequest request) {
        LOG.warn("GitHub repository eligibility failed; path={} code={}", request.getRequestURI(), failure.code);
        return ResponseEntity.status(failure.status).body(new AccessError(failure.code, failure.getMessage()));
    }

    @GetMapping(value = "/auth/failed", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    String failed() {
        return "<h1>Sign-in failed</h1><p>No authenticated result was saved. Check the registered callback and try a new sign-in.</p>"
            + "<a href='/oauth2/authorization/github'>Start a new sign-in</a>";
    }

    record Identity(long id, String login) {}
    record AccessError(String code, String message) {}
}
