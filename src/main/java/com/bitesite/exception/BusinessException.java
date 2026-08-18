package com.bitesite.exception;

/** Base for exceptions carrying a message safe to show directly to the end user. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
