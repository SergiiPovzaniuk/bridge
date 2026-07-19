package com.openaiapi.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolDefinition {

    private String type = "function";
    private FunctionDefinition function;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public FunctionDefinition getFunction() {
        return function;
    }

    public void setFunction(FunctionDefinition function) {
        this.function = function;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionDefinition {
        private String name;
        private String description;
        private JsonNode parameters;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public JsonNode getParameters() {
            return parameters;
        }

        public void setParameters(JsonNode parameters) {
            this.parameters = parameters;
        }
    }
}
