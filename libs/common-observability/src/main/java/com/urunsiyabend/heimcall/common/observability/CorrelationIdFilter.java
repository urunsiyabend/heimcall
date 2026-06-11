package com.urunsiyabend.heimcall.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Per-request correlation id for servlet services. Reads {@code X-Correlation-Id} from the inbound
 * request (propagated by an upstream caller/gateway) or mints a fresh one, puts it in the MDC so every
 * log line of this request carries it, echoes it on the response, and clears the MDC afterwards.
 *
 * <p>Ordered highest so the id is present for all downstream filters (incl. the JWT filter) and handlers.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = CorrelationContext.orNew(request.getHeader(CorrelationContext.HEADER));
        CorrelationContext.set(id);
        response.setHeader(CorrelationContext.HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
