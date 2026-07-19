package com.openaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.Writer;
import org.springframework.stereotype.Service;

@Service
public class StreamAdapterService {

    private final ObjectMapper objectMapper;

    public StreamAdapterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeAsSse(JsonNode completion, Writer writer) throws IOException {
        String id = text(completion, "id", "chatcmpl-local");
        String model = text(completion, "model", "unknown");
        long created = completion.path("created").asLong(System.currentTimeMillis() / 1000);

        ObjectNode roleChunk = chunk(id, model, created);
        ObjectNode roleChoice = roleChunk.withArray("choices").addObject();
        roleChoice.put("index", 0);
        roleChoice.putObject("delta").put("role", "assistant");
        write(writer, roleChunk);

        JsonNode choice = completion.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode toolCalls = message.path("tool_calls");
        String finishReason = choice.path("finish_reason").asText("stop");

        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonNode call = toolCalls.get(i);
                ObjectNode header = chunk(id, model, created);
                ObjectNode hChoice = header.withArray("choices").addObject();
                hChoice.put("index", 0);
                ArrayNode deltas = hChoice.putObject("delta").putArray("tool_calls");
                ObjectNode tc = deltas.addObject();
                tc.put("index", i);
                tc.put("id", call.path("id").asText());
                tc.put("type", call.path("type").asText("function"));
                ObjectNode fn = tc.putObject("function");
                fn.put("name", call.path("function").path("name").asText());
                fn.put("arguments", "");
                write(writer, header);

                ObjectNode argsChunk = chunk(id, model, created);
                ObjectNode aChoice = argsChunk.withArray("choices").addObject();
                aChoice.put("index", 0);
                ArrayNode aDeltas = aChoice.putObject("delta").putArray("tool_calls");
                ObjectNode aTc = aDeltas.addObject();
                aTc.put("index", i);
                aTc.putObject("function")
                        .put("arguments", call.path("function").path("arguments").asText("{}"));
                write(writer, argsChunk);
            }
            finishReason = "tool_calls";
        } else {
            String content = extractContent(message.path("content"));
            ObjectNode contentChunk = chunk(id, model, created);
            ObjectNode cChoice = contentChunk.withArray("choices").addObject();
            cChoice.put("index", 0);
            cChoice.putObject("delta").put("content", content == null ? "" : content);
            write(writer, contentChunk);
        }

        ObjectNode finish = chunk(id, model, created);
        ObjectNode fChoice = finish.withArray("choices").addObject();
        fChoice.put("index", 0);
        fChoice.putObject("delta");
        fChoice.put("finish_reason", finishReason == null || finishReason.isBlank() || "null".equals(finishReason)
                ? "stop"
                : finishReason);
        write(writer, finish);

        JsonNode usage = completion.get("usage");
        if (usage != null && !usage.isNull() && usage.isObject()) {
            ObjectNode usageChunk = chunk(id, model, created);
            usageChunk.set("usage", usage.deepCopy());
            write(writer, usageChunk);
        }

        writer.write("data: [DONE]\n\n");
        writer.flush();
    }

    private ObjectNode chunk(String id, String model, long created) {
        ObjectNode chunk = objectMapper.createObjectNode();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.putArray("choices");
        return chunk;
    }

    private void write(Writer writer, JsonNode chunk) throws IOException {
        writer.write("data: ");
        writer.write(objectMapper.writeValueAsString(chunk));
        writer.write("\n\n");
        writer.flush();
    }

    private String extractContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.has("text")) {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    sb.append(part.get("text").asText(""));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
