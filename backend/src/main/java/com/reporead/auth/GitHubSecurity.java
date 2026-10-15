package com.reporead.auth;

import com.reporead.RepoReadProperties;
import com.reporead.github.GitHubHttp;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.session.StandardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
public class GitHubSecurity {
    /** Browser sessions that can exist at once (see boundedSignInSessions). */
    static final int MAX_SIGN_IN_SESSIONS = 100;
    private static final Logger LOG = LoggerFactory.getLogger(GitHubSecurity.class);

    @Bean
    ClientRegistrationRepository registrations(RepoReadProperties properties, ServerProperties server) throws IOException {
        // Only loopback clients reach the server: the phone through `adb reverse` on the Mac, or the hosted HTTPS proxy.
        var address = server.getAddress();
        if (address == null || !address.getHostAddress().equals("127.0.0.1")) {
            throw new IllegalArgumentException("RepoRead must bind to 127.0.0.1, not " + address);
        }
        String callback = callbackUri(properties.publicUrl(), server.getPort());
        var registration = ClientRegistration.withRegistrationId("github")
            .clientId(properties.github().clientId()).clientSecret(readSecret(properties.github().clientSecretFile()))
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

    /**
     * The management server (loopback only, a port adb reverse does not forward) exposes only health, metrics and info,
     * which hold counts and the build's commit, never repository content or credentials.
     */
    @Bean
    @Order(0)
    SecurityFilterChain management(HttpSecurity http, ManagementServerProperties management) throws Exception {
        int managementPort = management.getPort();
        http.securityMatcher(request -> request.getLocalPort() == managementPort)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable());
        return http.build();
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
                                JdkClientHttpRequestFactory githubRequestFactory, AppSessions sessions, AllowedUsers allowed,
                                MeterRegistry meters) throws Exception {
        var pkce = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var resolver = startedByTheApp(pkce);

        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        tokenClient.setRestClient(tokenApi(RestClient.builder().requestFactory(githubRequestFactory), meters));
        var userHttp = new RestTemplate(githubRequestFactory);
        userHttp.setInterceptors(List.of(GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters)));
        userHttp.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        var userService = new DefaultOAuth2UserService();
        userService.setRestOperations(userHttp);

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/app/sign-in", "/auth/failed", "/error").permitAll()
                .anyRequest().denyAll())
            // A refused request is not saved for later (that would open a session): sign-in returns to the app, never here.
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
            .oauth2Login(oauth -> oauth
                .authorizedClientService(clients)
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
                .userInfoEndpoint(endpoint -> endpoint.userService(request -> allowedOnly(userService.loadUser(request), allowed)))
                .successHandler(new AppSignInSuccess(sessions))
                .failureHandler((request, response, error) -> {
                    LOG.warn("GitHub sign-in rejected; failureType={}", error.getClass().getSimpleName());
                    response.sendRedirect("/auth/failed");
                }));
        return http.build();
    }

    /**
     * Caps the browser sessions that exist at once. Only /app/sign-in opens one, for a request no one has authenticated,
     * so this bounds what a flood of sign-ins can hold in memory; past it a sign-in is refused (SIGN_IN_BUSY) until
     * sessions expire (server.servlet.session.timeout). Far above real use: the allow-list admits a handful of users.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedSignInSessions() {
        return factory -> factory.addContextCustomizers(context -> {
            // Tomcat's default manager, set before the context starts: Spring Boot then still turns off its persistence.
            var manager = new StandardManager();
            manager.setMaxActiveSessions(MAX_SIGN_IN_SESSIONS);
            context.setManager(manager);
        });
    }

    /**
     * Starts GitHub authorization only in a browser session that /app/sign-in began, which holds the app's PKCE challenge.
     * Without one there is nothing to resolve, so the request falls through to denyAll (401) and never reaches GitHub: a
     * login the app did not start cannot reach the callback, where Spring would store its GitHub token.
     */
    static OAuth2AuthorizationRequestResolver startedByTheApp(OAuth2AuthorizationRequestResolver resolver) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return hasAppChallenge(request) ? resolver.resolve(request) : null;
            }

            @Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return hasAppChallenge(request) ? resolver.resolve(request, registrationId) : null;
            }
        };
    }

    /**
     * Fails the sign-in of a GitHub user this server does not serve while GitHub's answer is still being checked, so
     * Spring stores no GitHub token for them and no RepoRead account is created; the browser is sent to /auth/failed.
     */
    static OAuth2User allowedOnly(OAuth2User user, AllowedUsers allowed) {
        Object id = user.getAttribute("id");
        if (id instanceof Number number && allowed.allows(number.longValue())) return user;
        LOG.warn("GitHub sign-in refused: not an allowed user; githubUserId={}", id);
        throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "This GitHub account may not use this RepoRead server.");
    }

    private static boolean hasAppChallenge(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(AppAuthController.CHALLENGE_ATTRIBUTE) instanceof String;
    }

    @Bean
    RestClientRefreshTokenTokenResponseClient githubRefreshTokens(JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        var refresh = new RestClientRefreshTokenTokenResponseClient();
        refresh.setRestClient(tokenApi(RestClient.builder().requestFactory(githubRequestFactory), meters));
        return refresh;
    }

    /**
     * Hands out a user's GitHub token, first refreshing it (one token-endpoint call) when it expires within a minute.
     * GitHub App user tokens last 8 hours; the refresh token GitHub rotates on every use.
     */
    @Bean
    OAuth2AuthorizedClientManager githubTokens(ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients,
                                               RestClientRefreshTokenTokenResponseClient githubRefreshTokens) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
            .refreshToken(refresh -> refresh.accessTokenResponseClient(githubRefreshTokens)).build());
        manager.setAuthorizationSuccessHandler((client, principal, attributes) -> {
            clients.saveAuthorizedClient(client, principal);
            LOG.info("GitHub user token refreshed; githubUserId={} expiresAt={}", principal.getName(), client.getAccessToken().getExpiresAt());
        });
        // Replaces Spring's default, which removes the stored token on some refusals: GitHubApi.userToken alone decides
        // what a failed refresh means, and removes the token itself when GitHub refused it.
        manager.setAuthorizationFailureHandler((error, principal, attributes) -> { });
        return manager;
    }

    /** GitHub's OAuth token endpoint, for both the sign-in code exchange and refreshes. */
    static RestClient tokenApi(RestClient.Builder builder, MeterRegistry meters) {
        return builder.requestInterceptor(GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters))
            .configureMessageConverters(converters -> converters.disableDefaults()
                .addCustomConverter(new FormHttpMessageConverter())
                .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
    }

    /**
     * The URL GitHub sends the browser back to after sign-in. Fixed by configuration: the Mac's adb-reverse loopback
     * address on this server's port, or an HTTPS origin with no path. Must match a callback registered on the GitHub App.
     */
    static String callbackUri(String publicUrl, int port) {
        URI uri;
        try {
            uri = new URI(publicUrl);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("reporead.public-url is not a URL: " + publicUrl, error);
        }
        boolean loopback = publicUrl.equals("http://127.0.0.1:" + port);
        boolean httpsOrigin = "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
            && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null;
        if (!loopback && !httpsOrigin) {
            throw new IllegalArgumentException("reporead.public-url must be http://127.0.0.1:" + port
                + " (adb reverse) or an https:// origin without a path, not: " + publicUrl);
        }
        return publicUrl + "/login/oauth2/code/github";
    }

    /** One token from a 0600 file: the GitHub client secret, and the GitHub token key (GitHubTokens). */
    static String readSecret(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Secret file must be a regular non-symlink file: " + file);
        }
        var permissions = Files.getPosixFilePermissions(file);
        if (!permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
            throw new IOException("Secret file must have permissions 0600: " + file);
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(4097); }
        if (bytes.length > 4096) throw new IOException("Secret file exceeds 4096 bytes: " + file);
        String secret = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
        if (!secret.matches("[A-Za-z0-9_]+")) {
            throw new IOException("Secret file must contain one nonblank token, not Markdown prose: " + file);
        }
        return secret;
    }

}
