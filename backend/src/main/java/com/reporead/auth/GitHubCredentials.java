// Owns credential lifecycle serialization across OAuth callback, renewal, session changes and grant revocation.

package com.reporead.auth;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.NestedExceptionUtils;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    /** GitHub's answer, and OAuth's, when the refresh token itself is no longer valid. */
    private static final Set<String> REFUSED_REFRESH_TOKEN = Set.of("bad_refresh_token", "invalid_grant");

    private final OAuth2AuthorizedClientService githubTokens;
    private final OAuth2AuthorizedClientManager manager;
    private final ClientRegistrationRepository registrations;
    private final RestTemplate githubUserApi;
    private final JsonMapper json;
    private final AppSessions sessions;
    // A callback's user is unknown before code exchange. One gate avoids a second identity/lock registry.
    private final Object lifecycle = new Object();

    /** A lifecycle operation, including the checked exceptions declared by the OAuth filter boundary. */
    @FunctionalInterface
    interface Change<T, E extends Exception> {
        T run() throws E;
    }

    /**
     * Serializes credential/session lifecycle before any user/repository transaction. All GitHub reads obtain their
     * token outside their publishing transaction, so deletion never waits for SQL held by a waiter on this gate.
     */
    <T, E extends Exception> T changing(Change<T, E> change) throws E {
        if (!Thread.holdsLock(lifecycle) && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Credential lifecycle must be entered before a database transaction");
        }
        synchronized (lifecycle) {
            return change.run();
        }
    }

    /** Rechecks admission after waiting: an earlier authenticated request cannot change a later account lifetime. */
    <T> T withSession(String token, AppUser user, Supplier<T> change) {
        return changing(() -> {
            sessions.authenticate(token)
                    .filter(current -> current.id() == user.id())
                    .orElseThrow(() -> new ApiFailure(Code.SIGN_IN_REQUIRED, "This app session is no longer current."));
            return change.get();
        });
    }

    /** Coordinates capture, committed account deletion and only then grant revocation under one admission. */
    public <T> T deletingAccount(String sessionToken, AppUser user, Supplier<T> delete) {
        return withSession(sessionToken, user, () -> {
            var token = tokenToRevoke(user.githubUserId());
            T deleted = delete.get();
            token.ifPresent(value -> revoke(user.githubUserId(), value));
            return deleted;
        });
    }

    /** AccountDeletion includes this in its existing transaction; no other layer may independently drop a token. */
    public void forgetForAccountDeletion(long githubUserId) {
        if (!Thread.holdsLock(lifecycle) || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Account credential removal requires lifecycle admission and its deletion transaction");
        }
        forget(githubUserId);
    }

    public GitHubCredentials(
            OAuth2AuthorizedClientService githubTokens,
            ClientRegistrationRepository registrations,
            RestClientRefreshTokenTokenResponseClient githubRefreshTokens,
            @Qualifier("githubUserApi") RestTemplate githubUserApi,
            JsonMapper json,
            AppSessions sessions) {
        this.githubTokens = githubTokens;
        this.registrations = registrations;
        this.githubUserApi = githubUserApi;
        this.json = json;
        this.sessions = sessions;
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
     * one token-endpoint call. Renewal shares the lifecycle gate with callbacks and removal, so an old renewal cannot
     * overwrite a new sign-in or resurrect a removed token. This is one process by design (ADR-14).
     */
    public String githubUserToken(long githubUserId) {
        return changing(() -> currentToken(githubUserId));
    }

    /** Called under the lifecycle gate; the manager's success handler writes the one encrypted token store. */
    private String currentToken(long githubUserId) {
        String principal = Long.toString(githubUserId);
        OAuth2AuthorizedClient client;
        try {
            client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION)
                    .principal(principal)
                    .build());
        } catch (OAuth2AuthorizationException error) {
            String refusal = GitHubSecurity.githubError(error);
            // Only GitHub refusing the refresh token itself makes it useless. Anything else (no answer, 429 or 5xx,
            // or a refusal of this server's own credentials) leaves it good for a later request.
            if (!REFUSED_REFRESH_TOKEN.contains(refusal)) {
                LOG.warn(
                        "GitHub token refresh failed; githubUserId={} error={} failureType={}",
                        githubUserId,
                        refusal,
                        NestedExceptionUtils.getMostSpecificCause(error)
                                .getClass()
                                .getSimpleName());
                throw new ApiFailure(
                        Code.GITHUB_UNAVAILABLE, "GitHub could not renew your sign-in; nothing was changed.");
            }
            // A refused refresh token stays refused; dropping it makes later requests ask for sign-in without a
            // call.
            forget(githubUserId);
            LOG.warn("GitHub refused the token refresh; githubUserId={} error={}", githubUserId, refusal);
            throw new ApiFailure(Code.SIGN_IN_REQUIRED, "GitHub refused to renew your sign-in; sign in again.");
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
     * Signing out of the last device: revokes RepoRead's authorization on GitHub, then deletes the stored token, so a
     * signed-out user leaves no working credential. At most 2 GitHub calls; a failed revocation is logged and the token
     * is deleted anyway.
     */
    void revokeAndForget(long githubUserId) {
        changing(() -> {
            tokenToRevoke(githubUserId).ifPresent(token -> revoke(githubUserId, token));
            forget(githubUserId);
            return null;
        });
    }

    /**
     * The current token to revoke this user's authorization with ({@link #revoke}), renewed first when it expires
     * within a minute: at most one GitHub call. Empty when RepoRead holds no usable token or GitHub did not renew it.
     * Signing out and deleting the account must not depend on GitHub, so that is logged, never thrown.
     */
    private Optional<String> tokenToRevoke(long githubUserId) {
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
    private void revoke(long githubUserId, String token) {
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
    private void forget(long githubUserId) {
        githubTokens.removeAuthorizedClient(REGISTRATION, Long.toString(githubUserId));
    }
}
