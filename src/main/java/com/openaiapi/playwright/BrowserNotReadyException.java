package com.openaiapi.playwright;

public class BrowserNotReadyException extends RuntimeException {

    public BrowserNotReadyException(String message) {
        super(message);
    }

    public BrowserNotReadyException(String message, Throwable cause) {
        super(message, cause);
    }
}
