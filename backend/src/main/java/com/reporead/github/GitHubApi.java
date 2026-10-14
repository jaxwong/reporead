package com.reporead.github;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.auth.GitHubSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Every GitHub REST call RepoRead makes with a signed-in user's GitHub App user token.
 * Each method makes exactly one request: no retries, pagination, redirects, or installation-token fallback. Getting the
 * token may first refresh it (see userToken). revokeAuthorization alone authenticates as the App instead.
 * GitHub owns authorization: user-token endpoints only return repositories the user and installation can both access.
 */
@Component
public class GitHubApi {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubApi.class);
    static final int MAX_INSTALLATIONS = 10;
    static final int MAX_REPOSITORIES = 100;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final MediaType JSON = MediaType.parseMediaType("application/vnd.github+json");
    private static final MediaType RAW = MediaType.parseMediaType("application/vnd.github.raw+json");

    private final RestTemplate githubUserApi;
    private final RestTemplate githubTreeApi;
    private final RestTemplate githubImageApi;
    private final JsonMapper json;
    private final OAuth2AuthorizedClientService clients;
    private final OAuth2AuthorizedClientManager tokens;
    private final ClientRegistrationRepository registrations;
    private final ConcurrentHashMap<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    public GitHubApi(@Qualifier("githubUserApi") RestTemplate githubUserApi,
                     @Qualifier("githubTreeApi") RestTemplate githubTreeApi,
                     @Qualifier("githubImageApi") RestTemplate githubImageApi,
                     JsonMapper json, OAuth2AuthorizedClientService clients, OAuth2AuthorizedClientManager tokens,
                     ClientRegistrationRepository registrations) {
        this.githubUserApi = githubUserApi;
        this.githubTreeApi = githubTreeApi;
        this.githubImageApi = githubImageApi;
        this.json = json;
        this.clients = clients;
        this.tokens = tokens;
        this.registrations = registrations;
    }

    public record Repository(long id, long installationId, String owner, String name,
                             boolean privateRepository, String defaultBranch) {
        public String fullName() { return owner + "/" + name; }
    }
    public record Branch(String commitSha, String treeSha) {}
    public record TreeEntry(String path, String mode, String type, String sha) {}

    /**
     * The server-side GitHub user token saved by the OAuth callback. One expiring within a minute is first refreshed with
     * one token-endpoint call. A user's refreshes are serialized: GitHub rotates the refresh token on use, so a second
     * concurrent refresh with the old one would be refused. Tokens are stored encrypted (GitHubTokens) and survive restarts.
     */
    public String userToken(long githubUserId) {
        String principal = Long.toString(githubUserId);
        OAuth2AuthorizedClient client;
        synchronized (refreshLocks.computeIfAbsent(githubUserId, id -> new Object())) {
            try {
                client = tokens.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("github").principal(principal).build());
            } catch (OAuth2AuthorizationException error) {
                Throwable unanswered = unanswered(error);
                if (unanswered != null) {
                    LOG.warn("GitHub token refresh failed; githubUserId={} failureType={}", githubUserId, unanswered.getClass().getSimpleName());
                    throw new ApiFailure(Code.GITHUB_UNAVAILABLE, "GitHub could not renew your sign-in; nothing was changed.");
                }
                // A refused refresh token stays refused; dropping it makes later requests ask for sign-in without a call.
                clients.removeAuthorizedClient("github", principal);
                LOG.warn("GitHub refused the token refresh; githubUserId={} error={}", githubUserId, error.getError().getErrorCode());
                throw new ApiFailure(Code.SIGN_IN_REQUIRED, "GitHub refused to renew your sign-in; sign in again.");
            }
        }
        // Without a refresh token the manager returns the expired client unchanged.
        if (client == null || (client.getAccessToken().getExpiresAt() != null &&
            !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))) {
            throw new ApiFailure(Code.SIGN_IN_REQUIRED, "No current GitHub user token is available; sign in again.");
        }
        return client.getAccessToken().getTokenValue();
    }

    /**
     * The failure behind a refresh GitHub never answered (network, timeout, 429, 5xx), which leaves the refresh token
     * usable by the next request; null when GitHub answered and refused. Spring nests it under its own OAuth2 exceptions.
     */
    private static Throwable unanswered(OAuth2AuthorizationException error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) return cause;
            if (cause instanceof RestClientResponseException response &&
                (response.getStatusCode().value() == 429 || response.getStatusCode().is5xxServerError())) return cause;
        }
        return null;
    }

    /**
     * The current token to revoke this user's authorization with ({@link #revokeAuthorization}), renewed first when it
     * expires within a minute: at most one GitHub call. Empty when RepoRead holds no usable token or GitHub did not
     * renew it. Signing out and deleting the account must not depend on GitHub, so that is logged, never thrown.
     */
    public Optional<String> tokenToRevoke(long githubUserId) {
        try {
            return Optional.of(userToken(githubUserId));
        } catch (ApiFailure failure) {
            // SIGN_IN_REQUIRED: no stored token, or GitHub refused to renew it, so RepoRead holds nothing usable.
            if (failure.code == Code.SIGN_IN_REQUIRED) LOG.info("GitHub authorization not revoked: no usable token; githubUserId={}", githubUserId);
            else LOG.warn("GitHub authorization not revoked; githubUserId={} code={}", githubUserId, failure.code);
            return Optional.empty();
        }
    }

    /**
     * Revokes RepoRead's authorization for this GitHub user on GitHub, so no token GitHub issued RepoRead for them works
     * any more ("Deleting an application's grant will also delete all OAuth tokens associated with the application for
     * the user", GitHub REST docs). Authenticated as the App, with a current [token] from {@link #tokenToRevoke}: one
     * GitHub call. A failure is logged, never thrown, and the caller deletes the stored token either way; GitHub then
     * expires the tokens on its own schedule.
     */
    public void revokeAuthorization(long githubUserId, String token) {
        var app = registrations.findByRegistrationId("github");
        var request = RequestEntity.method(HttpMethod.DELETE, uri("/applications/{clientId}/grant", app.getClientId()))
            .headers(headers -> headers.setBasicAuth(app.getClientId(), app.getClientSecret()))
            .accept(JSON).contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsBytes(Map.of("access_token", token)));
        try {
            githubUserApi.exchange(request, byte[].class);
            LOG.info("GitHub authorization revoked; githubUserId={}", githubUserId);
        } catch (RestClientResponseException error) {
            LOG.warn("GitHub authorization not revoked; githubUserId={} status={}", githubUserId, error.getStatusCode().value());
        } catch (ResourceAccessException error) {
            LOG.warn("GitHub authorization not revoked; githubUserId={} failureType={}", githubUserId,
                (error.getCause() == null ? error : error.getCause()).getClass().getSimpleName());
        }
    }

    /** Installation IDs of this GitHub App that the user can access; a complete list of at most 10. */
    public List<Long> installations(String token) {
        var page = object(get(githubUserApi, uri("/user/installations?per_page=100"), token, JSON, GitHubApi::accessDenied), "installation");
        var items = completeList(page, "installations", MAX_INSTALLATIONS, Code.GITHUB_INSTALLATION_LIMIT,
            "RepoRead supports at most 10 accessible GitHub App installations; nothing was published.");
        var ids = new ArrayList<Long>();
        for (var installation : items) {
            long id = positiveLong(installation, "id", "installation");
            if (ids.contains(id)) throw invalid("GitHub returned a duplicate installation id.");
            ids.add(id);
        }
        LOG.info("GitHub installations listed; count={}", ids.size());
        return List.copyOf(ids);
    }

    /** Repositories in one installation that this user can access; a complete list of at most 100. */
    public List<Repository> repositories(String token, long installationId) {
        var page = object(get(githubUserApi, uri("/user/installations/" + installationId + "/repositories?per_page=" + MAX_REPOSITORIES),
            token, JSON, GitHubApi::accessDenied), "repository");
        var items = completeList(page, "repositories", MAX_REPOSITORIES, Code.GITHUB_REPOSITORY_LIMIT,
            "RepoRead requires a complete list of at most 100 repositories per installation; no next page was fetched.");
        var result = new ArrayList<Repository>();
        var ids = new HashSet<Long>();
        for (var repository : items) {
            long id = positiveLong(repository, "id", "repository");
            String fullName = string(repository, "full_name", "repository");
            var privateFlag = repository == null ? null : repository.get("private");
            if (privateFlag == null || !privateFlag.isBoolean()) throw invalid("GitHub repository metadata requires a private flag.");
            String branch = string(repository, "default_branch", "repository");
            String[] parts = fullName.split("/", -1);
            if (parts.length != 2 || !validName(parts[0]) || !validName(parts[1]) || !ids.add(id)) {
                throw invalid("GitHub repository metadata contains an invalid name or duplicate id.");
            }
            result.add(new Repository(id, installationId, parts[0], parts[1], privateFlag.booleanValue(), branch));
        }
        LOG.info("GitHub repositories listed; installationId={} count={}", installationId, result.size());
        return List.copyOf(result);
    }

    public Branch branch(String token, String owner, String name, String branch) {
        var body = object(get(githubUserApi, uri("/repos/{owner}/{name}/branches/{branch}", owner, name, branch), token, JSON,
            () -> new ApiFailure(Code.DEFAULT_BRANCH_NOT_FOUND,
                "The default branch was not found; the repository may be empty, renamed, or no longer accessible.")), "branch");
        var commit = body.get("commit");
        String commitSha = string(commit, "sha", "branch commit");
        var tree = commit.get("commit") == null ? null : commit.get("commit").get("tree");
        String treeSha = string(tree, "sha", "branch tree");
        if (!SHA.matcher(commitSha).matches() || !SHA.matcher(treeSha).matches()) throw invalid("GitHub returned an invalid commit or tree SHA.");
        return new Branch(commitSha, treeSha);
    }

    /** The full recursive tree. A truncated tree is incomplete and is never treated as the repository's contents. */
    public List<TreeEntry> completeTree(String token, String owner, String name, String treeSha) {
        var body = object(get(githubTreeApi, uri("/repos/{owner}/{name}/git/trees/{sha}?recursive=1", owner, name, treeSha), token, JSON,
            GitHubApi::accessDenied), "tree");
        var truncated = body.get("truncated");
        var entries = body.get("tree");
        if (truncated == null || !truncated.isBoolean() || entries == null || !entries.isArray()) {
            throw invalid("GitHub tree response requires truncated and tree.");
        }
        if (truncated.booleanValue()) {
            throw new ApiFailure(Code.GITHUB_TREE_INCOMPLETE, "GitHub returned a truncated repository tree; no documents were changed.");
        }
        var result = new ArrayList<TreeEntry>(entries.size());
        for (var entry : entries) {
            String sha = string(entry, "sha", "tree entry");
            if (!SHA.matcher(sha).matches()) throw invalid("GitHub tree entry has an invalid SHA.");
            result.add(new TreeEntry(string(entry, "path", "tree entry"), string(entry, "mode", "tree entry"),
                string(entry, "type", "tree entry"), sha));
        }
        LOG.info("GitHub tree read; entries={}", result.size());
        return List.copyOf(result);
    }

    /** Raw blob bytes, bounded by the shared 1 MiB response ceiling, which equals the reader's note limit. */
    public byte[] blob(String token, String owner, String name, String sha) {
        byte[] bytes;
        try {
            bytes = get(githubUserApi, uri("/repos/{owner}/{name}/git/blobs/{sha}", owner, name, sha), token, RAW,
                () -> new ApiFailure(Code.SOURCE_NOT_FOUND, "GitHub no longer has this note version; refresh the repository."));
        } catch (ApiFailure failure) {
            if (failure.code != Code.GITHUB_RESPONSE_LIMIT) throw failure;
            throw new ApiFailure(Code.UNSUPPORTED_CONTENT,
                "This note exceeds the " + com.reporead.document.MarkdownRenderer.MAX_NOTE_MIB + " MiB reader limit.");
        }
        LOG.info("GitHub blob read; bytes={}", bytes.length);
        return bytes;
    }

    /** Owner/repository names become URL path segments, so dot segments are rejected even though they match NAME. */
    private static boolean validName(String value) {
        return NAME.matcher(value).matches() && !value.equals(".") && !value.equals("..");
    }

    /** Raw bytes of a repository file at a commit, bounded by the 5 MiB image ceiling. {@code path} must be normalized. */
    public byte[] imageFile(String token, String owner, String name, String path, String commitSha) {
        var segments = new ArrayList<String>();
        for (String segment : path.split("/")) segments.add(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        var uri = URI.create("https://api.github.com/repos/" + UriUtils.encodePathSegment(owner, StandardCharsets.UTF_8) + "/"
            + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/contents/" + String.join("/", segments)
            + "?ref=" + UriUtils.encodeQueryParam(commitSha, StandardCharsets.UTF_8));
        byte[] bytes;
        try {
            bytes = get(githubImageApi, uri, token, RAW,
                () -> new ApiFailure(Code.IMAGE_NOT_FOUND, "This image is not in the repository at the note's commit."));
        } catch (ApiFailure failure) {
            if (failure.code != Code.GITHUB_RESPONSE_LIMIT) throw failure;
            throw new ApiFailure(Code.UNSUPPORTED_CONTENT, "This image exceeds the 5 MiB reader limit.");
        }
        LOG.info("GitHub image read; bytes={}", bytes.length);
        return bytes;
    }

    private static URI uri(String template, Object... variables) {
        // Encoding the template first strictly encodes each variable, including '/' in branch names.
        return UriComponentsBuilder.fromUriString("https://api.github.com" + template).encode().buildAndExpand(variables).toUri();
    }

    private byte[] get(RestTemplate api, URI uri, String token, MediaType accept, Supplier<ApiFailure> notFound) {
        var request = RequestEntity.get(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).accept(accept).build();
        ResponseEntity<byte[]> response;
        try {
            response = api.exchange(request, byte[].class);
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 401) throw new ApiFailure(Code.SIGN_IN_REQUIRED, "GitHub rejected the user token; sign in again.");
            var headers = error.getResponseHeaders();
            boolean rateLimited = status == 429 || (status == 403 && headers != null &&
                ("0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.getFirst(HttpHeaders.RETRY_AFTER) != null));
            if (rateLimited || status >= 500) {
                throw new ApiFailure(Code.GITHUB_UNAVAILABLE, "GitHub is unavailable or rate limited; nothing was changed.");
            }
            if (status == 404) throw notFound.get();
            if (status == 403) throw accessDenied();
            throw invalid("GitHub returned an unexpected HTTP status.");
        } catch (ResourceAccessException error) {
            if (error.getCause() instanceof GitHubSecurity.ResponseTooLarge) {
                throw new ApiFailure(Code.GITHUB_RESPONSE_LIMIT, "GitHub exceeded the response-size limit; nothing was changed.");
            }
            throw new ApiFailure(Code.GITHUB_UNAVAILABLE, "The GitHub request failed or timed out; nothing was changed.");
        }
        if (response.getStatusCode().value() != 200) throw invalid("GitHub did not return a complete response.");
        // RestTemplate gives a null body for a 200 with no bytes, which is how GitHub returns an empty file. Empty raw
        // content is then checked like any other (a note's bytes must hash to its blob SHA); JSON callers reject it.
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    private JsonNode object(byte[] body, String what) {
        JsonNode node;
        try {
            node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JacksonException error) {
            throw invalid("GitHub returned malformed " + what + " JSON.");
        }
        if (node == null || !node.isObject()) throw invalid("GitHub " + what + " response must be an object.");
        return node;
    }

    private static JsonNode completeList(JsonNode page, String field, int max, Code limit, String limitMessage) {
        var total = page.get("total_count");
        var items = page.get(field);
        if (total == null || !total.isIntegralNumber() || !total.canConvertToInt() || total.intValue() < 0 || items == null || !items.isArray()) {
            throw invalid("GitHub list response requires total_count and " + field + ".");
        }
        if (total.intValue() > max || items.size() > max) throw new ApiFailure(limit, limitMessage);
        if (items.size() != total.intValue()) throw invalid("GitHub returned an incomplete " + field + " list.");
        return items;
    }

    private static long positiveLong(JsonNode node, String field, String what) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid("GitHub " + what + " metadata requires a positive " + field + ".");
        }
        return value.longValue();
    }

    private static String string(JsonNode node, String field, String what) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid("GitHub " + what + " metadata requires " + field + ".");
        }
        return value.stringValue();
    }

    private static ApiFailure accessDenied() {
        return new ApiFailure(Code.GITHUB_ACCESS_DENIED, "This GitHub resource is not accessible through your GitHub App user token.");
    }

    private static ApiFailure invalid(String message) {
        return new ApiFailure(Code.GITHUB_INVALID_RESPONSE, message);
    }
}
