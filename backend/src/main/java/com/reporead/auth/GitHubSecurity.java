package com.reporead.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
public class GitHubSecurity {
    private static final Logger LOG = LoggerFactory.getLogger(GitHubSecurity.class);
    /** Ceiling for OAuth, metadata, and raw note responses; equals MarkdownRenderer.MAX_NOTE_BYTES. */
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    /** Above GitHub's documented 7 MB recursive-tree maximum, so only GitHub's own truncation can make a tree incomplete. */
    public static final int MAX_TREE_RESPONSE_BYTES = 8 * 1_048_576;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    ClientRegistrationRepository registrations(
        @Value("${reporead.github.client-id}") String clientId,
        @Value("${reporead.github.client-secret-file}") String secretFile,
        @Value("${server.address}") String address,
        @Value("${server.port}") int port
    ) throws IOException {
        if (clientId.isBlank()) throw new IllegalArgumentException("reporead.github.client-id must not be blank");
        // The phone reaches this loopback callback through `adb reverse`; see backend/README.md.
        if (!address.equals("127.0.0.1")) throw new IllegalArgumentException("Development OAuth server must bind to 127.0.0.1");
        String callback = "http://" + address + ":" + port + "/login/oauth2/code/github";
        var registration = ClientRegistration.withRegistrationId("github")
            .clientId(clientId).clientSecret(readSecret(Path.of(secretFile)))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(callback)
            .authorizationUri("https://github.com/login/oauth/authorize")
            .tokenUri("https://github.com/login/oauth/access_token")
            .userInfoUri("https://api.github.com/user").userNameAttributeName("id")
            .clientName("RepoRead GitHub App").build();
        LOG.info("GitHub user sign-in configured; callback={}", callback);
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    JdkClientHttpRequestFactory githubRequestFactory() {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build());
        requestFactory.setReadTimeout(HTTP_TIMEOUT);
        return requestFactory;
    }

    @Bean
    RestTemplate githubUserApi(JdkClientHttpRequestFactory githubRequestFactory) {
        var rest = new RestTemplate(githubRequestFactory);
        rest.setInterceptors(java.util.List.of(boundedResponse(MAX_RESPONSE_BYTES)));
        return rest;
    }

    @Bean
    RestTemplate githubTreeApi(JdkClientHttpRequestFactory githubRequestFactory) {
        var rest = new RestTemplate(githubRequestFactory);
        rest.setInterceptors(java.util.List.of(boundedResponse(MAX_TREE_RESPONSE_BYTES)));
        return rest;
    }

    /** /api is bearer-only and stateless: no session cookie can authenticate it, so CSRF does not apply. */
    @Bean
    @Order(1)
    SecurityFilterChain api(HttpSecurity http, AppSessions sessions) throws Exception {
        http.securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/app-auth/token").permitAll()
                .anyRequest().authenticated())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
            .addFilterBefore(new BearerAuthentication(sessions), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** Browser-only GitHub OAuth for the app's Custom Tab. Its session lives only until the app code is issued. */
    @Bean
    @Order(2)
    SecurityFilterChain browser(HttpSecurity http, ClientRegistrationRepository registrations,
                                OAuth2AuthorizedClientService clients,
                                JdkClientHttpRequestFactory githubRequestFactory, AppSessions sessions) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder().requestFactory(githubRequestFactory)
            .requestInterceptor(boundedResponse(MAX_RESPONSE_BYTES))
            .configureMessageConverters(converters -> converters.disableDefaults()
                .addCustomConverter(new FormHttpMessageConverter())
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        var userHttp = new RestTemplate(githubRequestFactory);
        userHttp.setInterceptors(java.util.List.of(boundedResponse(MAX_RESPONSE_BYTES)));
        userHttp.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        var userService = new DefaultOAuth2UserService();
        userService.setRestOperations(userHttp);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/app/sign-in", "/auth/failed", "/error").permitAll()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
            .oauth2Login(oauth -> oauth
                .authorizedClientService(clients)
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
                .userInfoEndpoint(endpoint -> endpoint.userService(userService))
                .successHandler(new AppSignInSuccess(sessions))
                .failureHandler((request, response, error) -> {
                    LOG.warn("GitHub sign-in rejected; failureType={}", error.getClass().getSimpleName());
                    response.sendRedirect("/auth/failed");
                }));
        return http.build();
    }

    public static final class ResponseTooLarge extends IOException {
        ResponseTooLarge(String endpoint, int maxBytes) { super("GitHub response exceeds " + maxBytes + " bytes at " + endpoint); }
    }

    static String readSecret(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GitHub client secret must be a regular non-symlink file: " + file);
        }
        var permissions = Files.getPosixFilePermissions(file);
        if (!permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
            throw new IOException("GitHub client secret file must have permissions 0600: " + file);
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(4097); }
        if (bytes.length > 4096) throw new IOException("GitHub client secret file exceeds 4096 bytes: " + file);
        String secret = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
        if (!secret.matches("[A-Za-z0-9_]+")) {
            throw new IOException("GitHub client secret file must contain one nonblank token, not Markdown prose: " + file);
        }
        return secret;
    }

    // OAuth login makes at most two application-level requests: token exchange, then user identity.
    static ClientHttpRequestInterceptor boundedResponse(int maxBytes) {
        return (request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.USER_AGENT, "RepoRead");
            request.getHeaders().set("X-GitHub-Api-Version", "2026-03-10");
            var response = execution.execute(request, body);
            byte[] bytes;
            try {
                bytes = response.getBody().readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new ResponseTooLarge(request.getURI().getPath(), maxBytes);
                }
            } catch (IOException error) {
                response.close();
                LOG.warn("GitHub response failed; endpoint={} failureType={}", request.getURI().getPath(), error.getClass().getSimpleName());
                throw error;
            }
            LOG.info("GitHub response; endpoint={} status={} bytes={}", request.getURI().getPath(), response.getStatusCode().value(), bytes.length);
            return new ClientHttpResponse() {
                private final InputStream buffered = new ByteArrayInputStream(bytes);
                @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                @Override public InputStream getBody() { return buffered; }
                @Override public void close() { response.close(); }
            };
        };
    }
}
