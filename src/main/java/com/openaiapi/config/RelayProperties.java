package com.openaiapi.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.relay")
public class RelayProperties {

    /** Base URL of the Python cursor-openai-bridge; Chromium navigates here to load robot.html. */
    @NotBlank
    private String bridgeUrl = "http://127.0.0.1:8787/";
    /** Must match the bridge's RELAY_TOKEN. */
    @NotBlank
    private String relayToken = "";
    /** Bearer token required from local Continue clients hitting this Java gateway. */
    @NotBlank
    private String bearerToken = "";
    private boolean headless = false;
    private String browsersPath = "";
    private String browserExecutablePath = "";
    @Min(1000)
    private long responseTimeoutMs = 300_000;

    public String getBridgeUrl() {
        return bridgeUrl;
    }

    public void setBridgeUrl(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
    }

    public String getRelayToken() {
        return relayToken;
    }

    public void setRelayToken(String relayToken) {
        this.relayToken = relayToken;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getBrowsersPath() {
        return browsersPath;
    }

    public void setBrowsersPath(String browsersPath) {
        this.browsersPath = browsersPath;
    }

    public String getBrowserExecutablePath() {
        return browserExecutablePath;
    }

    public void setBrowserExecutablePath(String browserExecutablePath) {
        this.browserExecutablePath = browserExecutablePath;
    }

    public long getResponseTimeoutMs() {
        return responseTimeoutMs;
    }

    public void setResponseTimeoutMs(long responseTimeoutMs) {
        this.responseTimeoutMs = responseTimeoutMs;
    }
}
