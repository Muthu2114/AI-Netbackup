package com.netbackup.service;

/** Error from the AI assistant. {@code status} is the HTTP status the API should answer with. Messages never contain the API key. */
public class AiException extends RuntimeException {
    private final int status;

    public AiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
