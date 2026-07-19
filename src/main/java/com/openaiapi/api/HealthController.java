package com.openaiapi.api;

import com.openaiapi.playwright.PlaywrightBrowserManager;
import com.openaiapi.playwright.PlaywrightBrowserSession;
import com.openaiapi.playwright.PlaywrightUiTransport;
import com.openaiapi.service.UpstreamProxyService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final UpstreamProxyService upstreamProxyService;
    private final PlaywrightBrowserSession browserSession;
    private final PlaywrightBrowserManager browserManager;
    private final PlaywrightUiTransport playwrightUiTransport;

    public HealthController(
            UpstreamProxyService upstreamProxyService,
            PlaywrightBrowserSession browserSession,
            PlaywrightBrowserManager browserManager,
            PlaywrightUiTransport playwrightUiTransport) {
        this.upstreamProxyService = upstreamProxyService;
        this.browserSession = browserSession;
        this.browserManager = browserManager;
        this.playwrightUiTransport = playwrightUiTransport;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("playwrightReady", browserSession.isReady());
        body.put("playwrightConnected", browserManager.isConnected());
        body.put("busy", playwrightUiTransport.isBusy());

        if (upstreamProxyService.isEnabled()) {
            try {
                body.put("upstream", upstreamProxyService.proxyHealth());
                body.put("upstreamReachable", true);
            } catch (Exception ex) {
                body.put("upstreamReachable", false);
                body.put("upstreamError", ex.getMessage() == null ? "unreachable" : ex.getMessage());
            }
        }
        return body;
    }
}
