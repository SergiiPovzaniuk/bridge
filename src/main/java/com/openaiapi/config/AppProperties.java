package com.openaiapi.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Auth auth = new Auth();
    private Dummy dummy = new Dummy();
    private Llm llm = new Llm();
    private Upstream upstream = new Upstream();
    private List<ModelConfig> models = new ArrayList<>();
    private Future future = new Future();

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public Dummy getDummy() {
        return dummy;
    }

    public void setDummy(Dummy dummy) {
        this.dummy = dummy;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Upstream getUpstream() {
        return upstream;
    }

    public void setUpstream(Upstream upstream) {
        this.upstream = upstream;
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models;
    }

    public Future getFuture() {
        return future;
    }

    public void setFuture(Future future) {
        this.future = future;
    }

    public static class Auth {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Dummy {
        private String sampleFile = "README.md";
        /** Absolute workspace root on the remote VS Code host (Linux or Windows path). */
        private String workspaceRoot = "";

        public String getSampleFile() {
            return sampleFile;
        }

        public void setSampleFile(String sampleFile) {
            this.sampleFile = sampleFile;
        }

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }
    }

    /**
     * OpenAI-compatible chat model used for Copilot Agent tool loops. The model returns
     * standard {@code tool_calls}; VS Code executes create_file / edit tools in the remote workspace.
     */
    public static class Llm {
        private boolean enabled;
        private String baseUrl = "https://api.openai.com";
        private String chatPath = "/v1/chat/completions";
        private String apiKey = "";
        private String model = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Upstream {
        private boolean enabled = true;
        private boolean usePlaywright = true;
        private String baseUrl = "https://46.174.75.130";
        private String pageUrl = "https://46.174.75.130/";
        private String chatPath = "/ui/run";
        private String healthPath = "/health";
        private String modelsPath = "/v1/models";
        private String apiKey = "";
        private boolean headless = false;
        private String browsersPath = "";
        private int responseTimeoutMs = 600000;
        private String cursorCwd = "";
        private boolean sessionResume = true;
        private long sessionIdleMs = 1_800_000L;
        private String transportKey = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isUsePlaywright() {
            return usePlaywright;
        }

        public void setUsePlaywright(boolean usePlaywright) {
            this.usePlaywright = usePlaywright;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPageUrl() {
            return pageUrl;
        }

        public void setPageUrl(String pageUrl) {
            this.pageUrl = pageUrl;
        }

        public String getChatPath() {
            return chatPath;
        }

        public void setChatPath(String chatPath) {
            this.chatPath = chatPath;
        }

        public String getHealthPath() {
            return healthPath;
        }

        public void setHealthPath(String healthPath) {
            this.healthPath = healthPath;
        }

        public String getModelsPath() {
            return modelsPath;
        }

        public void setModelsPath(String modelsPath) {
            this.modelsPath = modelsPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isHeadless() {
            return headless;
        }

        public void setHeadless(boolean headless) {
            this.headless = headless;
        }

        public String getBrowsersPath() {
            return browsersPath;
        }

        public void setBrowsersPath(String browsersPath) {
            this.browsersPath = browsersPath;
        }

        public int getResponseTimeoutMs() {
            return responseTimeoutMs;
        }

        public void setResponseTimeoutMs(int responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
        }

        public String getCursorCwd() {
            return cursorCwd;
        }

        public void setCursorCwd(String cursorCwd) {
            this.cursorCwd = cursorCwd;
        }

        public boolean isSessionResume() {
            return sessionResume;
        }

        public void setSessionResume(boolean sessionResume) {
            this.sessionResume = sessionResume;
        }

        public long getSessionIdleMs() {
            return sessionIdleMs;
        }

        public void setSessionIdleMs(long sessionIdleMs) {
            this.sessionIdleMs = sessionIdleMs;
        }

        public String getTransportKey() {
            return transportKey;
        }

        public void setTransportKey(String transportKey) {
            this.transportKey = transportKey;
        }
    }

    public static class ModelConfig {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Future {
        private String vscodeBridgeUrl = "http://127.0.0.1:3030";

        public String getVscodeBridgeUrl() {
            return vscodeBridgeUrl;
        }

        public void setVscodeBridgeUrl(String vscodeBridgeUrl) {
            this.vscodeBridgeUrl = vscodeBridgeUrl;
        }
    }
}
