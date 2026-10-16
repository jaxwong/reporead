package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each user's GitHub App user token from sign-in to sign-out: the current token, renewed when it is about to expire,
 * and its revocation and removal when the user signs out of their last device or deletes the account (ADR-03).
 * GitHubTokens stores it encrypted; GitHubApi only uses it.
 */
@Component
public class GitHubCredentials {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubCredentials.class);
    private static final String REGISTRATION = "github";
    private static final MediaType GITHUB_JSON = MediaType.parseMediaType("application/vnd.github+json");

    private final OAuth2AuthorizedClientService githubTokens;
    private final OAuth2AuthorizedClientManager manager;
    private final ClientRegistrationRepository registrations;
    private final RestTemplate githubUserApi;
    private final JsonMapper json;
    private final ConcurrentHashMap<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    public GitHubCredentials(
            OAuth2AuthorizedClientService githubTokens,
            ClientRegistrationRepository registrations,
            RestClientRefreshTokenTokenResponseClient githubRefreshTokens,
            @Qualifier("githubUserApi") RestTemplate githubUserApi,
            JsonMapper json) {
        this.githubTokens = githubTokens;
        this.registrations = registrations;
        this.githubUserApi = githubUserApi;
        this.json = json;
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, githubTokens);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refresh -> refresh.accessTokenResponseClient(githubRefreshTokens))
                .build());
        manager.setAuthorizationSuccessHandler((client, principal, attributes) -> {
            githubTokens.saveAuthorizedClient(client, principal);
            LOG.info(
                    "GitHub user token refreshed; githubUserId={} expiresAt={}",
                    principal.getName(),
                    client.getAccessToken().getExpiresAt());
        });
        // Replaces Spring's default, which removes the stored token on some refusals: githubUserToken alone decides
        // what a failed renewal means, and removes the token itself when GitHub refused it.
        manager.setAuthorizationFailureHandler((error, principal, attributes) -> {});
        this.manager = manager;
    }

    /**
     * The server-side GitHub user token saved by the OAuth callback. One expiring within a minute is first renewed with
     * one token-endpoint call. A user's renewals are serialized: GitHub rotates the refresh token on use, so a second
     * concurrent renewal with the old one would be refused. The lock is this process's, which is enough because the
     * backend is one process by design (ADR-14: one systemd unit on one fixed loopback port); a second instance would
     * need a row lock on the stored token instead.
     */
    public String githubUserToken(long githubUserId) {
        String principal = Long.toString(githubUserId);
        OAuth2AuthorizedClient client;
        synchronized (refreshLocks.computeIfAbsent(githubUserId, id -> new Object())) {
            try {
                client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                        .principal(principal)
                        .build());
            } catch (OAuth2AuthorizationException error) {
                Throwable unanswered = unanswered(error);
                if (unanswered != null) {
                    LOG.warn(
                            "GitHub token refresh failed; githubUserId={} failureType={}",
                            githubUserId,
                            unanswered.getClass().getSimpleName());
                    throw new ApiFailure(
                            Code.GITHUB_UNAVAILABLE, "GitHub could not renew your sign-in; nothing was changed.");
                }
                // A refused refresh token stays refused; dropping it makes later requests ask for sign-in without a
                // call.
                forget(githubUserId);
                LOG.warn(
                        "GitHub refused the token refresh; githubUserId={} error={}",
                        githubUserId,
                        error.getError().getErrorCode());
                throw new ApiFailure(Code.SIGN_IN_REQUIRED, "GitHub refused to renew your sign-in; sign in again.");
            }
        }
        // Without a refresh token the manager returns the expired client unchanged.
        if (client == null
                || (client.getAccessToken().getExpiresAt() != null
                        && !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))) {
            throw new ApiFailure(Code.SIGN_IN_REQUIRED, "No current GitHub user token is available; sign in again.");
        }
        return client.getAccessToken().getTokenValue();
    }

    /**
     * The failure behind a refresh GitHub never answered (network, timeout, 429, 5xx), which leaves the refresh token
     * usable by the next request; null when GitHub answered and refused. Spring nests it under its own OAuth2
     * exceptions.
     */
    private static Throwable unanswered(OAuth2AuthorizationException error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) return cause;
            if (cause instanceof RestClientResponseException response
                    && (response.getStatusCode().value() == 429
                            || response.getStatusCode().is5xxServerError())) return cause;
        }
        return null;
    }

    /**
     * Signing out of the last device: revokes RepoRead's authorization on GitHub, then deletes the stored token, so a
     * signed-out user leaves no working credential. At most 2 GitHub calls; a failed revocation is logged and the token
     * is deleted anyway.
     */
    public void revokeAndForget(long githubUserId) {
        tokenToRevoke(githubUserId).ifPresent(token -> revoke(githubUserId, token));
        forget(githubUserId);
    }

    /**
     * The current token to revoke this user's authorization with ({@link #revoke}), renewed first when it expires
     * within a minute: at most one GitHub call. Empty when RepoRead holds no usable token or GitHub did not renew it.
     * Signing out and deleting the account must not depend on GitHub, so that is logged, never thrown.
     */
    public Optional<String> tokenToRevoke(long githubUserId) {
        try {
            return Optional.of(githubUserToken(githubUserId));
        } catch (ApiFailure failure) {
            // SIGN_IN_REQUIRED: no stored token, or GitHub refused to renew it, so RepoRead holds nothing usable.
            if (failure.code == Code.SIGN_IN_REQUIRED)
                LOG.info("GitHub authorization not revoked: no usable token; githubUserId={}", githubUserId);
            else LOG.warn("GitHub authorization not revoked; githubUserId={} code={}", githubUserId, failure.code);
            return Optional.empty();
        }
    }

    /**
     * Revokes RepoRead's authorization for this GitHub user on GitHub, so no token GitHub issued RepoRead for them
     * works any more ("Deleting an application's grant will also delete all OAuth tokens associated with the
     * application for the user", GitHub REST docs). Authenticated as the App, with a current {@code token} from
     * {@link #tokenToRevoke}: one GitHub call. A failure is logged, never thrown, and the caller deletes the stored
     * token either way; GitHub then expires the tokens on its own schedule.
     */
    public void revoke(long githubUserId, String token) {
        var app = registrations.findByRegistrationId(REGISTRATION);
        var uri = UriComponentsBuilder.fromUriString("https://api.github.com/applications/{clientId}/grant")
                .encode()
                .buildAndExpand(app.getClientId())
                .toUri();
        var request = RequestEntity.method(HttpMethod.DELETE, uri)
                .headers(headers -> headers.setBasicAuth(app.getClientId(), app.getClientSecret()))
                .accept(GITHUB_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsBytes(Map.of("access_token", token)));
        try {
            githubUserApi.exchange(request, byte[].class);
            LOG.info("GitHub authorization revoked; githubUserId={}", githubUserId);
        } catch (RestClientResponseException error) {
            LOG.warn(
                    "GitHub authorization not revoked; githubUserId={} status={}",
                    githubUserId,
                    error.getStatusCode().value());
        } catch (ResourceAccessException error) {
            LOG.warn(
                    "GitHub authorization not revoked; githubUserId={} failureType={}",
                    githubUserId,
                    (error.getCause() == null ? error : error.getCause())
                            .getClass()
                            .getSimpleName());
        }
    }

    /** Deletes the stored token; the user's next GitHub-backed request asks for sign-in. */
    public void forget(long githubUserId) {
        githubTokens.removeAuthorizedClient(REGISTRATION, Long.toString(githubUserId));
    }
}
