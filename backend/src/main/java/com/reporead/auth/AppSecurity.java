package com.reporead.auth;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Who can reach the server and how the app authenticates: loopback connections only, a management port that exposes
 * only counts and the build's commit, and a bearer-only /api. GitHub sign-in in the browser is GitHubSecurity.
 */
@Configuration
public class AppSecurity {
    /**
     * Only loopback clients reach the server: the phone through `adb reverse` on the Mac, or the hosted HTTPS proxy.
     * Tomcat honours forwarded headers only from such addresses, and the management chain trusts its port, so binding
     * anywhere else fails startup. Checked where the address is applied, after Spring Boot's own customizer.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> loopbackOnly() {
        return factory -> {
            var address = factory.getAddress();
            if (address == null || !address.getHostAddress().equals("127.0.0.1")) {
                throw new IllegalArgumentException("RepoRead must bind to 127.0.0.1, not " + address);
            }
        };
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
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.POST, "/api/app-auth/token")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
                .addFilterBefore(new BearerAuthentication(sessions), AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
