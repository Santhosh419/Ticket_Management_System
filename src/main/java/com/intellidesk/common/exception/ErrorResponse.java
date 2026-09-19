package com.intellidesk.common.exception;

import java.time.Instant;
import java.util.Map;

/**
 * The ONE error shape every client can rely on:
 *
 * <pre>{@code
 * {
 *   "timestamp": "2026-09-19T14:00:00Z",
 *   "status": 404,
 *   "error": "RESOURCE_NOT_FOUND",
 *   "message": "Ticket not found",
 *   "path": "/api/tickets/42",
 *   "fieldErrors": { "email": "must be a well-formed email address" }
 * }
 * }</pre>
 *
 * Never contains stack traces or internal details (security requirement).
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path,
                                   Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), status, error, message, path, fieldErrors);
    }
}
