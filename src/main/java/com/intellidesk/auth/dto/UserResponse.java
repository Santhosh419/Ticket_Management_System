package com.intellidesk.auth.dto;

import com.intellidesk.user.entity.User;

/**
 * The safe public view of a user. There is no password field BY CONSTRUCTION:
 * a response DTO can only contain what it declares, so password leakage is
 * structurally impossible instead of depending on developer discipline.
 */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        String phone,
        String role,
        boolean active
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole().name(),
                user.isActive()
        );
    }
}
