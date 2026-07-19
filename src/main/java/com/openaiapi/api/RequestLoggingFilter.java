package com.openaiapi.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, 8_192);
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            if (wrapped.getRequestURI().contains("/v1/")) {
                int bodyBytes = wrapped.getContentAsByteArray().length;
                log.info(
                        "{} {} -> {} bodyBytes~={}",
                        wrapped.getMethod(),
                        wrapped.getRequestURI(),
                        response.getStatus(),
                        bodyBytes);
            }
        }
    }
}
