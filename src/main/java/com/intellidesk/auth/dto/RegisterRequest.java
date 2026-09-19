package com.intellidesk.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration request. Deliberately has NO role field: every self-registered
 * user becomes CUSTOMER. Accepting a role from the client would be a
 * privilege-escalation hole; agents/admins are created by admins (later phase).
 *
 * <p>Password rules: 8-72 chars, at least one letter and one digit.
 * The 72-char ceiling matches BCrypt's internal 72-byte limit - longer input
 * is silently truncated by BCrypt, which surprises users.</p>
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                 message = "Password must contain at least one letter and one digit")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(min = 2, max = 120, message = "Full name must be 2-120 characters")
        String fullName,

        @Size(max = 20, message = "Phone must be at most 20 characters")
        @Pattern(regexp = "^[0-9+\\- ]*$", message = "Phone may contain digits, spaces, + and - only")
        String phone
) {}
