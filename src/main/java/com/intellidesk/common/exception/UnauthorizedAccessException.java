package com.intellidesk.common.exception;

/**
 * Thrown when an authenticated user tries to use a resource they are not
 * allowed to touch (e.g. a customer reading someone else's ticket).
 * Mapped to 403: identity is known, permission is missing.
 * (401 vs 403 distinction is a favorite interview question - see README.)
 */
public class UnauthorizedAccessException extends RuntimeException {

    public UnauthorizedAccessException(String message) {
        super(message);
    }
}
