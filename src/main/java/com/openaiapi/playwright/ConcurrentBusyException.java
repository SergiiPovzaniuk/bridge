package com.openaiapi.playwright;

public class ConcurrentBusyException extends RuntimeException {

    public ConcurrentBusyException(String message) {
        super(message);
    }
}
