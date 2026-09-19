package com.intellidesk.common.exception;

/**
 * Thrown when credentials are missing, wrong, or the account cannot be used
 * (e.g. deactivated). Mapped to 401. The message is deliberately vague
 * ("Invalid email or password") so attackers cannot enumerate which emails
 * are registered - a classic account-enumeration defense.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
