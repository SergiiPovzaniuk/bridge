package com.openaiapi.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ToolDefinition;
import com.openaiapi.config.AppProperties;
import com.openaiapi.provider.cursor.CursorUiProxyProvider;
import com.openaiapi.provider.dummy.DummyChatProvider;
import com.openaiapi.provider.llm.LlmChatProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    private AppProperties appProperties;
    private ProviderRegistry registry;
    private DummyChatProvider dummyChatProvider;
    private CursorUiProxyProvider cursorUiProxyProvider;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getUpstream().setEnabled(true);
        dummyChatProvider = mock(DummyChatProvider.class);
        when(dummyChatProvider.id()).thenReturn("dummy");
        cursorUiProxyProvider = mock(CursorUiProxyProvider.class);
        when(cursorUiProxyProvider.id()).thenReturn("cursor-ui");
        LlmChatProvider llmChatProvider = mock(LlmChatProvider.class);
        when(llmChatProvider.id()).thenReturn("llm");
        registry = new ProviderRegistry(
                List.of(dummyChatProvider, cursorUiProxyProvider, llmChatProvider),
                appProperties,
                cursorUiProxyProvider,
                dummyChatProvider,
                llmChatProvider);
    }

    @Test
    void dummyModelRoutesToDummy() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("dummy-gpt");
        assertThat(registry.resolve(request)).isSameAs(dummyChatProvider);
    }

    @Test
    void cursorModelRoutesToCursor() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("composer-2.5");
        assertThat(registry.resolve(request)).isSameAs(cursorUiProxyProvider);
    }

    @Test
    void cursorModelWithToolsRoutesToCursor() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("composer-2.5");
        ToolDefinition tool = new ToolDefinition();
        request.setTools(List.of(tool));
        assertThat(registry.resolve(request)).isSameAs(cursorUiProxyProvider);
    }
}
