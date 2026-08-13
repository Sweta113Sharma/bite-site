package com.bitesite.exception;

public class InvalidOrderStateException extends BusinessException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
