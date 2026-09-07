package com.openaiapi.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Merges a sequence of OpenAI chat.completion.chunk objects into one chat.completion body. */
public class ChunkAccumulator {

    private final ObjectMapper mapper;
    private final StringBuilder content = new StringBuilder();
    private JsonNode toolCalls;
    private JsonNode usage;
    private String finishReason = "stop";
    private String id;
    private long created;
    private String model;
    private boolean any;

    public ChunkAccumulator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void add(JsonNode chunk) {
        any = true;
        id = chunk.path("id").asText(id);
        created = chunk.path("created").asLong(created);
        model = chunk.path("model").asText(model);
        if (chunk.has("usage") && !chunk.get("usage").isNull()) {
            usage = chunk.get("usage");
        }
        JsonNode choice = chunk.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        if (delta.has("content") && !delta.get("content").isNull()) {
            content.append(delta.get("content").asText());
        }
        if (delta.has("tool_calls")) {
            toolCalls = delta.get("tool_calls");
        }
        if (!choice.path("finish_reason").isMissingNode() && !choice.path("finish_reason").isNull()) {
            finishReason = choice.get("finish_reason").asText();
        }
    }

    public boolean hasAny() {
        return any;
    }

    public ObjectNode build() {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", id == null ? "chatcmpl-relay" : id);
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model);
        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        if (toolCalls != null) {
            message.putNull("content");
            message.set("tool_calls", toolCalls);
        } else {
            message.put("content", content.toString());
        }
        ObjectNode choice = mapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        root.set("choices", mapper.createArrayNode().add(choice));
        root.set("usage", usage == null ? mapper.nullNode() : usage);
        return root;
    }
}
