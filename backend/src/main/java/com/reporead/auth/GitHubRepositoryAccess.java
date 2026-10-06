package com.reporead.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Component
public class GitHubRepositoryAccess {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubRepositoryAccess.class);
    private static final int MAX_REPOSITORIES = 100;
    private final RestTemplate githubUserApi;
    private final JsonMapper json;

    public GitHubRepositoryAccess(RestTemplate githubUserApi, JsonMapper json) {
        this.githubUserApi = githubUserApi;
        this.json = json;
    }

    public record Repository(long id, String fullName, boolean privateRepository) {}
    public record AccessibleRepositories(long installationId, List<Repository> repositories) {}

    static final class Failure extends RuntimeException {
        final HttpStatus status;
        final String code;

        Failure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    AccessibleRepositories fetch(long installationId, String userToken) {
        var request = RequestEntity.get(URI.create("https://api.github.com/user/installations/"
                + installationId + "/repositories?per_page=" + MAX_REPOSITORIES))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
            .accept(MediaType.parseMediaType("application/vnd.github+json")).build();
        ResponseEntity<byte[]> response;
        try {
            response = githubUserApi.exchange(request, byte[].class);
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 401) throw new Failure(HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED", "GitHub rejected the user token; sign in again.");
            var headers = error.getResponseHeaders();
            boolean rateLimited = status == 429 || (status == 403 && headers != null &&
                ("0".equals(headers.getFirst("X-RateLimit-Remaining")) || headers.getFirst(HttpHeaders.RETRY_AFTER) != null));
            if (rateLimited || status >= 500) {
                throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_UNAVAILABLE", "GitHub is unavailable or rate limited; no repositories were published.");
            }
            if (status == 403 || status == 404) {
                throw new Failure(HttpStatus.FORBIDDEN, "GITHUB_ACCESS_DENIED", "This installation is not accessible through your GitHub App user token.");
            }
            throw invalidResponse("GitHub returned an unexpected HTTP status.");
        } catch (ResourceAccessException error) {
            if (error.getCause() instanceof GitHubSecurity.ResponseTooLarge) {
                throw new Failure(HttpStatus.BAD_GATEWAY, "GITHUB_RESPONSE_LIMIT", "GitHub exceeded the response-size limit; no repositories were published.");
            }
            throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_UNAVAILABLE", "The GitHub request failed or timed out; no repositories were published.");
        }
        if (response.getStatusCode().value() != 200 || response.getBody() == null) {
            throw invalidResponse("GitHub did not return a complete repository response.");
        }

        JsonNode page;
        try {
            page = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.getBody());
        } catch (JacksonException error) {
            throw invalidResponse("GitHub returned malformed repository JSON.");
        }
        if (page == null || !page.isObject()) throw invalidResponse("GitHub repository response must be an object.");
        var total = page.get("total_count");
        var repositories = page.get("repositories");
        if (total == null || !total.isIntegralNumber() || !total.canConvertToInt() || total.intValue() < 0 ||
            repositories == null || !repositories.isArray()) {
            throw invalidResponse("GitHub repository response requires total_count and repositories.");
        }
        boolean hasNext = response.getHeaders().getOrEmpty(HttpHeaders.LINK).stream().anyMatch(value -> value.contains("rel=\"next\""));
        if (total.intValue() > MAX_REPOSITORIES || repositories.size() > MAX_REPOSITORIES || hasNext) {
            throw new Failure(HttpStatus.BAD_GATEWAY, "GITHUB_REPOSITORY_LIMIT", "This Stage 0 check requires a complete list of at most 100 repositories; no next page was fetched.");
        }
        if (repositories.size() != total.intValue()) throw invalidResponse("GitHub returned an incomplete repository list.");
        var result = new ArrayList<Repository>();
        var ids = new HashSet<Long>();
        for (var repository : repositories) {
            var id = repository.get("id");
            var name = repository.get("full_name");
            var privateFlag = repository.get("private");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 ||
                name == null || !name.isString() || name.stringValue().isBlank() ||
                privateFlag == null || !privateFlag.isBoolean()) {
                throw invalidResponse("GitHub repository metadata requires a positive id, full_name, and private flag.");
            }
            String[] parts = name.stringValue().split("/", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || !ids.add(id.longValue())) {
                throw invalidResponse("GitHub repository metadata contains an invalid name or duplicate id.");
            }
            result.add(new Repository(id.longValue(), name.stringValue(), privateFlag.booleanValue()));
        }
        LOG.info("GitHub repository eligibility checked; installationId={} count={}", installationId, result.size());
        return new AccessibleRepositories(installationId, List.copyOf(result));
    }

    private static Failure invalidResponse(String message) {
        return new Failure(HttpStatus.BAD_GATEWAY, "GITHUB_INVALID_RESPONSE", message);
    }
}
