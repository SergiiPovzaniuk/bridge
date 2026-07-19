package com.openaiapi.provider.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatCompletionResponse;
import com.openaiapi.provider.ChatCompletionProvider;
import com.openaiapi.service.LlmProxyService;
import org.springframework.stereotype.Component;

/**
 * Forwards Copilot Agent requests (messages + tools) to an OpenAI-compatible model.
 * The model emits standard tool_calls; VS Code runs create_file / edit / terminal tools.
 */
@Component
public class LlmChatProvider implements ChatCompletionProvider {

    private final LlmProxyService llmProxyService;
    private final ObjectMapper objectMapper;

    public LlmChatProvider(LlmProxyService llmProxyService, ObjectMapper objectMapper) {
        this.llmProxyService = llmProxyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "llm";
    }

    @Override
    public ChatCompletionResponse complete(ChatCompletionRequest request) {
        try {
            return objectMapper.treeToValue(completeJson(request), ChatCompletionResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to map LLM chat response", ex);
        }
    }

    @Override
    public JsonNode completeJson(ChatCompletionRequest request) {
        return llmProxyService.proxyChatJson(request);
    }
}
