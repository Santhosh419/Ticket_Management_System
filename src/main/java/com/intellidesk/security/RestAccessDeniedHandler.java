package com.intellidesk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes the standard {@link ErrorResponse} JSON with 403 when an
 * AUTHENTICATED user lacks the required role (401 vs 403: who you are is
 * known, but you may not do this).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED",
                "You do not have permission to access this resource",
                request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
