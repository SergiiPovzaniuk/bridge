package com.openaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LlmProxyService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LlmProxyService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    public boolean isEnabled() {
        return appProperties.getLlm().isEnabled();
    }

    public JsonNode proxyChatJson(ChatCompletionRequest request) {
        AppProperties.Llm llm = appProperties.getLlm();
        if (!llm.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM proxy is disabled");
        }
        if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Set app.llm.api-key / COPILOT_LLM_API_KEY for Agent tool mode");
        }

        ObjectNode body = objectMapper.valueToTree(request).deepCopy();
        body.put("stream", false);
        if (llm.getModel() != null && !llm.getModel().isBlank()) {
            body.put("model", llm.getModel());
        }

        var spec = restClient
                .post()
                .uri(url(llm.getChatPath()))
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.getApiKey())
                .body(body);
        try {
            return spec.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.valueOf(ex.getStatusCode().value()), ex.getResponseBodyAsString());
        }
    }

    private String url(String path) {
        String base = appProperties.getLlm().getBaseUrl().replaceAll("/$", "");
        String p = path.startsWith("/") ? path : "/" + path;
        return base + p;
    }
}
