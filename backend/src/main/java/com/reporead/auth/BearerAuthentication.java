package com.reporead.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates /api requests from an app session bearer token. Anything else stays anonymous and receives 401. */
final class BearerAuthentication extends OncePerRequestFilter {
    private static final String PREFIX = "Bearer ";
    private final AppSessions sessions;

    BearerAuthentication(AppSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            sessions.authenticate(token).ifPresent(user -> {
                var context = SecurityContextHolder.createEmptyContext();
                // The token is the credentials, so signing out revokes exactly this session without parsing again.
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, token, List.of()));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }
}
