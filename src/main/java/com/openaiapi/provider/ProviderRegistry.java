package com.openaiapi.provider;

import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.config.AppProperties;
import com.openaiapi.provider.cursor.CursorUiProxyProvider;
import com.openaiapi.provider.dummy.DummyChatProvider;
import com.openaiapi.provider.llm.LlmChatProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ProviderRegistry {

    private final Map<String, ChatCompletionProvider> providers;
    private final AppProperties appProperties;
    private final CursorUiProxyProvider cursorUiProxyProvider;
    private final DummyChatProvider dummyChatProvider;
    private final LlmChatProvider llmChatProvider;

    public ProviderRegistry(
            List<ChatCompletionProvider> providers,
            AppProperties appProperties,
            CursorUiProxyProvider cursorUiProxyProvider,
            DummyChatProvider dummyChatProvider,
            LlmChatProvider llmChatProvider) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(ChatCompletionProvider::id, Function.identity(), (a, b) -> a));
        this.appProperties = appProperties;
        this.cursorUiProxyProvider = cursorUiProxyProvider;
        this.dummyChatProvider = dummyChatProvider;
        this.llmChatProvider = llmChatProvider;
    }

    public ChatCompletionProvider resolve(String model) {
        return resolve(null, model);
    }

    public ChatCompletionProvider resolve(ChatCompletionRequest request) {
        return resolve(request, request == null ? null : request.getModel());
    }

    private ChatCompletionProvider resolve(ChatCompletionRequest request, String model) {
        if (model != null
                && providers.containsKey(model)
                && !"dummy".equals(model)
                && !"cursor-ui".equals(model)
                && !"llm".equals(model)) {
            return providers.get(model);
        }
        if (model != null && model.toLowerCase().startsWith("dummy")) {
            return dummyChatProvider;
        }
        if (appProperties.getUpstream().isEnabled()) {
            return cursorUiProxyProvider;
        }
        if (appProperties.getLlm().isEnabled() && request != null && request.getTools() != null && !request.getTools().isEmpty()) {
            return llmChatProvider;
        }
        return dummyChatProvider;
    }
}
