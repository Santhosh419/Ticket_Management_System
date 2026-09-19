package com.intellidesk.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack auth flow over REAL HTTP (random port, real Tomcat, real
 * security filter chain): register -> login -> authenticated access -> role
 * enforcement -> every documented error shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:intellidesk_auth_test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired private TestRestTemplate rest;

    // ---- registration ------------------------------------------------------

    @Test
    void registerReturns201WithTokenAndNeverExposesPassword() {
        String email = uniqueEmail("register");
        ResponseEntity<String> response = rest.postForEntity("/api/auth/register",
                jsonBody(Map.of(
                        "email", email,
                        "password", "Sup3rSecret!",
                        "fullName", "Register Tester")), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody())
                .contains("\"tokenType\":\"Bearer\"")
                .contains(email)
                .contains("\"role\":\"CUSTOMER\"");
        // No password material anywhere in the response
        assertThat(response.getBody()).doesNotContain("Sup3rSecret");
        assertThat(response.getBody()).doesNotContain("password");
        assertThat(response.getBody()).doesNotContain("passwordHash");
    }

    @Test
    void registerDuplicateEmailReturns409WithErrorCode() {
        String email = uniqueEmail("duplicate");
        rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", "Sup3rSecret!", "fullName", "First")), String.class);

        ResponseEntity<String> second = rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", "Sup3rSecret!", "fullName", "Second")), String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("DUPLICATE_RESOURCE").contains(email);
    }

    @Test
    void registerInvalidBodyReturns400WithFieldErrors() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", "not-an-email", "password", "short", "fullName", "")), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .contains("VALIDATION_ERROR")
                .contains("email")
                .contains("password")
                .contains("fullName");
    }

    @Test
    void registerMalformedJsonReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/auth/register",
                new HttpEntity<>("{ not json", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("MALFORMED_REQUEST");
    }

    // ---- login -------------------------------------------------------------

    @Test
    void loginWithCorrectCredentialsReturns200AndToken() {
        String email = uniqueEmail("login");
        rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", "Sup3rSecret!", "fullName", "Login Tester")),
                String.class);

        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                jsonBody(Map.of("email", email, "password", "Sup3rSecret!")), String.class);

        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getBody()).contains("\"token\":").contains(email);
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        String email = uniqueEmail("wrongpw");
        rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", "Sup3rSecret!", "fullName", "Wrong Pw")),
                String.class);

        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                jsonBody(Map.of("email", email, "password", "WrongPass1")), String.class);

        assertThat(login.getStatusCode().value()).isEqualTo(401);
        assertThat(login.getBody()).contains("INVALID_CREDENTIALS");
        // Must not reveal whether the email exists
        assertThat(login.getBody()).contains("Invalid email or password");
    }

    @Test
    void loginWithUnknownEmailReturnsSame401AsWrongPassword() {
        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                jsonBody(Map.of("email", uniqueEmail("ghost"), "password", "Whatever1")), String.class);

        assertThat(login.getStatusCode().value()).isEqualTo(401);
        assertThat(login.getBody()).contains("Invalid email or password");
    }

    // ---- authenticated requests -------------------------------------------

    @Test
    void meWithValidTokenReturnsCurrentUser() {
        String email = uniqueEmail("me");
        String token = registerAndLogin(email, "Sup3rSecret!");

        ResponseEntity<String> me = exchangeWithToken("/api/auth/me", token);

        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody()).contains(email).contains("\"role\":\"CUSTOMER\"");
    }

    @Test
    void meWithoutTokenReturns401JsonError() {
        ResponseEntity<String> me = rest.getForEntity("/api/auth/me", String.class);

        assertThat(me.getStatusCode().value()).isEqualTo(401);
        assertThat(me.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    void meWithGarbageTokenReturns401() {
        ResponseEntity<String> me = exchangeWithToken("/api/auth/me", "this.is.garbage");

        assertThat(me.getStatusCode().value()).isEqualTo(401);
        assertThat(me.getBody()).contains("UNAUTHORIZED");
    }

    // ---- role-based authorization -----------------------------------------

    @Test
    void customerTokenOnAdminEndpointReturns403() {
        String token = registerAndLogin(uniqueEmail("customer"), "Sup3rSecret!");

        ResponseEntity<String> ping = exchangeWithToken("/api/admin/ping", token);

        assertThat(ping.getStatusCode().value()).isEqualTo(403);
        assertThat(ping.getBody()).contains("ACCESS_DENIED");
    }

    @Test
    void adminTokenOnAdminEndpointReturns200() {
        // Seeded by DataSeeder from application-test.yml
        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                jsonBody(Map.of("email", "test-admin@intellidesk.local", "password", "TestAdmin@123")),
                String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String token = extractField(login.getBody(), "token");

        ResponseEntity<String> ping = exchangeWithToken("/api/admin/ping", token);

        assertThat(ping.getStatusCode().value()).isEqualTo(200);
        assertThat(ping.getBody()).contains("\"status\":\"ok\"").contains("ADMIN");
    }

    @Test
    void swaggerDocsArePublic() {
        ResponseEntity<String> docs = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(docs.getStatusCode().value()).isEqualTo(200);
    }

    // ---- helpers -----------------------------------------------------------

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@test.local";
    }

    private static HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String registerAndLogin(String email, String password) {
        ResponseEntity<String> register = rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", password, "fullName", "Flow Tester")),
                String.class);
        assertThat(register.getStatusCode().value()).isEqualTo(201);
        return extractField(register.getBody(), "token");
    }

    private ResponseEntity<String> exchangeWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Minimal extraction without a JSON library dependency in tests. */
    private static String extractField(String json, String field) {
        int fieldIndex = json.indexOf("\"" + field + "\":\"");
        int valueStart = json.indexOf('"', fieldIndex + field.length() + 3) + 1;
        return json.substring(valueStart, json.indexOf('"', valueStart));
    }
}
