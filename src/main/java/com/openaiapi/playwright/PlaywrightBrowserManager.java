package com.openaiapi.playwright;

import com.openaiapi.config.AppProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PlaywrightBrowserManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserManager.class);
    private static final String BUNDLED_BROWSERS_RESOURCE = "ms-playwright";

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
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured.trim()).toAbsolutePath().normalize();
            validateBrowsersPath(path);
            return path;
        }

        Path fromSource = Paths.get(System.getProperty("user.dir"), "src", "main", "resources", BUNDLED_BROWSERS_RESOURCE)
                .toAbsolutePath()
                .normalize();
        if (Files.isDirectory(fromSource) && hasChromiumRevision(fromSource)) {
            return fromSource;
        }

        Path fromClasspath = resolveClasspathBrowsersPath();
        if (fromClasspath != null) {
            validateBrowsersPath(fromClasspath);
            return fromClasspath;
        }

        validateBrowsersPath(fromSource);
        return fromSource;
    }

    private Path resolveClasspathBrowsersPath() {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(BUNDLED_BROWSERS_RESOURCE);
        if (resource == null) {
            return null;
        }
        if (!"file".equals(resource.getProtocol())) {
            throw new BrowserNotReadyException(
                    "Bundled Playwright browsers must be on the filesystem (exploded resources), not inside a JAR. "
                            + "Set app.upstream.browsers-path to the ms-playwright directory.");
        }
        try {
            return Paths.get(resource.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException ex) {
            throw new BrowserNotReadyException("Invalid bundled Playwright browsers path", ex);
        }
    }

    private void validateBrowsersPath(Path path) {
        if (!Files.isDirectory(path)) {
            throw new BrowserNotReadyException(
                    "Playwright browsers not found at " + path
                            + ". Run scripts/fetch-playwright-browsers.ps1 on a networked machine, then copy the folder to the remote PC.");
        }
        if (!hasChromiumRevision(path)) {
            throw new BrowserNotReadyException(
                    "No chromium-* revision under " + path
                            + ". Run scripts/fetch-playwright-browsers.ps1 to install chromium into resources.");
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
