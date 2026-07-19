package com.openaiapi.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatCompletionResponse;

public interface ChatCompletionProvider {

    String id();

    ChatCompletionResponse complete(ChatCompletionRequest request);

    default JsonNode completeJson(ChatCompletionRequest request) {
        return null;
    }
}
