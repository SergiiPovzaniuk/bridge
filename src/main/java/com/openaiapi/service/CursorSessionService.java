package com.openaiapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.openaiapi.api.dto.ChatCompletionRequest;
import com.openaiapi.api.dto.ChatMessage;
import com.openaiapi.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CursorSessionService {

    private final AppProperties appProperties;
    private final Map<String, SessionEntry> sessionsByFingerprint = new ConcurrentHashMap<>();
    private final Map<String, String> activeFingerprintByModel = new ConcurrentHashMap<>();

    public CursorSessionService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public void prepareRequest(ChatCompletionRequest request) {
        if (request == null) {
            return;
        }
        ChatCompletionRequest.CursorOptions cursor = request.getCursor();
        if (cursor == null) {
            cursor = new ChatCompletionRequest.CursorOptions();
            request.setCursor(cursor);
        }

        cursor.setCwd(null);

        boolean hasTools = request.getTools() != null && !request.getTools().isEmpty();
        String requestedMode = cursor.getMode();
        if (hasTools) {
            cursor.setMode("ask");
        } else if (!StringUtils.hasText(requestedMode) || "agent".equalsIgnoreCase(requestedMode)) {
            cursor.setMode("ask");
        }

        if (!appProperties.getUpstream().isSessionResume()) {
            cursor.setAgentId(null);
            return;
        }

        expireIdleSessions();

        String model = request.getModel();
        if (!StringUtils.hasText(model)) {
            return;
        }

        String fingerprint = conversationFingerprint(model, request.getMessages());
        String userFingerprint = userOnlyFingerprint(model, request.getMessages());
        boolean freshChat = isFreshChat(request.getMessages());

        if (freshChat) {
            sessionsByFingerprint.remove(fingerprint);
            if (!fingerprint.equals(userFingerprint)) {
                sessionsByFingerprint.remove(userFingerprint);
            }
            cursor.setAgentId(null);
            activeFingerprintByModel.put(model, fingerprint);
            return;
        }

        activeFingerprintByModel.put(model, fingerprint);

        if (StringUtils.hasText(cursor.getAgentId())) {
            return;
        }

        SessionEntry stored = sessionsByFingerprint.get(fingerprint);
        if (stored == null && !fingerprint.equals(userFingerprint)) {
            stored = sessionsByFingerprint.remove(userFingerprint);
            if (stored != null) {
                sessionsByFingerprint.put(fingerprint, stored);
            }
        }
        if (stored != null && StringUtils.hasText(stored.agentId())) {
            cursor.setAgentId(stored.agentId());
            stored.touch();
        }
    }

    public void recordFromResponse(JsonNode response) {
        if (!appProperties.getUpstream().isSessionResume() || response == null) {
            return;
        }
        JsonNode cursor = response.path("cursor");
        if (cursor.isMissingNode() || cursor.isNull()) {
            return;
        }
        String agentId = cursor.path("agentId").asText(null);
        String model = response.path("model").asText(null);
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(model)) {
            return;
        }
        String fingerprint = activeFingerprintByModel.get(model);
        if (!StringUtils.hasText(fingerprint)) {
            return;
        }
        sessionsByFingerprint.put(fingerprint, new SessionEntry(agentId, System.currentTimeMillis()));
    }

    public Map<String, Object> resetAll() {
        int cleared = sessionsByFingerprint.size();
        sessionsByFingerprint.clear();
        activeFingerprintByModel.clear();
        return Map.of("status", "ok", "cleared", cleared);
    }

    public Map<String, Object> resetModel(String model) {
        if (!StringUtils.hasText(model)) {
            return resetAll();
        }
        clearByModel(model);
        return Map.of("status", "ok", "model", model);
    }

    private void clearByModel(String model) {
        String fingerprint = activeFingerprintByModel.remove(model);
        if (fingerprint != null) {
            sessionsByFingerprint.remove(fingerprint);
        }
        sessionsByFingerprint.keySet().removeIf(key -> {
            int colon = key.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            return model.equals(key.substring(0, colon));
        });
    }

    private void expireIdleSessions() {
        long idleMs = appProperties.getUpstream().getSessionIdleMs();
        if (idleMs <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - idleMs;
        Iterator<Map.Entry<String, SessionEntry>> it = sessionsByFingerprint.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SessionEntry> entry = it.next();
            if (entry.getValue().lastAccessMs() < cutoff) {
                it.remove();
                activeFingerprintByModel.entrySet().removeIf(e -> entry.getKey().equals(e.getValue()));
            }
        }
    }

    static boolean isFreshChat(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return true;
        }
        for (ChatMessage message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            String role = message.getRole().toLowerCase();
            if ("assistant".equals(role) || "tool".equals(role)) {
                return false;
            }
        }
        return true;
    }

    static String conversationFingerprint(String model, List<ChatMessage> messages) {
        String firstUser = firstUserText(messages);
        String firstAssistant = firstAssistantText(messages);
        if (!StringUtils.hasText(firstAssistant)) {
            return model + ":" + sha256(firstUser);
        }
        return model + ":" + sha256(firstUser + "\0" + firstAssistant);
    }

    static String userOnlyFingerprint(String model, List<ChatMessage> messages) {
        return model + ":" + sha256(firstUserText(messages));
    }

    private static String firstUserText(List<ChatMessage> messages) {
        return firstRoleText(messages, "user");
    }

    private static String firstAssistantText(List<ChatMessage> messages) {
        return firstRoleText(messages, "assistant");
    }

    private static String firstRoleText(List<ChatMessage> messages, String role) {
        if (messages == null) {
            return "";
        }
        for (ChatMessage message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            if (!role.equalsIgnoreCase(message.getRole())) {
                continue;
            }
            JsonNode content = message.getContent();
            if (content == null || content.isNull()) {
                return "";
            }
            if (content.isTextual()) {
                return content.asText("");
            }
            return content.toString();
        }
        return "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class SessionEntry {
        private final String agentId;
        private volatile long lastAccessMs;

        private SessionEntry(String agentId, long lastAccessMs) {
            this.agentId = agentId;
            this.lastAccessMs = lastAccessMs;
        }

        private String agentId() {
            return agentId;
        }

        private long lastAccessMs() {
            return lastAccessMs;
        }

        private void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
