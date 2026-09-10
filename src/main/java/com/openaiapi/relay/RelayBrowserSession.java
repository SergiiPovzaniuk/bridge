package com.openaiapi.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.openaiapi.config.RelayProperties;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives one headless Chromium tab loaded on the Python bridge's robot page.
 * All Playwright calls are confined to a single dedicated thread; inbound frames
 * arrive via an exposed binding and are handed off to {@link RelayChannel}.
 */
@Component
public class RelayBrowserSession {

    private static final Logger log = LoggerFactory.getLogger(RelayBrowserSession.class);

    private final RelayProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pwThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playwright-relay");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean pokeInFlight = new AtomicBoolean(false);
    private int pokeFailures;
    // Must be single-threaded FIFO: a poll response can batch several frames for the
    // same stream, and a concurrent/unordered executor here would let their delta
    // chunks race and interleave before reaching the SSE writer, corrupting output.
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "relay-dispatch");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private RelayChannel channel;

    public RelayBrowserSession(RelayProperties props) {
        this.props = props;
    }

    void setChannel(RelayChannel channel) {
        this.channel = channel;
    }

    public boolean isReady() {
        return ready.get();
    }

    public void start() {
        pwThread.submit(this::launchOnPwThread);
    }

    private void launchOnPwThread() {
        try {
            Map<String, String> env = new java.util.HashMap<>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            if (props.getBrowsersPath() != null && !props.getBrowsersPath().isBlank()) {
                env.put("PLAYWRIGHT_BROWSERS_PATH", props.getBrowsersPath());
            }
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            // Chromium's own OS-level sandbox can fail to initialize under restricted
            // accounts (locked-down remote machines, IDE-launched JVMs, RDP sessions,
            // no writable /dev/shm) -- disable it rather than let launch() throw.
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(props.isHeadless())
                    .setArgs(java.util.List.of("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"));
            if (props.getBrowserExecutablePath() != null && !props.getBrowserExecutablePath().isBlank()) {
                Path executable = Path.of(props.getBrowserExecutablePath()).toAbsolutePath();
                if (!Files.isRegularFile(executable)) {
                    throw new IllegalArgumentException("Browser executable not found: " + executable);
                }
                launchOptions.setExecutablePath(executable);
            }
            browser = playwright.chromium().launch(launchOptions);
            openPage();
        } catch (Exception e) {
            ready.set(false);
            if (channel != null) channel.failAll("failed to launch relay browser");
            log.error("failed to launch relay browser", e);
        }
    }

    private void openPage() {
        ready.set(false);
        String connId = java.util.UUID.randomUUID().toString();
        page = browser.newPage();
        page.exposeBinding("javaPush", (source, args) -> {
            String raw = String.valueOf(args[0]);
            dispatch.submit(() -> onInboundRaw(raw));
            return null;
        });
        page.addInitScript(
                "window.__cursorToken = " + mapper.valueToTree(props.getRelayToken()).toString() + ";"
                        + "window.__cursorConnId = " + mapper.valueToTree(connId).toString() + ";");
        page.navigate(props.getBridgeUrl());
        log.info("relay browser page loaded at {} (conn {})", props.getBridgeUrl(), connId);
    }

    private void onInboundRaw(String raw) {
        try {
            JsonNode frame = mapper.readTree(raw);
            String kind = frame.path("k").asText();
            if ("hello".equals(kind)) {
                if (ready.compareAndSet(false, true)) {
                    log.info("relay browser connected to host");
                }
                return;
            }
            if ("status".equals(kind)) {
                ready.set(frame.path("ready").asBoolean(false));
                return;
            }
            channel.onFrame(frame);
        } catch (Exception e) {
            log.warn("failed to parse inbound relay frame", e);
        }
    }

    /** Sends one JSON frame into the page; safe to call from any thread. */
    public void send(String json) {
        pwThread.submit(() -> {
            try {
                if (page != null && !page.isClosed()) {
                    page.evaluate("(s) => window.__cursorTx(s)", json);
                } else {
                    ready.set(false);
                    if (channel != null) channel.failAll("relay page is closed");
                    log.warn("page is null or closed, dropping frame");
                }
            } catch (Exception e) {
                ready.set(false);
                if (channel != null) channel.failAll("failed to send through relay page");
                log.warn("failed to push frame into relay page: {}", e.getMessage(), e);
            }
        });
    }

    // Headless Chromium defers delivery of exposeBinding/CDP events until the renderer is
    // "woken up" by another CDP command; without this, incoming WS frames stall indefinitely.
    @Scheduled(fixedDelay = 20, initialDelay = 1000)
    public void pokeEventLoop() {
        if (page == null || !pokeInFlight.compareAndSet(false, true)) {
            return;
        }
        pwThread.submit(() -> {
            try {
                if (page != null && !page.isClosed()) {
                    page.evaluate("1");
                    pokeFailures = 0;
                }
            } catch (Exception ignored) {
                if (++pokeFailures >= 3) {
                    ready.set(false);
                }
            } finally {
                pokeInFlight.set(false);
            }
        });
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void healthCheck() {
        pwThread.submit(() -> {
            boolean browserDead = browser == null || !browser.isConnected();
            boolean pageClosed = page == null || page.isClosed();
            if (!browserDead && !pageClosed) {
                return;
            }
            ready.set(false);
            if (channel != null) channel.failAll("relay browser reconnected");
            if (browserDead) {
                log.warn("relay browser disconnected, relaunching from scratch");
                closeQuietly();
                try {
                    launchOnPwThread();
                } catch (Exception e) {
                    log.error("failed to relaunch relay browser", e);
                }
            } else {
                log.warn("relay page closed, reopening");
                try {
                    openPage();
                } catch (Exception e) {
                    log.error("failed to reopen relay page", e);
                }
            }
        });
    }

    private void closeQuietly() {
        try {
            if (browser != null) browser.close();
        } catch (Exception ignored) {
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception ignored) {
        }
        browser = null;
        playwright = null;
        page = null;
    }

    @PreDestroy
    public void stop() {
        ready.set(false);
        if (channel != null) channel.failAll("relay is shutting down");
        try {
            pwThread.submit(this::closeQuietly).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        pwThread.shutdownNow();
        dispatch.shutdownNow();
    }
}
