package com.openaiapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.config.RelayProperties;
import com.openaiapi.relay.RelayChannel;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPatternParser;

class OpenAiControllerTest {

    private MockMvc modelApi() throws Exception {
        JsonNode frame = new ObjectMapper().readTree("{\"data\":[{\"id\":\"composer-2.5\"},{\"id\":\"codex/gpt-6-astra\"},{\"id\":\"codex/gpt-6-astra:agent\"}]}");
        RelayChannel channel = new RelayChannel(null) {
            @Override
            public void requestModels(Consumer<JsonNode> callback) {
                callback.accept(frame);
            }
        };
        RelayProperties props = new RelayProperties();
        props.setBearerToken("test-token");
        return MockMvcBuilders.standaloneSetup(new OpenAiController(channel, props))
                .setControllerAdvice(new ApiExceptionHandler())
                .setPatternParser(new PathPatternParser()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"composer-2.5", "codex/gpt-6-astra", "codex/gpt-6-astra:agent"})
    void getsModelsWithFullNamespacedIds(String id) throws Exception {
        MockMvc api = modelApi();
        for (String prefix : new String[]{"/v1/models/", "/models/"}) {
            api.perform(get(prefix + id).header("Authorization", "Bearer test-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        }
    }

    @Test
    void namespacedModelStillRequiresAuthentication() throws Exception {
        modelApi().perform(get("/v1/models/codex/gpt-6-astra"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownNamespacedModelReturnsCatalogError() throws Exception {
        modelApi().perform(get("/v1/models/codex/nonexistent").header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("model 'codex/nonexistent' not found"));
    }

    @Test
    void forwardsContinueExecutionContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Conversation-Id", "conv-1");
        request.addHeader("X-Continue-Workspace", "C:\\remote\\project");
        request.addHeader("X-Continue-OS", "windows");
        request.addHeader("X-Continue-Shell", "powershell");
        request.addHeader("X-Unrelated", "ignored");
        Map<String, String> headers = new OpenAiController(null, new RelayProperties()).forwardedHeaders(request);
        assertEquals("conv-1", headers.get("x-conversation-id"));
        assertEquals("C:\\remote\\project", headers.get("x-continue-workspace"));
        assertEquals("windows", headers.get("x-continue-os"));
        assertEquals("powershell", headers.get("x-continue-shell"));
        assertEquals(4, headers.size());
    }

    @Test
    void requiresRemoteContextForTools() throws Exception {
        OpenAiController controller = new OpenAiController(null, new RelayProperties());
        var body = new ObjectMapper().readTree("{\"tools\":[{\"type\":\"function\"}]}");
        assertThrows(ResponseStatusException.class, () -> controller.requireRemoteContext(body, Map.of()));
        controller.requireRemoteContext(body, Map.of(
                "x-continue-workspace", "C:\\remote",
                "x-continue-os", "windows",
                "x-continue-shell", "powershell"
        ));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void codexStreamsWithoutContinueHeadersAndPreservesErrorStatus(boolean afterDelta) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode error = mapper.readTree("""
                {"k":"end","reason":"error","err":{"status":400,"code":"invalid_image","message":"Invalid image"}}
                """);
        RelayChannel channel = new RelayChannel(null) {
            @Override
            public String openStream(Consumer<JsonNode> callback) {
                if (afterDelta) {
                    var frame = mapper.createObjectNode().put("k", "d");
                    frame.putObject("data");
                    callback.accept(frame);
                }
                callback.accept(error);
                return "test";
            }
            @Override
            public void sendChat(String id, JsonNode body, Map<String, String> headers) {
                assertEquals(Map.of(), headers);
            }
        };
        RelayProperties props = new RelayProperties();
        props.setBearerToken("test-token");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        request.setContent("""
                {"model":"codex/gpt-6.2-astra","stream":true,"tools":[{"type":"function"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        new OpenAiController(channel, props).chatCompletions(request, response);
        assertEquals(afterDelta ? 200 : 400, response.getStatus());
        if (afterDelta) {
            org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("invalid_image"));
            org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().endsWith("data: [DONE]\n\n"));
        } else {
            assertEquals("invalid_image", mapper.readTree(response.getContentAsString()).at("/error/code").asText());
        }
    }
}
