package com.openaiapi.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
        call.put("index", 0);
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

    @Test
    void mergesFragmentedParallelToolCalls() {
        ChunkAccumulator acc = new ChunkAccumulator(mapper);
        ObjectNode first = chunk(null, null);
        ObjectNode call0 = mapper.createObjectNode().put("index", 0).put("id", "call_1").put("type", "function");
        call0.set("function", mapper.createObjectNode().put("name", "read_file").put("arguments", "{\"file"));
        ObjectNode call1 = mapper.createObjectNode().put("index", 1).put("id", "call_2").put("type", "function");
        call1.set("function", mapper.createObjectNode().put("name", "ls").put("arguments", "{\"dir"));
        ((ObjectNode) first.path("choices").path(0).path("delta")).set("tool_calls", mapper.createArrayNode().add(call0).add(call1));
        ObjectNode second = chunk(null, null);
        ObjectNode call0End = mapper.createObjectNode().put("index", 0);
        call0End.set("function", mapper.createObjectNode().put("arguments", "path\":\"a.py\"}"));
        ObjectNode call1End = mapper.createObjectNode().put("index", 1);
        call1End.set("function", mapper.createObjectNode().put("arguments", "Path\":\".\"}"));
        ((ObjectNode) second.path("choices").path(0).path("delta")).set("tool_calls", mapper.createArrayNode().add(call0End).add(call1End));
        acc.add(first);
        acc.add(second);
        acc.add(chunk(null, "tool_calls"));
        JsonNode calls = acc.build().path("choices").path(0).path("message").path("tool_calls");
        assertEquals("{\"filepath\":\"a.py\"}", calls.path(0).path("function").path("arguments").asText());
        assertEquals("{\"dirPath\":\".\"}", calls.path(1).path("function").path("arguments").asText());
    }
}
