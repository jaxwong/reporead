// Owns the browser OAuth security boundary, including callback admission through GitHubTokenLifecycle.

package com.reporead.auth;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import com.reporead.RepoReadProperties;
import com.reporead.github.GitHubHttpConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.catalina.session.StandardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * GitHub sign-in in the browser, for the app's Custom Tab: the App's OAuth registration, the browser filter chain, and
 * the token endpoint's clients. The app's own API and the management port are ApiSecurity.
 */
@Configuration
public class GitHubBrowserSignIn {
    static final String REGISTRATION = "github";
    /** Browser sessions that can exist at once (see boundedSignInSessions). */
    static final int MAX_SIGN_IN_SESSIONS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(GitHubBrowserSignIn.class);

    @Bean
    ClientRegistrationRepository registrations(RepoReadProperties properties, ServerProperties server)
            throws IOException {
        String callback = callbackUri(properties.publicUrl(), server.getPort());
        var registration = ClientRegistration.withRegistrationId(REGISTRATION)
                .clientId(properties.github().clientId())
                .clientSecret(SecretFiles.read(properties.github().clientSecretFile()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(callback)
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("RepoRead GitHub App")
                .build();
        LOG.info("GitHub user sign-in configured; callback={}", callback);
        return new InMemoryClientRegistrationRepository(registration);
    }

    /** Browser-only GitHub OAuth for the app's Custom Tab. Its session lives only until the app code is issued. */
    @Bean
    @Order(2)
    SecurityFilterChain browser(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients,
            RestClientAuthorizationCodeTokenResponseClient githubCodeExchange,
            DefaultOAuth2UserService githubUserInfo,
            AppSessions sessions,
            GitHubTokenLifecycle credentials,
            AllowedUsers allowed,
            RepoReadProperties properties)
            throws Exception {
        var pkce = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var resolver = startedByTheApp(pkce);

        // Admission must precede token exchange, not just token storage: deleting a grant invalidates newly issued
        // tokens too. The browser's user is not known yet, so GitHubTokenLifecycle's one process gate is used.
        var callbacks = pathPattern(OAuth2LoginAuthenticationFilter.DEFAULT_FILTER_PROCESSES_URI);
        http.addFilterBefore(
                new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        return !callbacks.matches(request);
                    }

                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        try {
                            credentials.changing(() -> {
                                chain.doFilter(request, response);
                                return null;
                            });
                        } catch (IOException | ServletException | RuntimeException failure) {
                            throw failure;
                        } catch (Exception failure) {
                            throw new IllegalStateException(
                                    "OAuth callback threw an undeclared checked exception", failure);
                        }
                    }
                },
                OAuth2LoginAuthenticationFilter.class);

        http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/app/sign-in",
                                AppAuthController.SIGNED_IN_PATH,
                                "/.well-known/assetlinks.json",
                                "/auth/failed",
                                "/error")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                // A refused request is not saved for later (that would open a session): sign-in returns to the app,
                // never here.
                .requestCache(cache -> cache.disable())
                // Every route here is a GET, and the only state is a sign-in in progress, so CSRF protection guards no
                // request; its token would open a session for each refused POST, which counts against
                // MAX_SIGN_IN_SESSIONS. The session ends when the app's code is issued, so there is no logout either.
                .csrf(csrf -> csrf.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
                .oauth2Login(oauth -> oauth.authorizedClientService(clients)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                        .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(githubCodeExchange))
                        .userInfoEndpoint(endpoint ->
                                endpoint.userService(request -> allowedOnly(githubUserInfo.loadUser(request), allowed)))
                        .successHandler(
                                new AppSignInSuccess(sessions, PendingAppSignIn.appRedirect(properties.publicUrl())))
                        .failureHandler((request, response, error) -> {
                            // The OAuth error code says which step failed (state, code exchange, user, allow-list).
                            LOG.warn(
                                    "GitHub sign-in rejected; failureType={} error={}",
                                    error.getClass().getSimpleName(),
                                    githubError(error));
                            response.sendRedirect("/auth/failed");
                        }));
        return http.build();
    }

    /**
     * Caps the browser sessions that exist at once. Only /app/sign-in opens one, for a request no one has
     * authenticated, so this bounds what a flood of sign-ins can hold in memory; past it a sign-in is refused
     * (SIGN_IN_BUSY) until sessions expire (server.servlet.session.timeout). Far above real use: the allow-list admits
     * a handful of users.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> boundedSignInSessions() {
        return factory -> factory.addContextCustomizers(context -> {
            // Tomcat's default manager, set before the context starts: Spring Boot then still turns off its
            // persistence.
            var manager = new StandardManager();
            manager.setMaxActiveSessions(MAX_SIGN_IN_SESSIONS);
            context.setManager(manager);
        });
    }

    /**
     * Starts GitHub authorization only in a browser session that /app/sign-in began, which holds the app's PKCE
     * challenge. Without one there is nothing to resolve, so the request falls through to denyAll (401) and never
     * reaches GitHub: a login the app did not start cannot reach the callback, where Spring would store its GitHub
     * token.
     */
    static OAuth2AuthorizationRequestResolver startedByTheApp(OAuth2AuthorizationRequestResolver resolver) {
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return PendingAppSignIn.isPending(request) ? resolver.resolve(request) : null;
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return PendingAppSignIn.isPending(request) ? resolver.resolve(request, registrationId) : null;
            }
        };
    }

    /**
     * Fails the sign-in of a GitHub user this server does not serve while GitHub's answer is still being checked, so
     * Spring stores no GitHub token for them and no RepoRead account is created; the browser is sent to /auth/failed.
     */
    private static OAuth2User allowedOnly(OAuth2User user, AllowedUsers allowed) {
        Object id = user.getAttribute("id");
        if (id instanceof Number number && allowed.allows(number.longValue())) return user;
        LOG.warn("GitHub sign-in refused: not an allowed user; githubUserId={}", id);
        throw new OAuth2AuthenticationException(
                new OAuth2Error("access_denied"), "This GitHub account may not use this RepoRead server.");
    }

    @Bean
    RestClientAuthorizationCodeTokenResponseClient githubCodeExchange(
            JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        var exchange = new RestClientAuthorizationCodeTokenResponseClient();
        exchange.setRestClient(tokenApi(RestClient.builder().requestFactory(githubRequestFactory), meters));
        return exchange;
    }

    /** Reads the signed-in GitHub user, whose id allowedOnly checks before Spring stores the token. */
    @Bean
    DefaultOAuth2UserService githubUserInfo(JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        var http = GitHubHttpConfiguration.bounded(
                githubRequestFactory, GitHubHttpConfiguration.MAX_RESPONSE_BYTES, meters);
        http.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        var users = new DefaultOAuth2UserService();
        users.setRestOperations(http);
        return users;
    }

    @Bean
    RestClientRefreshTokenTokenResponseClient githubRefreshTokens(
            JdkClientHttpRequestFactory githubRequestFactory, MeterRegistry meters) {
        var refresh = new RestClientRefreshTokenTokenResponseClient();
        refresh.setRestClient(tokenApi(RestClient.builder().requestFactory(githubRequestFactory), meters));
        return refresh;
    }

    /**
     * GitHub's OAuth token endpoint, for both the sign-in code exchange and refreshes. GitHub answers a refusal with
     * HTTP 200 and an error body, which is read as that error, so the caller sees GitHub's code (bad_refresh_token,
     * incorrect_client_credentials) instead of an unreadable token response.
     */
    static RestClient tokenApi(RestClient.Builder builder, MeterRegistry meters) {
        var tokens = new OAuth2AccessTokenResponseHttpMessageConverter();
        var standard = new DefaultMapOAuth2AccessTokenResponseConverter();
        tokens.setAccessTokenResponseConverter(answer -> {
            if (answer.get("error") instanceof String code)
                throw new OAuth2AuthorizationException(new OAuth2Error(code));
            return standard.convert(answer);
        });
        return builder.requestInterceptor(
                        GitHubHttpConfiguration.boundedResponse(GitHubHttpConfiguration.MAX_RESPONSE_BYTES, meters))
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(tokens))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /**
     * The OAuth error code a failure carries, GitHub's own when tokenApi read one: Spring nests it under its own
     * invalid_token_response. Null when there is none. Sign-in failures and token refreshes both read it here.
     */
    static String githubError(Throwable failure) {
        String code = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OAuth2AuthenticationException rejected)
                code = rejected.getError().getErrorCode();
            if (cause instanceof OAuth2AuthorizationException refused)
                code = refused.getError().getErrorCode();
        }
        return code;
    }

    /**
     * The URL GitHub sends the browser back to after sign-in. Fixed by configuration: the Mac's adb-reverse loopback
     * address on this server's port, or an HTTPS origin with no path. Must match a callback registered on the GitHub
     * App.
     */
    static String callbackUri(String publicUrl, int port) {
        URI uri;
        try {
            uri = new URI(publicUrl);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("reporead.public-url is not a URL: " + publicUrl, failure);
        }
        boolean loopback = publicUrl.equals("http://127.0.0.1:" + port);
        boolean httpsOrigin = "https".equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawPath().isEmpty()
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!loopback && !httpsOrigin) {
            throw new IllegalArgumentException("reporead.public-url must be http://127.0.0.1:" + port
                    + " (adb reverse) or an https:// origin without a path, not: " + publicUrl);
        }
        return publicUrl + AppAuthController.GITHUB_CALLBACK_PATH;
    }
}
