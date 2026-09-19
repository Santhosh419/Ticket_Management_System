package com.intellidesk.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The concurrency/constraint surface of the API: optimistic-lock races and
 * unique-constraint races are EXPECTED business conflicts and must answer
 * 409 - never the generic 500 fallback.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
        r.setRequestURI(uri);
        return r;
    }

    @Test
    void optimisticLockRaceIsA409NotA500() {
        ResponseEntity<ErrorResponse> response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(
                        com.intellidesk.ticket.entity.Ticket.class, 42L),
                request("/api/tickets/42/status"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(response.getBody().message()).contains("retry");
    }

    @Test
    void duplicateEmailRaceIsA409NotA500() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("uk_users_email"),
                request("/api/auth/register"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("DUPLICATE_RESOURCE");
    }

    @Test
    void domainErrorsKeepTheirContract() {
        assertThat(handler.handleInvalidTicketState(
                new InvalidTicketStateException("Illegal transition OPEN -> CLOSED"),
                request("/x")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleUnauthorizedAccess(
                new UnauthorizedAccessException("nope"),
                request("/x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
