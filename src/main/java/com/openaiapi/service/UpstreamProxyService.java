package com.openaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ModelListResponse;
import com.openaiapi.api.dto.ModelObject;
import com.openaiapi.config.AppProperties;
import com.openaiapi.crypto.PayloadCrypto;
import com.openaiapi.playwright.PlaywrightUiTransport;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UpstreamProxyService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final PlaywrightUiTransport playwrightUiTransport;
    private final CursorSessionService cursorSessionService;
    private final PayloadCrypto payloadCrypto;

    public UpstreamProxyService(
            AppProperties appProperties,
            ObjectMapper objectMapper,
            PlaywrightUiTransport playwrightUiTransport,
            CursorSessionService cursorSessionService,
            PayloadCrypto payloadCrypto) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.playwrightUiTransport = playwrightUiTransport;
        this.cursorSessionService = cursorSessionService;
        this.payloadCrypto = payloadCrypto;
        this.restClient = RestClient.builder().requestFactory(upstreamRequestFactory(appProperties)).build();
    }

    private static JdkClientHttpRequestFactory upstreamRequestFactory(AppProperties appProperties) {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            HttpClient httpClient = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(Math.max(60_000, appProperties.getUpstream().getResponseTimeoutMs())));
            return factory;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure upstream HTTPS client", ex);
        }
    }

    public boolean isEnabled() {
        return appProperties.getUpstream().isEnabled();
    }

    public Map<String, Object> proxyHealth() {
        return restClient.get()
                .uri(url(appProperties.getUpstream().getHealthPath()))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public ModelListResponse proxyModels() {
        var spec = restClient.get().uri(url(appProperties.getUpstream().getModelsPath()));
        String apiKey = appProperties.getUpstream().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }
        try {
            return spec.retrieve().body(ModelListResponse.class);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getResponseBodyAsString());
        }
    }

    public ModelObject proxyModel(String id) {
        ModelListResponse list = proxyModels();
        return list.getData().stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found: " + id));
    }

    public JsonNode proxyChatJson(ChatCompletionRequest request) {
        cursorSessionService.prepareRequest(request);
        try {
            return dispatchChat(request);
        } catch (com.openaiapi.playwright.PlaywrightAutomationException ex) {
            if (!isResumeFailed(ex) || request.getCursor() == null) {
                throw ex;
            }
            String model = request.getModel();
            if (model != null) {
                cursorSessionService.resetModel(model);
            }
            request.getCursor().setAgentId(null);
            return dispatchChat(request);
        }
    }

    private JsonNode dispatchChat(ChatCompletionRequest request) {
        if (appProperties.getUpstream().isUsePlaywright()) {
            return playwrightUiTransport.proxyChat(request);
        }
        ObjectNode body = objectMapper.valueToTree(request);
        body.put("stream", false);
        return postChat(body);
    }

    @SuppressWarnings("unchecked")
    private boolean isResumeFailed(com.openaiapi.playwright.PlaywrightAutomationException ex) {
        Map<String, Object> body = ex.getErrorBody();
        if (body == null) {
            return false;
        }
        Object error = body.get("error");
        if (!(error instanceof Map<?, ?> err)) {
            return false;
        }
        Object code = err.get("code");
        return "resume_failed".equals(String.valueOf(code));
    }

    public Map<String, Object> proxyUiReset() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = withTransportKey(restClient.post().uri(url("/ui/reset")))
                    .retrieve()
                    .body(Map.class);
            return body != null ? body : Map.of("status", "ok");
        } catch (RestClientResponseException ex) {
            throw upstreamError(ex);
        }
    }

    private JsonNode postChat(JsonNode body) {
        try {
            String sealed = payloadCrypto.seal(objectMapper.writeValueAsString(body));
            var spec = withTransportKey(restClient.post()
                    .uri(url(appProperties.getUpstream().getChatPath()))
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(sealed));
            String apiKey = appProperties.getUpstream().getApiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + apiKey);
            }
            String responseText = spec.retrieve().body(String.class);
            if (responseText == null || responseText.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty upstream response");
            }
            return objectMapper.readTree(payloadCrypto.unseal(responseText.trim()));
        } catch (RestClientResponseException ex) {
            throw upstreamError(ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Upstream seal/unseal failed: " + ex.getMessage(), ex);
        }
    }

    private String url(String path) {
        String base = appProperties.getUpstream().getBaseUrl().replaceAll("/$", "");
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }

    private RestClient.RequestHeadersSpec<?> withTransportKey(RestClient.RequestHeadersSpec<?> spec) {
        String key = appProperties.getUpstream().getTransportKey();
        if (key != null && !key.isBlank()) {
            return spec.header("x-transport-key", key);
        }
        return spec;
    }

    private ResponseStatusException upstreamError(RestClientResponseException ex) {
        String raw = ex.getResponseBodyAsString();
        try {
            if (raw != null && raw.startsWith(PayloadCrypto.PREFIX)) {
                raw = payloadCrypto.unseal(raw.trim());
            }
        } catch (Exception ignored) {
            // keep sealed/raw body in exception message
        }
        return new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()), raw);
    }
}
