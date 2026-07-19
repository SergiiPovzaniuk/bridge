package com.openaiapi.api;

import com.openaiapi.playwright.BrowserNotReadyException;
import com.openaiapi.playwright.ConcurrentBusyException;
import com.openaiapi.playwright.PlaywrightAutomationException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BrowserNotReadyException.class)
    public ResponseEntity<Map<String, Object>> browserNotReady(BrowserNotReadyException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(error("cursor_agent_error", "Playwright session is starting", null, true));
    }

    @ExceptionHandler(ConcurrentBusyException.class)
    public ResponseEntity<Map<String, Object>> busy(ConcurrentBusyException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body(error("rate_limit_error", ex.getMessage(), "concurrency_limit", true));
    }

    @ExceptionHandler(PlaywrightAutomationException.class)
    public ResponseEntity<Map<String, Object>> playwrightError(PlaywrightAutomationException ex) {
        log.warn("Playwright automation error: {}", ex.getMessage());
        if (ex.getErrorBody() != null) {
            return ResponseEntity.status(ex.getStatus()).body(ex.getErrorBody());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("cursor_agent_error", sanitize(ex.getMessage()), null, null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException ex) {
        log.warn("Bad request body: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("invalid_request_error", "Invalid JSON request body", null, null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? "request failed" : ex.getReason();
        String type = ex.getStatusCode().value() == 404 ? "invalid_request_error" : "cursor_agent_error";
        return ResponseEntity.status(ex.getStatusCode()).body(error(type, message, null, null));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, Object>> restClient(RestClientException ex) {
        log.warn("Upstream RestClient error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("cursor_agent_error", "Upstream request failed", null, true));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("server_error", "internal error", null, null));
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "Playwright automation failed";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private Map<String, Object> error(String type, String message, String code, Boolean retryable) {
        Map<String, Object> err = new HashMap<>();
        err.put("message", message);
        err.put("type", type);
        if (code != null) {
            err.put("code", code);
        }
        if (retryable != null) {
            err.put("retryable", retryable);
        }
        return Map.of("error", err);
    }
}
