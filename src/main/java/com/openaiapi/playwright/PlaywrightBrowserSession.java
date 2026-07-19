package com.openaiapi.playwright;

import static com.openaiapi.playwright.UiSelectors.A0;
import static com.openaiapi.playwright.UiSelectors.testId;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.openaiapi.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlaywrightBrowserSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserSession.class);
    private static final long RETRY_MS = 30_000L;

    private final PlaywrightBrowserManager browserManager;
    private final AppProperties appProperties;
    private final Object lock = new Object();
    private final AtomicBoolean warming = new AtomicBoolean(false);

    private BrowserContext context;
    private Page page;
    private volatile boolean ready;
    private volatile boolean shutdown;

    public PlaywrightBrowserSession(PlaywrightBrowserManager browserManager, AppProperties appProperties) {
        this.browserManager = browserManager;
        this.appProperties = appProperties;
    }

    @PostConstruct
    void warmUp() {
        if (!appProperties.getUpstream().isEnabled() || !appProperties.getUpstream().isUsePlaywright()) {
            return;
        }
        scheduleWarmUp(0);
    }

    public boolean isReady() {
        return ready;
    }

    public Page page() {
        synchronized (lock) {
            openPage();
            ready = true;
            return page;
        }
    }

    public void reload() {
        synchronized (lock) {
            if (page == null || page.isClosed()) {
                openPage();
                return;
            }
            page.navigate(
                    appProperties.getUpstream().getPageUrl(),
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator(testId(A0))
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
            injectTransportKey(page);
        }
    }

    @PreDestroy
    void onShutdown() {
        shutdown = true;
        synchronized (lock) {
            closeContext();
            ready = false;
        }
    }

    private void scheduleWarmUp(long delayMs) {
        if (shutdown) {
            return;
        }
        Thread.startVirtualThread(() -> {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (shutdown || ready) {
                return;
            }
            if (!warming.compareAndSet(false, true)) {
                return;
            }
            try {
                synchronized (lock) {
                    if (shutdown) {
                        return;
                    }
                    openPage();
                    ready = true;
                    log.info("Playwright page ready at {}", appProperties.getUpstream().getPageUrl());
                }
            } catch (Exception ex) {
                ready = false;
                log.warn("Playwright page warm-up failed (retry in {}s): {}", RETRY_MS / 1000, ex.getMessage());
                scheduleWarmUp(RETRY_MS);
            } finally {
                warming.set(false);
            }
        });
    }

    private void openPage() {
        if (context != null && page != null && !page.isClosed()) {
            injectTransportKey(page);
            return;
        }
        closeContext();
        Browser browser = browserManager.getBrowser();
        context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        page = context.newPage();
        int timeoutMs = Math.max(60_000, appProperties.getUpstream().getResponseTimeoutMs());
        page.setDefaultTimeout(timeoutMs);
        page.navigate(
                appProperties.getUpstream().getPageUrl(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
        page.locator(testId(A0))
                .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
        injectTransportKey(page);
    }

    private void injectTransportKey(Page page) {
        String key = appProperties.getUpstream().getTransportKey();
        if (!StringUtils.hasText(key) || page == null || page.isClosed()) {
            return;
        }
        page.evaluate("(k) => { window.__k = k; }", key);
    }

    private void closeContext() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception ignored) {
            }
            context = null;
            page = null;
        }
    }
}
