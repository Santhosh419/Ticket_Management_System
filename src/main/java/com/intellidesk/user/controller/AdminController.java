package com.intellidesk.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ADMIN namespace probe. Phase 2 intentionally ships exactly one endpoint so
 * role-based authorization is demonstrably testable end-to-end (customer ->
 * 403, admin -> 200). Real admin management APIs arrive in later phases.
 *
 * <p>Both authorization layers apply: the filter-chain rule
 * ({@code /api/admin/** -> hasRole('ADMIN')}) AND method-level
 * {@code @PreAuthorize} - defense in depth.</p>
 */
@Tag(name = "Admin", description = "Admin-only operations (more arrive in later phases)")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Operation(summary = "Authorization probe: reachable only with an ADMIN token")
    @ApiResponse(responseCode = "200", description = "Caller is ADMIN")
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "requiredRole", "ADMIN");
    }
}
