package com.reporead.github;

import com.reporead.ApiFailure;
import com.reporead.ApiFailure.Code;
import com.reporead.Hashes;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
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

/**
 * Repository GitHub REST reads, each with a signed-in user's GitHub App user token from GitHubTokenLifecycle. Each
 * method makes exactly one GET request: no retries, pagination, redirects, or installation-token fallback. GitHub owns
 * authorization: user-token endpoints only return repositories the user and installation can both access.
 */
@Component
public class GitHubApi {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubApi.class);
    static final int MAX_INSTALLATIONS = 10;
    static final int MAX_REPOSITORIES = 100;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final RestTemplate githubUserHttpClient;
    private final RestTemplate githubTreeHttpClient;
    private final RestTemplate githubImageHttpClient;
    private final JsonMapper json;

    public GitHubApi(
            @Qualifier("githubUserHttpClient") RestTemplate githubUserHttpClient,
            @Qualifier("githubTreeHttpClient") RestTemplate githubTreeHttpClient,
            @Qualifier("githubImageHttpClient") RestTemplate githubImageHttpClient,
            JsonMapper json) {
        this.githubUserHttpClient = githubUserHttpClient;
        this.githubTreeHttpClient = githubTreeHttpClient;
        this.githubImageHttpClient = githubImageHttpClient;
        this.json = json;
    }

    public record Repository(
            long githubRepositoryId,
            long installationId,
            String owner,
            String name,
            boolean privateRepository,
            String defaultBranch) {
        public String fullName() {
            return owner + "/" + name;
        }
    }

    public record Branch(String commitSha, String treeSha) {}

    public record TreeEntry(String path, String mode, String type, String sha) {}

    /** Installation IDs of this GitHub App that the user can access; a complete list of at most 10. */
    public List<Long> installations(String token) {
        var response = parseObject(
                get(
                        githubUserHttpClient,
                        uri("/user/installations?per_page=100"),
                        token,
                        GitHubHttpConfiguration.JSON,
                        GitHubApi::accessDenied,
                        GitHubApi::responseLimit,
                        GitHubApi::redirected),
                "installation");
        var installationNodes = completeList(
                response,
                "installations",
                MAX_INSTALLATIONS,
                Code.GITHUB_INSTALLATION_LIMIT,
                "RepoRead supports at most "
                        + MAX_INSTALLATIONS
                        + " accessible GitHub App installations; nothing was published.");
        var installationIds = new ArrayList<Long>();
        for (var installationNode : installationNodes) {
            long installationId = positiveLong(installationNode, "id", "installation");
            if (installationIds.contains(installationId)) {
                throw invalid("GitHub returned a duplicate installation id.");
            }
            installationIds.add(installationId);
        }
        LOG.info("GitHub installations listed; count={}", installationIds.size());
        return List.copyOf(installationIds);
    }

    /** Repositories in one installation that this user can access; a complete list of at most 100. */
    public List<Repository> repositories(String token, long installationId) {
        var response = parseObject(
                get(
                        githubUserHttpClient,
                        uri("/user/installations/" + installationId + "/repositories?per_page=" + MAX_REPOSITORIES),
                        token,
                        GitHubHttpConfiguration.JSON,
                        GitHubApi::accessDenied,
                        GitHubApi::responseLimit,
                        GitHubApi::redirected),
                "repository");
        var repositoryNodes = completeList(
                response,
                "repositories",
                MAX_REPOSITORIES,
                Code.GITHUB_REPOSITORY_LIMIT,
                "RepoRead requires a complete list of at most "
                        + MAX_REPOSITORIES
                        + " repositories per installation; no next page was fetched.");
        var repositories = new ArrayList<Repository>();
        var repositoryIds = new HashSet<Long>();
        for (var repositoryNode : repositoryNodes) {
            long repositoryId = positiveLong(repositoryNode, "id", "repository");
            String fullName = requiredString(repositoryNode, "full_name", "repository");
            var privateFlag = repositoryNode == null ? null : repositoryNode.get("private");
            if (privateFlag == null || !privateFlag.isBoolean())
                throw invalid("GitHub repository metadata requires a private flag.");
            String defaultBranch = requiredString(repositoryNode, "default_branch", "repository");
            String[] fullNameParts = fullName.split("/", -1);
            if (fullNameParts.length != 2
                    || !validName(fullNameParts[0])
                    || !validName(fullNameParts[1])
                    || !repositoryIds.add(repositoryId)) {
                throw invalid("GitHub repository metadata contains an invalid name or duplicate id.");
            }
            repositories.add(new Repository(
                    repositoryId,
                    installationId,
                    fullNameParts[0],
                    fullNameParts[1],
                    privateFlag.booleanValue(),
                    defaultBranch));
        }
        LOG.info("GitHub repositories listed; installationId={} count={}", installationId, repositories.size());
        return List.copyOf(repositories);
    }

    public Branch branch(String token, String owner, String name, String branchName) {
        var response = parseObject(
                get(
                        githubUserHttpClient,
                        uri("/repos/{owner}/{name}/branches/{branch}", owner, name, branchName),
                        token,
                        GitHubHttpConfiguration.JSON,
                        () -> new ApiFailure(
                                Code.DEFAULT_BRANCH_NOT_FOUND,
                                "The default branch was not found: the repository may be empty or no longer accessible, or its default branch was renamed (connecting the repository again records the current one)."),
                        GitHubApi::responseLimit,
                        GitHubApi::renamed),
                "branch");
        var commitNode = response.get("commit");
        String commitSha = requiredString(commitNode, "sha", "branch commit");
        var treeNode = commitNode.get("commit") == null
                ? null
                : commitNode.get("commit").get("tree");
        String treeSha = requiredString(treeNode, "sha", "branch tree");
        if (!Hashes.isGitSha(commitSha) || !Hashes.isGitSha(treeSha))
            throw invalid("GitHub returned an invalid commit or tree SHA.");
        return new Branch(commitSha, treeSha);
    }

    /** The full recursive tree. A truncated tree is incomplete and is never treated as the repository's contents. */
    public List<TreeEntry> completeTree(String token, String owner, String name, String treeSha) {
        var response = parseObject(
                get(
                        githubTreeHttpClient,
                        uri("/repos/{owner}/{name}/git/trees/{sha}?recursive=1", owner, name, treeSha),
                        token,
                        GitHubHttpConfiguration.JSON,
                        GitHubApi::accessDenied,
                        GitHubApi::responseLimit,
                        GitHubApi::renamed),
                "tree");
        var truncated = response.get("truncated");
        var treeNodes = response.get("tree");
        if (truncated == null || !truncated.isBoolean() || treeNodes == null || !treeNodes.isArray()) {
            throw invalid("GitHub tree response requires truncated and tree.");
        }
        if (truncated.booleanValue()) {
            throw new ApiFailure(
                    Code.GITHUB_TREE_INCOMPLETE,
                    "GitHub returned a truncated repository tree; no documents were changed.");
        }
        var treeEntries = new ArrayList<TreeEntry>(treeNodes.size());
        for (var treeNode : treeNodes) {
            String entrySha = requiredString(treeNode, "sha", "tree entry");
            if (!Hashes.isGitSha(entrySha)) throw invalid("GitHub tree entry has an invalid SHA.");
            treeEntries.add(new TreeEntry(
                    requiredString(treeNode, "path", "tree entry"),
                    requiredString(treeNode, "mode", "tree entry"),
                    requiredString(treeNode, "type", "tree entry"),
                    entrySha));
        }
        LOG.info("GitHub tree read; entries={}", treeEntries.size());
        return List.copyOf(treeEntries);
    }

    /** Raw blob bytes, bounded by the shared 1 MiB response ceiling, which equals the reader's note limit. */
    public byte[] blob(String token, String owner, String name, String sha) {
        byte[] bytes = get(
                githubUserHttpClient,
                uri("/repos/{owner}/{name}/git/blobs/{sha}", owner, name, sha),
                token,
                GitHubHttpConfiguration.RAW,
                () -> new ApiFailure(
                        Code.SOURCE_NOT_FOUND, "GitHub no longer has this note version; refresh the repository."),
                () -> new ApiFailure(
                        Code.UNSUPPORTED_CONTENT,
                        "This note exceeds the "
                                + GitHubHttpConfiguration.MAX_RESPONSE_BYTES / 1_048_576
                                + " MiB reader limit."),
                GitHubApi::renamed);
        LOG.info("GitHub blob read; bytes={}", bytes.length);
        return bytes;
    }

    /** Owner/repository names become URL path segments, so dot segments are rejected even though they match NAME. */
    private static boolean validName(String value) {
        return NAME.matcher(value).matches() && !value.equals(".") && !value.equals("..");
    }

    /**
     * Raw bytes of a repository file at a commit, bounded by the 5 MiB image ceiling. {@code path} must be normalized.
     */
    public byte[] imageFile(String token, String owner, String name, String path, String commitSha) {
        var segments = new ArrayList<String>();
        for (String segment : path.split("/"))
            segments.add(UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8));
        var uri = URI.create(GitHubHttpConfiguration.API_ORIGIN + "/repos/"
                + UriUtils.encodePathSegment(owner, StandardCharsets.UTF_8)
                + "/"
                + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + "/contents/" + String.join("/", segments)
                + "?ref=" + UriUtils.encodeQueryParam(commitSha, StandardCharsets.UTF_8));
        byte[] bytes = get(
                githubImageHttpClient,
                uri,
                token,
                GitHubHttpConfiguration.RAW,
                () -> new ApiFailure(Code.IMAGE_NOT_FOUND, "This image is not in the repository at the note's commit."),
                () -> new ApiFailure(
                        Code.UNSUPPORTED_CONTENT,
                        "This image exceeds the "
                                + GitHubHttpConfiguration.MAX_IMAGE_RESPONSE_BYTES / 1_048_576
                                + " MiB reader limit."),
                GitHubApi::renamed);
        LOG.info("GitHub image read; bytes={}", bytes.length);
        return bytes;
    }

    private static URI uri(String template, Object... variables) {
        // Encoding the template first strictly encodes each variable, including '/' in branch names.
        return UriComponentsBuilder.fromUriString(GitHubHttpConfiguration.API_ORIGIN + template)
                .encode()
                .buildAndExpand(variables)
                .toUri();
    }

    /**
     * One GET. {@code notFound} is what a 404 means for this resource, {@code tooLarge} what a response over the
     * client's size ceiling means, and {@code redirect} what a redirect means: GitHub redirects a renamed repository's
     * or branch's old name, which only a request naming a repository can have.
     */
    private byte[] get(
            RestTemplate client,
            URI uri,
            String token,
            MediaType accept,
            Supplier<ApiFailure> notFound,
            Supplier<ApiFailure> tooLarge,
            Supplier<ApiFailure> redirect) {
        var request = RequestEntity.get(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(accept)
                .build();
        ResponseEntity<byte[]> response;
        try {
            response = client.exchange(request, byte[].class);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            if (status == 401)
                throw new ApiFailure(Code.SIGN_IN_REQUIRED, "GitHub rejected the user token; sign in again.");
            var responseHeaders = failure.getResponseHeaders();
            boolean isRateLimited = status == 429
                    || (status == 403
                            && responseHeaders != null
                            && ("0".equals(responseHeaders.getFirst("X-RateLimit-Remaining"))
                                    || responseHeaders.getFirst(HttpHeaders.RETRY_AFTER) != null));
            if (isRateLimited || status >= 500) {
                throw new ApiFailure(
                        Code.GITHUB_UNAVAILABLE, "GitHub is unavailable or rate limited; nothing was changed.");
            }
            if (status == 404) throw notFound.get();
            if (status == 403) throw accessDenied();
            throw invalid("GitHub returned an unexpected HTTP status.");
        } catch (ResourceAccessException failure) {
            if (failure.getCause() instanceof GitHubHttpConfiguration.ResponseTooLarge) throw tooLarge.get();
            throw new ApiFailure(
                    Code.GITHUB_UNAVAILABLE, "The GitHub request failed or timed out; nothing was changed.");
        }
        if (response.getStatusCode().is3xxRedirection()) throw redirect.get();
        if (response.getStatusCode().value() != 200) throw invalid("GitHub did not return a complete response.");
        // RestTemplate gives a null body for a 200 with no bytes, which is how GitHub returns an empty file. Empty raw
        // content is then checked like any other (a note's bytes must hash to its blob SHA); JSON callers reject it.
        return response.getBody() == null ? new byte[0] : response.getBody();
    }

    private JsonNode parseObject(byte[] responseBody, String resourceName) {
        JsonNode response;
        try {
            response = json.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(responseBody);
        } catch (JacksonException failure) {
            throw invalid("GitHub returned malformed " + resourceName + " JSON.");
        }
        if (response == null || !response.isObject()) {
            throw invalid("GitHub " + resourceName + " response must be an object.");
        }
        return response;
    }

    private static JsonNode completeList(
            JsonNode response, String fieldName, int maximumItems, Code limitCode, String limitMessage) {
        var totalCount = response.get("total_count");
        var nodes = response.get(fieldName);
        if (totalCount == null
                || !totalCount.isIntegralNumber()
                || !totalCount.canConvertToInt()
                || totalCount.intValue() < 0
                || nodes == null
                || !nodes.isArray()) {
            throw invalid("GitHub list response requires total_count and " + fieldName + ".");
        }
        if (totalCount.intValue() > maximumItems || nodes.size() > maximumItems) {
            throw new ApiFailure(limitCode, limitMessage);
        }
        if (nodes.size() != totalCount.intValue()) {
            throw invalid("GitHub returned an incomplete " + fieldName + " list.");
        }
        return nodes;
    }

    private static long positiveLong(JsonNode node, String fieldName, String resourceName) {
        var value = node == null ? null : node.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid("GitHub " + resourceName + " metadata requires a positive " + fieldName + ".");
        }
        return value.longValue();
    }

    private static String requiredString(JsonNode node, String fieldName, String resourceName) {
        var value = node == null ? null : node.get(fieldName);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid("GitHub " + resourceName + " metadata requires " + fieldName + ".");
        }
        return value.stringValue();
    }

    private static ApiFailure responseLimit() {
        return new ApiFailure(
                Code.GITHUB_RESPONSE_LIMIT, "GitHub exceeded the response-size limit; nothing was changed.");
    }

    /**
     * Connecting the repository again stores GitHub's current name and default branch and keeps the connection's data;
     * retrying cannot fix it.
     */
    private static ApiFailure renamed() {
        return new ApiFailure(
                Code.REPOSITORY_RENAMED,
                "GitHub redirected this request, as it does after a repository or branch is renamed.");
    }

    private static ApiFailure redirected() {
        return invalid("GitHub redirected a request that names no repository.");
    }

    private static ApiFailure accessDenied() {
        return new ApiFailure(
                Code.GITHUB_ACCESS_DENIED,
                "This GitHub resource is not accessible through your GitHub App user token.");
    }

    private static ApiFailure invalid(String message) {
        return new ApiFailure(Code.GITHUB_INVALID_RESPONSE, message);
    }
}
