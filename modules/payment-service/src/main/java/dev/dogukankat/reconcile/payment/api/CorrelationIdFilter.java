package dev.dogukankat.reconcile.payment.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds a per-request correlation ID to the SLF4J MDC under the key
 * {@code correlationId}. Reads {@code X-Correlation-Id} from the
 * inbound request; if absent or blank, generates a UUID. Echoes the
 * effective value back on the response so callers retrying a request
 * can stitch their logs to ours.
 *
 * Runs before any controller-bound filter so downstream beans
 * (including the outbox writer) see the MDC value via
 * {@code MDC.get("correlationId")}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String correlationId = (inbound == null || inbound.isBlank())
                ? UUID.randomUUID().toString()
                : inbound;
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
