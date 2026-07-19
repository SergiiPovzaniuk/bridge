package com.openaiapi.provider.cursor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatCompletionResponse;
import com.openaiapi.provider.ChatCompletionProvider;
import com.openaiapi.service.UpstreamProxyService;
import org.springframework.stereotype.Component;

@Component
public class CursorUiProxyProvider implements ChatCompletionProvider {

    private final UpstreamProxyService upstreamProxyService;
    private final ObjectMapper objectMapper;

    public CursorUiProxyProvider(UpstreamProxyService upstreamProxyService, ObjectMapper objectMapper) {
        this.upstreamProxyService = upstreamProxyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "cursor-ui";
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        try {
            return objectMapper.treeToValue(completeJson(request), ChatCompletionResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to map upstream chat response", ex);
        }
    }

    @Override
    public JsonNode completeJson(ChatCompletionRequest request) {
        return upstreamProxyService.proxyChatJson(request);
    }
}
