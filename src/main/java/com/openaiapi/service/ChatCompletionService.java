package com.openaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatCompletionResponse;
import com.openaiapi.provider.ChatCompletionProvider;
import com.openaiapi.provider.ProviderRegistry;
import org.springframework.stereotype.Service;

@Service
public class ChatCompletionService {

    private final ProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public ChatCompletionService(ProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        return providerRegistry.resolve(request).complete(request);
    }

    public JsonNode completeJson(ChatCompletionRequest request) {
        ChatCompletionProvider provider = providerRegistry.resolve(request);
        JsonNode json = provider.completeJson(request);
        if (json != null) {
            return json;
        }
        return objectMapper.valueToTree(provider.complete(request));
    }
}
