package com.banque.service;

import org.springframework.http.HttpStatus;

public class ClientAuthException extends RuntimeException {
    private final HttpStatus status;

    public ClientAuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }
}
