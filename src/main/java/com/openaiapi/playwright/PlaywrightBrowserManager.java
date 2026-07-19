package com.openaiapi.playwright;

import com.openaiapi.config.AppProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlaywrightBrowserManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserManager.class);
    static final String DEFAULT_BROWSERS_PATH = "C:\\browser\\ms-playwright";

    private final AppProperties appProperties;
    private final Object lock = new Object();
    private Playwright playwright;
    private Browser browser;

    public PlaywrightBrowserManager(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    void warmUp() {
        if (!appProperties.getUpstream().isEnabled() || !appProperties.getUpstream().isUsePlaywright()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                getBrowser();
            } catch (Exception ex) {
                log.warn("Playwright browser warm-up failed: {}", ex.getMessage());
            }
        });
    }

    public Browser getBrowser() {
        synchronized (lock) {
            if (browser != null && browser.isConnected()) {
                return browser;
            }
            return launchBrowser();
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return browser != null && browser.isConnected();
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ex) {
                    log.warn("Failed to close browser", ex);
                }
                browser = null;
            }
            if (playwright != null) {
                playwright.close();
                playwright = null;
            }
        }
    }

    private Browser launchBrowser() {
        try {
            if (playwright == null) {
                Path browsersPath = resolveBrowsersPath();
                Map<String, String> env = new HashMap<>(System.getenv());
                env.put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toAbsolutePath().toString());
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
                log.info("Playwright using browsers at {} (download disabled)", browsersPath);
            }
            var options = new BrowserType.LaunchOptions()
                    .setHeadless(appProperties.getUpstream().isHeadless());
            browser = playwright.chromium().launch(options);
            log.info("Playwright browser launched");
            return browser;
        } catch (BrowserNotReadyException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BrowserNotReadyException("Failed to launch Playwright browser", ex);
        }
    }

    private Path resolveBrowsersPath() {
        String configured = appProperties.getUpstream().getBrowsersPath();
        Path path = Paths.get(StringUtils.hasText(configured) ? configured.trim() : DEFAULT_BROWSERS_PATH)
                .toAbsolutePath()
                .normalize();
        validateBrowsersPath(path);
        return path;
    }

    private void validateBrowsersPath(Path path) {
        if (!Files.isDirectory(path)) {
            throw new BrowserNotReadyException(
                    "Playwright browsers not found at " + path
                            + ". Place Chromium at C:\\browser\\ms-playwright\\chromium-1148\\chrome-win"
                            + " (PLAYWRIGHT_BROWSERS_PATH = C:\\browser\\ms-playwright). Downloads are disabled.");
        }
        if (!hasChromiumRevision(path)) {
            throw new BrowserNotReadyException(
                    "No chromium-* revision under " + path
                            + ". Expected C:\\browser\\ms-playwright\\chromium-1148\\chrome-win. Downloads are disabled.");
        }
    }

    private boolean hasChromiumRevision(Path browsersPath) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(browsersPath, "chromium-*")) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    return true;
                }
            }
            return false;
        } catch (IOException ex) {
            throw new BrowserNotReadyException("Failed to inspect Playwright browsers at " + browsersPath, ex);
        }
    }
}
