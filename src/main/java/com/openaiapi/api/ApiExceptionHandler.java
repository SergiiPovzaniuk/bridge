package com.openaiapi.api;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("invalid_request_error", "Invalid JSON request body", null));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? "request failed" : ex.getReason();
        int status = ex.getStatusCode().value();
        String type = (status == 404 || status == 501 || status == 400) ? "invalid_request_error"
                : status == 401 ? "invalid_request_error"
                : "server_error";
        String code = status == 501 ? "not_implemented" : status == 401 ? "invalid_api_key" : null;
        return ResponseEntity.status(ex.getStatusCode()).body(error(type, message, code));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("invalid_request_error", "Not found: " + ex.getResourcePath(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("server_error", "internal error", null));
    }

    private Map<String, Object> error(String type, String message, String code) {
        Map<String, Object> err = new HashMap<>();
        err.put("message", message);
        err.put("type", type);
        if (code != null) {
            err.put("code", code);
        }
        return Map.of("error", err);
    }
}
