package com.openaiapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatMessage;
import com.openaiapi.api.dto.ToolDefinition;
import com.openaiapi.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CursorSessionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AppProperties appProperties;
    private CursorSessionService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getUpstream().setSessionResume(true);
        appProperties.getUpstream().setSessionIdleMs(1_800_000L);
        appProperties.getUpstream().setCursorCwd("/workspace/project");
        service = new CursorSessionService(appProperties);
    }

    @Test
    void neverInjectsHostCwd() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("composer-2.5");
        request.setMessages(List.of(user("hello")));

        service.prepareRequest(request);

        assertThat(request.getCursor().getCwd()).isNull();
        assertThat(request.getCursor().getMode()).isEqualTo("ask");
    }

    @Test
    void toolsForceAskMode() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("composer-2.5");
        request.setMessages(List.of(user("create file")));
        request.setTools(List.of(new ToolDefinition()));

        service.prepareRequest(request);

        assertThat(request.getCursor().getMode()).isEqualTo("ask");
        assertThat(request.getCursor().getCwd()).isNull();
    }

    @Test
    void freshChatDoesNotResume() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("model", "composer-2.5");
        response.putObject("cursor").put("agentId", "session-abc");

        ChatCompletionRequest first = new ChatCompletionRequest();
        first.setModel("composer-2.5");
        first.setMessages(List.of(user("hello")));
        service.prepareRequest(first);
        service.recordFromResponse(response);

        ChatCompletionRequest fresh = new ChatCompletionRequest();
        fresh.setModel("composer-2.5");
        fresh.setMessages(List.of(user("brand new chat")));
        service.prepareRequest(fresh);

        assertThat(fresh.getCursor().getAgentId()).isNull();
    }

    @Test
    void continuesSameConversationWithAgentId() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("model", "composer-2.5");
        response.putObject("cursor").put("agentId", "session-abc");

        ChatCompletionRequest first = new ChatCompletionRequest();
        first.setModel("composer-2.5");
        first.setMessages(List.of(user("hello")));
        service.prepareRequest(first);
        service.recordFromResponse(response);

        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assistant.setContent(objectMapper.getNodeFactory().textNode("hi"));

        ChatCompletionRequest followUp = new ChatCompletionRequest();
        followUp.setModel("composer-2.5");
        followUp.setMessages(List.of(user("hello"), assistant, user("again")));
        service.prepareRequest(followUp);

        assertThat(followUp.getCursor().getAgentId()).isEqualTo("session-abc");
    }

    @Test
    void fingerprintChangeDoesNotResetOtherChats() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("model", "composer-2.5");
        response.putObject("cursor").put("agentId", "session-abc");

        ChatCompletionRequest first = new ChatCompletionRequest();
        first.setModel("composer-2.5");
        first.setMessages(List.of(user("hello")));
        service.prepareRequest(first);
        service.recordFromResponse(response);

        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assistant.setContent(objectMapper.getNodeFactory().textNode("hi"));

        ChatCompletionRequest other = new ChatCompletionRequest();
        other.setModel("composer-2.5");
        other.setMessages(List.of(user("different first message"), assistant, user("again")));
        service.prepareRequest(other);
        assertThat(other.getCursor().getAgentId()).isNull();

        ChatCompletionRequest resumeOriginal = new ChatCompletionRequest();
        resumeOriginal.setModel("composer-2.5");
        resumeOriginal.setMessages(List.of(user("hello"), assistant, user("again")));
        service.prepareRequest(resumeOriginal);
        assertThat(resumeOriginal.getCursor().getAgentId()).isEqualTo("session-abc");
    }

    @Test
    void resetClearsSessions() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("model", "composer-2.5");
        response.putObject("cursor").put("agentId", "session-abc");

        ChatCompletionRequest first = new ChatCompletionRequest();
        first.setModel("composer-2.5");
        first.setMessages(List.of(user("hello")));
        service.prepareRequest(first);
        service.recordFromResponse(response);

        service.resetAll();

        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assistant.setContent(objectMapper.getNodeFactory().textNode("hi"));
        ChatCompletionRequest followUp = new ChatCompletionRequest();
        followUp.setModel("composer-2.5");
        followUp.setMessages(List.of(user("hello"), assistant, user("again")));
        service.prepareRequest(followUp);

        assertThat(followUp.getCursor().getAgentId()).isNull();
    }

    @Test
    void clearByModelDoesNotClearLongerModelPrefix() {
        ObjectNode response25 = objectMapper.createObjectNode();
        response25.put("model", "composer-2.5");
        response25.putObject("cursor").put("agentId", "session-25");

        ChatCompletionRequest req25 = new ChatCompletionRequest();
        req25.setModel("composer-2.5");
        req25.setMessages(List.of(user("hello-25")));
        service.prepareRequest(req25);
        service.recordFromResponse(response25);

        ObjectNode response2 = objectMapper.createObjectNode();
        response2.put("model", "composer-2");
        response2.putObject("cursor").put("agentId", "session-2");

        ChatCompletionRequest req2 = new ChatCompletionRequest();
        req2.setModel("composer-2");
        req2.setMessages(List.of(user("hello-2")));
        service.prepareRequest(req2);
        service.recordFromResponse(response2);

        service.resetModel("composer-2");

        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assistant.setContent(objectMapper.getNodeFactory().textNode("hi"));

        ChatCompletionRequest follow25 = new ChatCompletionRequest();
        follow25.setModel("composer-2.5");
        follow25.setMessages(List.of(user("hello-25"), assistant, user("again")));
        service.prepareRequest(follow25);
        assertThat(follow25.getCursor().getAgentId()).isEqualTo("session-25");
    }

    @Test
    void isFreshChatDetectsHistory() {
        assertThat(CursorSessionService.isFreshChat(List.of(user("hi")))).isTrue();
        ChatMessage assistant = new ChatMessage();
        assistant.setRole("assistant");
        assertThat(CursorSessionService.isFreshChat(List.of(user("hi"), assistant))).isFalse();
    }

    private static ChatMessage user(String text) {
        ChatMessage message = new ChatMessage();
        message.setRole("user");
        message.setContent(new ObjectMapper().getNodeFactory().textNode(text));
        return message;
    }
}
