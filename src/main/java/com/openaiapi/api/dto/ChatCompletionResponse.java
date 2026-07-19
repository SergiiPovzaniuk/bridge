package com.openaiapi.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {

    private String id;
    private String object = "chat.completion";
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;
    private CursorResult cursor;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public CursorResult getCursor() {
        return cursor;
    }

    public void setCursor(CursorResult cursor) {
        this.cursor = cursor;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CursorResult {
        @JsonProperty("agentId")
        private String agentId;
        @JsonProperty("runId")
        private String runId;
        private String status;
        private JsonNode git;

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public JsonNode getGit() {
            return git;
        }

        public void setGit(JsonNode git) {
            this.git = git;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {
        private int index;
        private ResponseMessage message;
        private Delta delta;

        @JsonProperty("finish_reason")
        private String finishReason;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public ResponseMessage getMessage() {
            return message;
        }

        public void setMessage(ResponseMessage message) {
            this.message = message;
        }

        public Delta getDelta() {
            return delta;
        }

        public void setDelta(Delta delta) {
            this.delta = delta;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseMessage {
        private String role;
        private String content;

        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {
        private String role;
        private String content;

        @JsonProperty("tool_calls")
        private List<ToolCallDelta> toolCalls;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public List<ToolCallDelta> getToolCalls() {
            return toolCalls;
        }

        public void setToolCalls(List<ToolCallDelta> toolCalls) {
            this.toolCalls = toolCalls;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallDelta {
        private int index;
        private String id;
        private String type;
        private ToolCall.FunctionCall function;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public ToolCall.FunctionCall getFunction() {
            return function;
        }

        public void setFunction(ToolCall.FunctionCall function) {
            this.function = function;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        @JsonProperty("completion_tokens")
        private int completionTokens;

        @JsonProperty("total_tokens")
        private int totalTokens;

        public Usage() {
        }

        public Usage(int promptTokens, int completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = promptTokens + completionTokens;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
