package com.openaiapi.playwright;

import java.util.Map;

public class PlaywrightAutomationException extends RuntimeException {

    private final int status;
    private final Map<String, Object> errorBody;

    public PlaywrightAutomationException(String message) {
        super(message);
        this.status = 502;
        this.errorBody = null;
    }

    public PlaywrightAutomationException(String message, Throwable cause) {
        super(message, cause);
        this.status = 502;
        this.errorBody = null;
    }

    public PlaywrightAutomationException(int status, Map<String, Object> errorBody) {
        super(extractMessage(errorBody));
        this.status = status;
        this.errorBody = errorBody;
    }

    private static String extractMessage(Map<String, Object> errorBody) {
        if (errorBody == null) {
            return "playwright automation failed";
        }
        Object error = errorBody.get("error");
        if (error instanceof Map<?, ?> err) {
            Object message = err.get("message");
            if (message != null) {
                return message.toString();
            }
        }
        return errorBody.toString();
    }

    public int getStatus() {
        return status;
    }

    public Map<String, Object> getErrorBody() {
        return errorBody;
    }
}
