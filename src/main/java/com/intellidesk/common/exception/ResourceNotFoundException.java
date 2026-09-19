package com.intellidesk.common.exception;

/**
 * Thrown when a referenced entity does not exist. Mapped to 404 by
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String entity, Object id) {
        return new ResourceNotFoundException("%s not found with id %s".formatted(entity, id));
    }
}
