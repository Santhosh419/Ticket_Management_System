package com.intellidesk.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized exception handling: converts every failure into the single
 * {@link ErrorResponse} shape with the right HTTP status - controllers stay
 * free of try/catch noise and clients get predictable errors.
 *
 * <p>Stack traces are logged server-side but never serialized to clients.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- Domain exceptions -------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorizedAccess(UnauthorizedAccessException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTicketStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTicketState(InvalidTicketStateException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "INVALID_TICKET_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleBadSortProperty(PropertyReferenceException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_SORT_PROPERTY",
                "Unknown sort/filter property: " + ex.getPropertyName(), request);
    }

    // ---- Request validation ------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            // First message per field wins; later duplicates are noise
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(), "VALIDATION_ERROR", "Request validation failed",
                request.getRequestURI(), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or malformed", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "TYPE_MISMATCH",
                "Parameter '%s' has invalid value '%s'".formatted(ex.getName(), ex.getValue()), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_SUPPORTED", ex.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No handler for " + request.getRequestURI(), request);
    }

    // ---- Security (thrown inside the dispatch layer, e.g. @PreAuthorize) ----

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", request);
    }

    // ---- Concurrency & persistence -----------------------------------------

    /**
     * Two actors transition/assign/edit the same ticket concurrently: the
     * loser's flush fails the {@code @Version} check. That is an expected,
     * retryable business conflict - 409, never a 500.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {
        log.warn("Concurrent modification on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by someone else. Reload and retry.", request);
    }

    /**
     * Unique-constraint races (e.g. two parallel registrations with the same
     * email after the pre-check passed). Must surface as 409, not 500.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(),
                request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE",
                "The resource conflicts with existing data (already present?)", request);
    }

    // ---- Fallback ----------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Full details in the server log only - clients get a generic message
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.of(status.value(), code, message, request.getRequestURI());
        if (status.is5xxServerError()) {
            log.error("{} -> {} {}", status, request.getMethod(), request.getRequestURI());
        } else {
            log.debug("{} -> {} {} ({})", status, request.getMethod(), request.getRequestURI(), message);
        }
        return ResponseEntity.status(status).body(body);
    }
}
