package com.reporead;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * one line records the request's outcome. Outermost, so requests Spring Security refuses are logged too. The path is
 * logged without its query, which can name a note (the image route's path).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestLog extends OncePerRequestFilter {
    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";
    private static final Logger LOG = LoggerFactory.getLogger(RequestLog.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        MDC.put(MDC_KEY, UUID.randomUUID().toString());
        response.setHeader(HEADER, MDC.get(MDC_KEY));
        try {
            chain.doFilter(request, response);
            LOG.info(
                    "Request; method={} path={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (IOException | ServletException | RuntimeException error) {
            // Tomcat logs the stack trace next, on the same thread, and answers 500; the status is not set yet here.
            LOG.warn(
                    "Request failed unhandled; method={} path={} durationMs={} error={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName());
            throw error;
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
