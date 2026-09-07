package com.openaiapi.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ChunkAccumulatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode chunk(String content, String finishReason) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "chatcmpl-1");
        root.put("created", 100);
        root.put("model", "composer-2.5");
        ObjectNode delta = mapper.createObjectNode();
        if (content != null) {
            delta.put("content", content);
        }
        ObjectNode choice = mapper.createObjectNode();
        choice.put("index", 0);
        choice.set("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        } else {
            choice.putNull("finish_reason");
        }
        root.set("choices", mapper.createArrayNode().add(choice));
        return root;
    }

    @Test
    void mergesTextDeltasIntoContent() {
        ChunkAccumulator acc = new ChunkAccumulator(mapper);
        acc.add(chunk("Hel", null));
        acc.add(chunk("lo", null));
        acc.add(chunk(null, "stop"));
        ObjectNode result = acc.build();
        assertEquals("Hello", result.path("choices").path(0).path("message").path("content").asText());
        assertEquals("stop", result.path("choices").path(0).path("finish_reason").asText());
        assertEquals("chat.completion", result.path("object").asText());
    }

    @Test
    void emptyAccumulatorHasNoContent() {
        ChunkAccumulator acc = new ChunkAccumulator(mapper);
        assertFalse(acc.hasAny());
    }

    @Test
    void keepsToolCallsVerbatim() {
        ChunkAccumulator acc = new ChunkAccumulator(mapper);
        ObjectNode toolChunk = chunk(null, null);
        ObjectNode call = mapper.createObjectNode();
        call.put("id", "call_1");
        call.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", "read_file");
        fn.put("arguments", "{}");
        call.set("function", fn);
        ((ObjectNode) toolChunk.path("choices").path(0).path("delta")).set("tool_calls", mapper.createArrayNode().add(call));
        acc.add(toolChunk);
        acc.add(chunk(null, "tool_calls"));
        ObjectNode result = acc.build();
        assertTrue(result.path("choices").path(0).path("message").path("tool_calls").isArray());
        assertEquals("read_file", result.path("choices").path(0).path("message").path("tool_calls").path(0).path("function").path("name").asText());
        assertTrue(result.path("choices").path(0).path("message").path("content").isNull());
    }
}
