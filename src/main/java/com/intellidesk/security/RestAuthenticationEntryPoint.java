package com.intellidesk.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes the standard {@link ErrorResponse} JSON with 401 when an
 * unauthenticated request reaches a protected endpoint - instead of Spring's
 * default (an HTML page or a bare status code).
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ErrorResponse body = ErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED",
                "Authentication required: provide a valid Bearer token",
                request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
