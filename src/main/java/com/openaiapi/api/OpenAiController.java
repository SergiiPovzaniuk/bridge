package com.openaiapi.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openaiapi.config.RelayProperties;
import com.openaiapi.relay.ChunkAccumulator;
import com.openaiapi.relay.RelayChannel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Thin OpenAI-compatible passthrough: every request/response body is opaque JSON relayed as-is. */
@RestController
public class OpenAiController {

    private static final Object POISON = new Object();
    private static final String[] FORWARD_HEADERS = {
            "x-conversation-id", "x-cursor-mode", "x-continue-workspace", "x-continue-os", "x-continue-shell"
    };

    private final RelayChannel channel;
    private final RelayProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiController(RelayChannel channel, RelayProperties props) {
        this.channel = channel;
        this.props = props;
    }

    @GetMapping({"/v1/models", "/models"})
    public void listModels(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireBearer(req);
        BlockingQueue<Object> q = new ArrayBlockingQueue<>(1);
        channel.requestModels(frame -> q.add(frame));
        JsonNode frame = (JsonNode) await(q, props.getResponseTimeoutMs());
        if (frame == null || frame.has("err")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "models request failed");
        }
        writeJson(resp, mapper.createObjectNode().put("object", "list").set("data", frame.get("data")));
    }

    @GetMapping({"/v1/models/{id}", "/models/{id}"})
    public void getModel(@PathVariable String id, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireBearer(req);
        BlockingQueue<Object> q = new ArrayBlockingQueue<>(1);
        channel.requestModels(frame -> q.add(frame));
        JsonNode frame = (JsonNode) await(q, props.getResponseTimeoutMs());
        if (frame == null || frame.has("err")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "models request failed");
        }
        for (JsonNode m : frame.get("data")) {
            if (id.equals(m.path("id").asText())) {
                writeJson(resp, m);
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "model '" + id + "' not found");
    }

    @PostMapping({"/v1/embeddings", "/embeddings"})
    public void embeddings(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireBearer(req);
        throw notImplemented("Cursor SDK is an agent SDK and cannot serve embeddings. Configure a separate embeddings provider in Continue.");
    }

    @PostMapping({"/v1/completions", "/completions"})
    public void completions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireBearer(req);
        throw notImplemented("Cursor SDK cannot serve raw FIM/completions. Configure a separate autocomplete provider in Continue.");
    }

    @PostMapping(value = {"/v1/chat/completions", "/chat/completions"}, produces = {MediaType.APPLICATION_JSON_VALUE, "text/event-stream"})
    public void chatCompletions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        requireBearer(req);
        JsonNode body = mapper.readTree(req.getInputStream());
        boolean wantStream = body.path("stream").asBoolean(false);
        Map<String, String> headers = forwardedHeaders(req);

        if (wantStream) {
            streamCompletion(body, headers, resp);
        } else {
            nonStreamCompletion(body, headers, resp);
        }
    }

    private void streamCompletion(JsonNode body, Map<String, String> headers, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("text/event-stream");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        Writer writer = resp.getWriter();

        BlockingQueue<Object> q = new ArrayBlockingQueue<>(256);
        String sid = channel.openStream(frame -> {
            q.add(frame);
            if ("end".equals(frame.path("k").asText())) {
                q.add(POISON);
            }
        });
        channel.sendChat(sid, body, headers);
        try {
            while (true) {
                Object item = await(q, props.getResponseTimeoutMs());
                if (item == null || item == POISON) {
                    break;
                }
                JsonNode frame = (JsonNode) item;
                String kind = frame.path("k").asText();
                if ("d".equals(kind)) {
                    writer.write("data: " + mapper.writeValueAsString(frame.get("data")) + "\n\n");
                    writer.flush();
                } else if ("end".equals(kind)) {
                    if ("error".equals(frame.path("reason").asText())) {
                        writeSseError(writer, frame.path("err"));
                    }
                    writer.write("data: [DONE]\n\n");
                    writer.flush();
                }
            }
        } catch (IOException e) {
            // client disconnected mid-stream; stop the upstream run instead of leaking it
            channel.cancel(sid);
        } finally {
            channel.closeStream(sid);
        }
    }

    private void nonStreamCompletion(JsonNode body, Map<String, String> headers, HttpServletResponse resp) throws IOException {
        BlockingQueue<Object> q = new ArrayBlockingQueue<>(256);
        String sid = channel.openStream(frame -> {
            q.add(frame);
            if ("end".equals(frame.path("k").asText())) {
                q.add(POISON);
            }
        });
        channel.sendChat(sid, body, headers);
        ChunkAccumulator acc = new ChunkAccumulator(mapper);
        JsonNode errFrame = null;
        try {
            while (true) {
                Object item = await(q, props.getResponseTimeoutMs());
                if (item == null || item == POISON) {
                    break;
                }
                JsonNode frame = (JsonNode) item;
                String kind = frame.path("k").asText();
                if ("d".equals(kind)) {
                    acc.add(frame.get("data"));
                } else if ("end".equals(kind) && "error".equals(frame.path("reason").asText())) {
                    errFrame = frame.path("err");
                }
            }
        } finally {
            channel.closeStream(sid);
        }
        if (errFrame != null) {
            writeError(resp, errFrame);
            return;
        }
        if (!acc.hasAny()) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "no response from bridge");
        }
        writeJson(resp, acc.build());
    }

    private void writeSseError(Writer writer, JsonNode err) throws IOException {
        writer.write("data: " + mapper.writeValueAsString(Map.of("error", errorBody(err))) + "\n\n");
    }

    private void writeError(HttpServletResponse resp, JsonNode err) throws IOException {
        resp.setStatus(err.path("status").asInt(502));
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(resp.getWriter(), Map.of("error", errorBody(err)));
    }

    private Map<String, Object> errorBody(JsonNode err) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", err.path("message").asText("internal error"));
        body.put("type", err.path("type").asText("server_error"));
        if (!err.path("code").isMissingNode() && !err.path("code").isNull()) {
            body.put("code", err.path("code").asText());
        }
        return body;
    }

    Map<String, String> forwardedHeaders(HttpServletRequest req) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String h : FORWARD_HEADERS) {
            String v = req.getHeader(h);
            if (v != null) {
                out.put(h, v);
            }
        }
        return out;
    }

    private ResponseStatusException notImplemented(String message) {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, message);
    }

    private void requireBearer(HttpServletRequest req) {
        if (props.getBearerToken().isBlank()) {
            return;
        }
        String auth = req.getHeader("Authorization");
        if (!("Bearer " + props.getBearerToken()).equals(auth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid API key");
        }
    }

    private void writeJson(HttpServletResponse resp, Object body) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(resp.getWriter(), body);
    }

    private <T> T await(BlockingQueue<T> q, long timeoutMs) {
        try {
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
