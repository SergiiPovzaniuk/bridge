package com.openaiapi.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Multiplexes concurrent chat streams over browser-mediated HTTP long polling. */
@Component
public class RelayChannel {

    private static final Logger log = LoggerFactory.getLogger(RelayChannel.class);
    private final RelayBrowserSession session;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Consumer<JsonNode>> streams = new ConcurrentHashMap<>();

    public RelayChannel(RelayBrowserSession session) {
        this.session = session;
    }

    @PostConstruct
    void init() {
        session.setChannel(this);
        session.start();
    }

    public boolean isReady() {
        return session.isReady();
    }

    public String openStream(Consumer<JsonNode> onFrame) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        streams.put(sid, onFrame);
        return sid;
    }

    public void closeStream(String sid) {
        streams.remove(sid);
    }

    public void sendChat(String sid, JsonNode payload, Map<String, String> headers) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("k", "send");
        frame.put("sid", sid);
        frame.set("payload", payload);
        frame.set("headers", mapper.valueToTree(headers));
        emit(frame);
    }

    public void cancel(String sid) {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("k", "cancel");
        frame.put("sid", sid);
        emit(frame);
    }

    public void requestModels(Consumer<JsonNode> onResult) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        streams.put(sid, frame -> {
            closeStream(sid);
            onResult.accept(frame);
        });
        ObjectNode frame = mapper.createObjectNode();
        frame.put("k", "models");
        frame.put("sid", sid);
        emit(frame);
    }

    void onFrame(JsonNode frame) {
        String sid = frame.path("sid").asText(null);
        if (sid == null) {
            return;
        }
        Consumer<JsonNode> c = streams.get(sid);
        if (c != null) {
            try {
                c.accept(frame);
            } catch (RuntimeException e) {
                streams.remove(sid);
                log.warn("relay stream consumer failed for {}", sid, e);
            }
        }
    }

    void failAll(String message) {
        streams.keySet().forEach(sid -> fail(sid, message));
    }

    private void emit(ObjectNode frame) {
        if (!session.isReady()) {
            fail(frame.path("sid").asText(), "relay browser is not connected");
            return;
        }
        try {
            session.send(mapper.writeValueAsString(frame));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void fail(String sid, String message) {
        Consumer<JsonNode> consumer = streams.remove(sid);
        if (consumer == null) {
            return;
        }
        ObjectNode frame = mapper.createObjectNode();
        frame.put("k", "end");
        frame.put("sid", sid);
        frame.put("reason", "error");
        frame.set("err", mapper.createObjectNode()
                .put("message", message)
                .put("type", "server_error")
                .put("code", "relay_unavailable")
                .put("status", 503));
        consumer.accept(frame);
    }
}
