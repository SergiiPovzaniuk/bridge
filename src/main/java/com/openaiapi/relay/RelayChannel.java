package com.openaiapi.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Multiplexes many concurrent chat streams over the single browser-relay WebSocket. */
@Component
public class RelayChannel {

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
            c.accept(frame);
        }
    }

    private void emit(ObjectNode frame) {
        try {
            session.send(mapper.writeValueAsString(frame));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
