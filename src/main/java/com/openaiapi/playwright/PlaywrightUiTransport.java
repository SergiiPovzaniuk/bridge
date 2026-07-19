package com.openaiapi.playwright;

import static com.openaiapi.playwright.UiSelectors.A0;
import static com.openaiapi.playwright.UiSelectors.P1;
import static com.openaiapi.playwright.UiSelectors.P2;
import static com.openaiapi.playwright.UiSelectors.P3;
import static com.openaiapi.playwright.UiSelectors.STATE_DONE;
import static com.openaiapi.playwright.UiSelectors.STATE_ERROR;
import static com.openaiapi.playwright.UiSelectors.testId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.config.AppProperties;
import com.openaiapi.crypto.PayloadCrypto;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlaywrightUiTransport {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightUiTransport.class);
    private static final int CHUNK = 256 * 1024;

    private final PlaywrightBrowserSession browserSession;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final PayloadCrypto payloadCrypto;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public PlaywrightUiTransport(
            PlaywrightBrowserSession browserSession,
            AppProperties appProperties,
            ObjectMapper objectMapper,
            PayloadCrypto payloadCrypto) {
        this.browserSession = browserSession;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.payloadCrypto = payloadCrypto;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public JsonNode proxyChat(ChatCompletionRequest request) {
        if (!browserSession.isReady()) {
            throw new BrowserNotReadyException("Playwright session is not ready");
        }
        if (!lock.tryLock()) {
            throw new ConcurrentBusyException(
                    "Another chat is already in progress (concurrency=1). Retry later.");
        }
        busy.set(true);
        try {
            Page page = browserSession.page();
            try {
                ObjectNode body = objectMapper.valueToTree(request);
                body.put("stream", false);
                String payload = payloadCrypto.seal(objectMapper.writeValueAsString(body));
                String path = appProperties.getUpstream().getChatPath();
                if (path == null || path.isBlank()) {
                    path = "/ui/run";
                }
                String transportKey = appProperties.getUpstream().getTransportKey();
                if (!StringUtils.hasText(transportKey)) {
                    throw new PlaywrightAutomationException("TRANSPORT_KEY is required for UI transport");
                }

                page.locator(testId(A0))
                        .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
                page.evaluate("(k) => { window.__k = k; }", transportKey);
                injectPayload(page, payload);
                page.evaluate(
                        """
                        ({ path, transportKey }) => {
                          const payload = window.__b;
                          window.__b = '';
                          const p3 = document.querySelector('[data-testid="p3"]');
                          const p1 = document.querySelector('[data-testid="p1"]');
                          const p2 = document.querySelector('[data-testid="p2"]');
                          const STATE = { '0': '0 idle', '1': '1 load', '2': '2 done', '3': '3 err' };
                          const setState = (s) => {
                            if (!p3) return;
                            p3.dataset.state = s;
                            p3.textContent = STATE[s] ?? s;
                          };
                          if (p1) p1.value = '';
                          if (p2) p2.value = '';
                          setState('1');
                          const headers = {
                            'Content-Type': 'text/plain; charset=utf-8',
                            'x-transport-key': transportKey,
                          };
                          if (typeof window.__x?.run === 'function') {
                            void window.__x.run(payload, path, transportKey);
                            return;
                          }
                          const gen = (window.__gen = (window.__gen || 0) + 1);
                          void fetch(path, {
                            method: 'POST',
                            headers,
                            body: payload,
                          }).then(async (res) => {
                            if (gen !== window.__gen) return;
                            const text = await res.text();
                            if (gen !== window.__gen) return;
                            if (p2) p2.value = String(res.status);
                            if (p1) p1.value = text;
                            setState(res.ok ? '2' : '3');
                          }).catch((err) => {
                            if (gen !== window.__gen) return;
                            if (p2) p2.value = '';
                            if (p1) p1.value = String(err);
                            setState('3');
                          });
                        }
                        """,
                        Map.of("path", path, "transportKey", transportKey));
                waitForDoneOrThrow(page);

                String state = page.locator(testId(P3)).getAttribute("data-state");
                String httpStatus = page.locator(testId(P2)).inputValue().trim();
                String responseText = page.locator(testId(P1)).inputValue().trim();

                if (STATE_ERROR.equals(state)) {
                    throw upstreamError(httpStatus, responseText);
                }
                if (responseText.isBlank()) {
                    recoverPage();
                    throw new PlaywrightAutomationException("UI returned empty response");
                }

                String plain = unsealResponse(responseText);
                JsonNode parsed = objectMapper.readTree(plain);
                if (parsed.has("error")) {
                    int status = parseStatus(httpStatus, 502);
                    if (status < 400) {
                        status = 502;
                    }
                    throw upstreamError(String.valueOf(status), responseText);
                }
                if (!parsed.has("choices") || !parsed.get("choices").isArray() || parsed.get("choices").isEmpty()) {
                    recoverPage();
                    throw new PlaywrightAutomationException("Upstream response has no choices: " + plain);
                }
                return parsed;
            } catch (PlaywrightAutomationException | BrowserNotReadyException | ConcurrentBusyException ex) {
                throw ex;
            } catch (Exception ex) {
                recoverPage();
                throw new PlaywrightAutomationException("Playwright UI automation failed: " + ex.getMessage(), ex);
            }
        } finally {
            busy.set(false);
            lock.unlock();
        }
    }

    private String unsealResponse(String responseText) {
        try {
            return payloadCrypto.unseal(responseText);
        } catch (IllegalArgumentException ex) {
            throw new PlaywrightAutomationException("Failed to unseal UI response: " + ex.getMessage(), ex);
        }
    }

    private void recoverPage() {
        try {
            browserSession.reload();
        } catch (Exception ex) {
            log.warn("Failed to reload Playwright page after error: {}", ex.getMessage());
        }
    }

    private void injectPayload(Page page, String payload) {
        page.evaluate("() => { window.__b = ''; }");
        for (int i = 0; i < payload.length(); i += CHUNK) {
            String part = payload.substring(i, Math.min(payload.length(), i + CHUNK));
            page.evaluate("(c) => { window.__b += c; }", part);
        }
    }

    private void waitForDone(Page page) {
        Locator status = page.locator(testId(P3));
        long deadline = System.currentTimeMillis() + appProperties.getUpstream().getResponseTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            String state = status.getAttribute("data-state");
            if (STATE_DONE.equals(state) || STATE_ERROR.equals(state)) {
                return;
            }
            page.waitForTimeout(200);
        }
        throw new PlaywrightAutomationException("Timed out waiting for UI response");
    }

    private void waitForDoneOrThrow(Page page) {
        try {
            waitForDone(page);
        } catch (PlaywrightAutomationException ex) {
            recoverPage();
            throw ex;
        }
    }

    private int parseStatus(String httpStatus, int fallback) {
        try {
            if (httpStatus != null && !httpStatus.isBlank()) {
                return Integer.parseInt(httpStatus.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private PlaywrightAutomationException upstreamError(String httpStatus, String responseText) {
        int status = parseStatus(httpStatus, 502);
        if (status < 400) {
            status = 502;
        }
        try {
            String plain = payloadCrypto.unseal(responseText);
            Map<String, Object> body = objectMapper.readValue(plain, Map.class);
            return new PlaywrightAutomationException(status, body);
        } catch (Exception ignored) {
            return new PlaywrightAutomationException("UI error (HTTP " + httpStatus + "): " + responseText);
        }
    }
}
