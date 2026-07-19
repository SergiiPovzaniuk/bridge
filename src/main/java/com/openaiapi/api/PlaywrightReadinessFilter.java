package com.openaiapi.api;

import com.openaiapi.config.AppProperties;
import com.openaiapi.playwright.PlaywrightBrowserSession;
import com.openaiapi.service.UpstreamProxyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PlaywrightReadinessFilter extends OncePerRequestFilter {

    private final UpstreamProxyService upstreamProxyService;
    private final PlaywrightBrowserSession browserSession;
    private final AppProperties appProperties;

    public PlaywrightReadinessFilter(
            UpstreamProxyService upstreamProxyService,
            PlaywrightBrowserSession browserSession,
            AppProperties appProperties) {
        this.upstreamProxyService = upstreamProxyService;
        this.browserSession = browserSession;
        this.appProperties = appProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        if (!request.getRequestURI().contains("/v1/chat/completions")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (shouldWaitForPlaywright() && !browserSession.isReady()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "5");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter()
                    .write(
                            "{\"error\":{\"message\":\"Playwright session is starting\",\"type\":\"cursor_agent_error\",\"retryable\":true}}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean shouldWaitForPlaywright() {
        return upstreamProxyService.isEnabled() && appProperties.getUpstream().isUsePlaywright();
    }
}
