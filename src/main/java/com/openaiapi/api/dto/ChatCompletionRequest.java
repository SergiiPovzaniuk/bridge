package com.openaiapi.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionRequest {

    private String model;
    private List<ChatMessage> messages;
    private List<ToolDefinition> tools;
    private JsonNode toolChoice;
    private Boolean stream;
    private Double temperature;
    private Integer maxTokens;
    private CursorOptions cursor;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public void setTools(List<ToolDefinition> tools) {
        this.tools = tools;
    }

    public JsonNode getToolChoice() {
        return toolChoice;
    }

    public void setToolChoice(JsonNode toolChoice) {
        this.toolChoice = toolChoice;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public boolean isStream() {
        return Boolean.TRUE.equals(stream);
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public CursorOptions getCursor() {
        return cursor;
    }

    public void setCursor(CursorOptions cursor) {
        this.cursor = cursor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CursorOptions {
        private String cwd;
        @JsonProperty("agentId")
        private String agentId;
        private String mode;

        public String getCwd() {
            return cwd;
        }

        public void setCwd(String cwd) {
            this.cwd = cwd;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }
}
