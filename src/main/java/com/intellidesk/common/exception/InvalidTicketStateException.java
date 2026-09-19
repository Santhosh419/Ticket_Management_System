package com.intellidesk.common.exception;

/**
 * Thrown when an operation would violate the ticket workflow (fully enforced
 * from Phase 4; field-update rules already use it, e.g. editing a ticket that
 * is no longer OPEN). Mapped to 409 CONFLICT: the request contradicts the
 * current state of the resource.
 */
public class InvalidTicketStateException extends RuntimeException {

    public InvalidTicketStateException(String message) {
        super(message);
    }
}
