package com.bitesite.exception;

public class DuplicateEmailException extends BusinessException {
    public DuplicateEmailException(String email) {
        super("An account with email " + email + " already exists.");
    }
}
