package com.intellidesk.common.exception;

/**
 * Thrown when a uniqueness rule is violated (e.g. registering an email that
 * already exists). Mapped to 409 Conflict - the client sent a semantically
 * valid request, but it collides with existing state.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
