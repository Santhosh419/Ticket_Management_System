package com.intellidesk.common.exception;

/**
 * Thrown when a request is syntactically valid but semantically impossible
 * (e.g. references a category that does not exist). Mapped to 400 by
 * {@link GlobalExceptionHandler} - distinct from validation errors only in
 * that the problem is cross-field or reference-based.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
