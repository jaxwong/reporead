package com.reporead.auth;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import com.reporead.RepoReadProperties;
import com.reporead.github.GitHubHttp;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.client.RestClient;

/**
 * GitHub sign-in in the browser, for the app's Custom Tab: the App's OAuth registration, the browser filter chain, and
 * the token endpoint's clients. The app's own API and the management port are AppSecurity.
 */
@Configuration
public class GitHubSecurity {
    /** Browser sessions that can exist at once (see boundedSignInSessions). */
    static final int MAX_SIGN_IN_SESSIONS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(GitHubSecurity.class);

    @Bean
    ClientRegistrationRepository registrations(RepoReadProperties properties, ServerProperties server)
            throws IOException {
        String callback = callbackUri(properties.publicUrl(), server.getPort());
        var registration = ClientRegistration.withRegistrationId("github")
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
            AllowedUsers allowed,
            RepoReadProperties properties)
            throws Exception {
        var pkce = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        pkce.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var resolver = startedByTheApp(pkce);

        http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/app/sign-in",
                                PendingAppSignIn.SIGNED_IN,
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
                            LOG.warn(
                                    "GitHub sign-in rejected; failureType={}",
                                    error.getClass().getSimpleName());
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
    static OAuth2User allowedOnly(OAuth2User user, AllowedUsers allowed) {
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
        var http = GitHubHttp.bounded(githubRequestFactory, GitHubHttp.MAX_RESPONSE_BYTES, meters);
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

    /** GitHub's OAuth token endpoint, for both the sign-in code exchange and refreshes. */
    static RestClient tokenApi(RestClient.Builder builder, MeterRegistry meters) {
        return builder.requestInterceptor(GitHubHttp.boundedResponse(GitHubHttp.MAX_RESPONSE_BYTES, meters))
                .configureMessageConverters(converters -> converters
                        .disableDefaults()
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
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
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("reporead.public-url is not a URL: " + publicUrl, error);
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
        return publicUrl + "/login/oauth2/code/github";
    }
}
