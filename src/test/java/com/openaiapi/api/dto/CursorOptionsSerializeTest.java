package com.openaiapi.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

class CursorOptionsSerializeTest {

    @Test
    void serializesAgentIdAsCamelCaseDespiteSnakeCaseMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        ChatCompletionRequest.CursorOptions cursor = new ChatCompletionRequest.CursorOptions();
        cursor.setAgentId("session-abc");
        cursor.setMode("ask");

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("auto");
        request.setCursor(cursor);

        String json = mapper.writeValueAsString(request);
        assertThat(json).contains("\"agentId\":\"session-abc\"");
        assertThat(json).doesNotContain("\"agent_id\"");
        assertThat(json).contains("\"mode\":\"ask\"");
    }

    @Test
    void deserializesAgentIdCamelCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        ChatCompletionRequest request = mapper.readValue(
                "{\"model\":\"auto\",\"cursor\":{\"agentId\":\"sid-1\",\"mode\":\"ask\"}}",
                ChatCompletionRequest.class);

        assertThat(request.getCursor().getAgentId()).isEqualTo("sid-1");
    }
}
