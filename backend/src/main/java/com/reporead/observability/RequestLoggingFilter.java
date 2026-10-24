package com.reporead.observability;

import com.reporead.auth.AppAuthController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Names each request so a failure the app logs can be found in the server's log. The server makes the id; every line
 * logged while the request runs carries it (logging.pattern.correlation), the response returns it in X-Request-Id, and
 * one line records the request's outcome. Outermost, so requests Spring Security refuses are logged too. Query values
 * are kept for diagnostic context except on the two authentication callbacks, whose one-time values are redacted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLoggingFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final Set<String> REDACTED_QUERY_PATHS =
            Set.of(AppAuthController.GITHUB_CALLBACK_PATH, AppAuthController.SIGNED_IN_PATH);
    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        String requestTarget = request.getRequestURI();
        if (request.getQueryString() != null)
            requestTarget += REDACTED_QUERY_PATHS.contains(request.getServletPath())
                    ? "?[REDACTED]"
                    : "?" + request.getQueryString();
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        response.setHeader(HEADER, MDC.get(MDC_KEY));
        try {
            chain.doFilter(request, response);
            LOG.info(
                    "Request; method={} target={} status={} durationMs={}",
                    request.getMethod(),
                    requestTarget,
                    response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (IOException | ServletException | RuntimeException failure) {
            // A server bug, so ERROR, as Tomcat's stack trace that follows on the same thread is; it answers 500, and
            // the status is not set yet here.
            LOG.error(
                    "Request failed unhandled; method={} target={} durationMs={} error={}",
                    request.getMethod(),
                    requestTarget,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    NestedExceptionUtils.getMostSpecificCause(failure)
                            .getClass()
                            .getSimpleName());
            throw failure;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
