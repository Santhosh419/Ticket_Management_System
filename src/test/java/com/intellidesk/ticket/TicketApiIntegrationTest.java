package com.intellidesk.ticket;

import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack ticket API matrix over real HTTP: creation, the complete access
 * matrix (owner / other customer / unassigned+assigned agent / admin), paging,
 * field-update rules and every documented error response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:intellidesk_ticket_test;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TicketApiIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String customerA;
    private String customerB;
    private String agent;
    private String admin;
    private Long paymentCategoryId;
    private Long otherCategoryId;

    private Long ticketACreatedAtAll;
    private String ticketANumber;
    private Instant ticketADeadlineBeforePriorityChange;

    @BeforeAll
    void setUpUsersAndCategories() {
        customerA = registerAndLogin("cust-a-" + suffix() + "@test.local", "Passw0rd123");
        customerB = registerAndLogin("cust-b-" + suffix() + "@test.local", "Passw0rd123");
        agent = createAgentAndLogin("agent-" + suffix() + "@test.local");
        admin = login("test-admin@intellidesk.local", "TestAdmin@123");
        paymentCategoryId = categoryRepository.findByCode("PAYMENT").orElseThrow().getId();
        otherCategoryId = categoryRepository.findByCode("OTHER").orElseThrow().getId();
    }

    // ---- creation ----------------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(1)
    void customerCreatesTicketAndGetsGeneratedFields() {
        ResponseEntity<String> response = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerA, Map.of(
                        "title", "Payment deducted but order failed",
                        "description", "Rs.500 was deducted from my account but the order was cancelled.",
                        "categoryId", paymentCategoryId,
                        "priority", "HIGH")), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        String body = response.getBody();
        assertThat(body)
                .contains("\"status\":\"OPEN\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"code\":\"PAYMENT\"");
        // non_null JSON inclusion omits empty fields entirely - the API contract
        // guarantees they are absent, never rendered as null
        assertThat(body)
                .doesNotContain("assignedAgent")
                .doesNotContain("resolvedAt")
                .doesNotContain("closedAt");
        ticketANumber = extractJsonString(body, "ticketNumber");
        assertThat(ticketANumber).matches("TKD-\\d{4}-\\d{6}");
        ticketACreatedAtAll = Long.parseLong(extractJsonNumber(body, "id"));
        assertThat(extractJsonString(body, "slaDeadlineAt")).isNotBlank();

        // Location header points at the new resource
        assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/api/tickets/" + ticketACreatedAtAll);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(2)
    void agentCannotCreateTickets() {
        ResponseEntity<String> response = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(agent, Map.of(
                        "title", "Agent attempt", "description", "Agents should not create tickets.",
                        "categoryId", paymentCategoryId, "priority", "LOW")), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).contains("ACCESS_DENIED");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(3)
    void invalidCreationPayloadsReturn400() {
        // Blank title + short description
        ResponseEntity<String> invalid = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerA, Map.of(
                        "title", "", "description", "too short",
                        "categoryId", paymentCategoryId, "priority", "HIGH")), String.class);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody()).contains("VALIDATION_ERROR").contains("title").contains("description");

        // Unknown priority enum value
        ResponseEntity<String> badEnum = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerA, Map.of(
                        "title", "Some valid title", "description", "Some valid description here.",
                        "categoryId", paymentCategoryId, "priority", "URGENT")), String.class);
        assertThat(badEnum.getStatusCode().value()).isEqualTo(400);

        // Nonexistent category
        ResponseEntity<String> badCategory = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerA, Map.of(
                        "title", "Some valid title", "description", "Some valid description here.",
                        "categoryId", 99999, "priority", "HIGH")), String.class);
        assertThat(badCategory.getStatusCode().value()).isEqualTo(400);
        assertThat(badCategory.getBody()).contains("Category 99999");
    }

    // ---- read access matrix --------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(4)
    void ownerReadsOwnTicket_otherCustomerForbidden_unassignedAgentForbidden() {
        ResponseEntity<String> owner = getWithToken("/api/tickets/" + ticketACreatedAtAll, customerA);
        assertThat(owner.getStatusCode().value()).isEqualTo(200);
        assertThat(owner.getBody()).contains(ticketANumber);

        ResponseEntity<String> otherCustomer = getWithToken("/api/tickets/" + ticketACreatedAtAll, customerB);
        assertThat(otherCustomer.getStatusCode().value()).isEqualTo(403);
        assertThat(otherCustomer.getBody()).contains("ACCESS_DENIED");

        ResponseEntity<String> unassignedAgent = getWithToken("/api/tickets/" + ticketACreatedAtAll, agent);
        assertThat(unassignedAgent.getStatusCode().value()).isEqualTo(403);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(5)
    void assignedAgentAndAdminCanRead() {
        // capture deadline BEFORE the priority change test for later comparison
        ResponseEntity<String> before = getWithToken("/api/tickets/" + ticketACreatedAtAll, customerA);
        ticketADeadlineBeforePriorityChange = Instant.parse(extractJsonString(before.getBody(), "slaDeadlineAt"));

        assignAgent(ticketACreatedAtAll);

        ResponseEntity<String> assignedAgent = getWithToken("/api/tickets/" + ticketACreatedAtAll, agent);
        assertThat(assignedAgent.getStatusCode().value()).isEqualTo(200);
        assertThat(assignedAgent.getBody()).contains("\"fullName\":");

        ResponseEntity<String> adminView = getWithToken("/api/tickets/" + ticketACreatedAtAll, admin);
        assertThat(adminView.getStatusCode().value()).isEqualTo(200);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(6)
    void nonexistentTicketReturns404() {
        ResponseEntity<String> response = getWithToken("/api/tickets/99999999", admin);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("RESOURCE_NOT_FOUND").contains("99999999");
    }

    // ---- lists -------------------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(7)
    void myTicketsReturnOnlyOwnTicketsPaged() {
        // B creates an own ticket
        rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerB, Map.of(
                        "title", "Delivery is late", "description", "My parcel is three weeks late now.",
                        "categoryId", otherCategoryId, "priority", "MEDIUM")), String.class);

        ResponseEntity<String> mine = getWithToken("/api/tickets/my?page=0&size=10", customerA);
        assertThat(mine.getStatusCode().value()).isEqualTo(200);
        assertThat(mine.getBody())
                .contains(ticketANumber)
                .contains("\"totalElements\":")
                .doesNotContain("Delivery is late");

        ResponseEntity<String> theirs = getWithToken("/api/tickets/my?page=0&size=10", customerB);
        assertThat(theirs.getBody()).contains("Delivery is late").doesNotContain(ticketANumber);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(8)
    void assignedListContainsOnlyAssignedWork() {
        ResponseEntity<String> assigned = getWithToken("/api/tickets/assigned?page=0&size=10", agent);

        assertThat(assigned.getStatusCode().value()).isEqualTo(200);
        assertThat(assigned.getBody()).contains(ticketANumber);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(9)
    void adminListSupportsPagingAndStatusFilter_customersForbidden() {
        ResponseEntity<String> all = getWithToken("/api/tickets?page=0&size=5", admin);
        assertThat(all.getStatusCode().value()).isEqualTo(200);
        assertThat(all.getBody()).contains("\"page\":0").contains("\"size\":5").contains("\"totalElements\":");

        ResponseEntity<String> openOnly = getWithToken("/api/tickets?status=OPEN&size=50", admin);
        assertThat(openOnly.getStatusCode().value()).isEqualTo(200);
        assertThat(openOnly.getBody()).doesNotContain("\"status\":\"ASSIGNED\"");

        ResponseEntity<String> forbidden = getWithToken("/api/tickets", customerA);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(10)
    void unknownSortPropertyReturnsClean400() {
        ResponseEntity<String> response = getWithToken("/api/tickets?sort=bogusProperty", admin);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("INVALID_SORT_PROPERTY").contains("bogusProperty");
    }

    // ---- updates -------------------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(11)
    void customerEditsOwnOpenTicket() {
        // fresh ticket that nobody assigned -> stays OPEN and editable
        ResponseEntity<String> created = rest.postForEntity("/api/tickets",
                jsonBodyWithToken(customerA, Map.of(
                        "title", "Account locked after password reset",
                        "description", "I cannot log in after resetting my password yesterday.",
                        "categoryId", paymentCategoryId, "priority", "MEDIUM")), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        Long editableId = Long.parseLong(extractJsonNumber(created.getBody(), "id"));

        ResponseEntity<String> response = exchangeWithToken("/api/tickets/" + editableId,
                customerA, HttpMethod.PATCH,
                Map.of("title", "Account locked even after correct password"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Account locked even after correct password");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(12)
    void customerCannotChangePriorityAndOtherCustomersTickets() {
        ResponseEntity<String> priorityAttempt = exchangeWithToken("/api/tickets/" + ticketACreatedAtAll,
                customerA, HttpMethod.PATCH, Map.of("priority", "CRITICAL"));
        assertThat(priorityAttempt.getStatusCode().value()).isEqualTo(403);
        assertThat(priorityAttempt.getBody()).contains("admins");

        // B tries to edit A's ticket (it is OPEN + not his)
        Long otherId = findTicketIdByNumber(customerB);
        ResponseEntity<String> foreign = exchangeWithToken("/api/tickets/" + otherId,
                customerA, HttpMethod.PATCH, Map.of("title", "Hijack attempt title"));
        assertThat(foreign.getStatusCode().value()).isEqualTo(403);
        assertThat(foreign.getBody()).contains("your own");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(13)
    void customerCannotEditAssignedTicket_409() {
        // A's ticket is ASSIGNED since order(5); editing must now conflict
        ResponseEntity<String> response = exchangeWithToken("/api/tickets/" + ticketACreatedAtAll,
                customerA, HttpMethod.PATCH, Map.of("description", "Trying to edit after assignment."));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("INVALID_TICKET_STATE").contains("OPEN");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(14)
    void adminUpdatesCategoryPriorityWithSlaRecompute_andEmptyUpdateIs400() {
        ResponseEntity<String> response = exchangeWithToken("/api/tickets/" + ticketACreatedAtAll,
                admin, HttpMethod.PATCH,
                Map.of("categoryId", otherCategoryId, "priority", "CRITICAL"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"code\":\"OTHER\"").contains("\"priority\":\"CRITICAL\"");
        Instant newDeadline = Instant.parse(extractJsonString(response.getBody(), "slaDeadlineAt"));
        assertThat(newDeadline).isBefore(ticketADeadlineBeforePriorityChange);
        assertThat(newDeadline).isAfter(Instant.now());

        ResponseEntity<String> empty = exchangeWithToken("/api/tickets/" + ticketACreatedAtAll,
                admin, HttpMethod.PATCH, Map.of());
        assertThat(empty.getStatusCode().value()).isEqualTo(400);
        assertThat(empty.getBody()).contains("At least one field");
    }

    // ---- helpers -------------------------------------------------------------

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
        return new HttpEntity<>(body, jsonHeaders());
    }

    private static HttpEntity<Map<String, Object>> jsonBodyWithToken(String token, Map<String, Object> body) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> exchangeWithToken(String path, String token, HttpMethod method,
                                                     Map<String, Object> body) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String registerAndLogin(String email, String password) {
        ResponseEntity<String> register = rest.postForEntity("/api/auth/register",
                jsonBody(Map.of("email", email, "password", password, "fullName", "Test User")),
                String.class);
        assertThat(register.getStatusCode().value()).isEqualTo(201);
        return extractJsonString(register.getBody(), "token");
    }

    private String createAgentAndLogin(String email) {
        User agentUser = new User(email, passwordEncoder.encode("AgentPass1"), "Test Agent", null, Role.AGENT);
        userRepository.save(agentUser);
        return login(email, "AgentPass1");
    }

    private String login(String email, String password) {
        ResponseEntity<String> login = rest.postForEntity("/api/auth/login",
                jsonBody(Map.of("email", email, "password", password)), String.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        return extractJsonString(login.getBody(), "token");
    }

    private void assignAgent(Long ticketId) {
        User agentUser = userRepository.findByEmail(agentEmailUsed()).orElseThrow();
        Ticket ticket = ticketRepository.findById(ticketId).orElseThrow();
        ticket.setAssignedAgent(agentUser);
        ticket.changeStatus(com.intellidesk.ticket.domain.TicketStatus.ASSIGNED);
        ticketRepository.save(ticket);
    }

    private String agentEmailUsed() {
        // the agent created in setUp - recover by role
        return userRepository.findByRoleAndActiveTrue(Role.AGENT).get(0).getEmail();
    }

    private Long findTicketIdByNumber(String ownerToken) {
        ResponseEntity<String> mine = getWithToken("/api/tickets/my?size=50", ownerToken);
        String number = extractJsonString(mine.getBody(), "ticketNumber");
        return ticketRepository.findByTicketNumber(number).orElseThrow().getId();
    }

    /** Minimal JSON field extraction (no extra test dependencies). */
    private static String extractJsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\":\"([^\"]*)\"")
                .matcher(json);
        assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
        return matcher.group(1);
    }

    private static String extractJsonNumber(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\":(\\d+)")
                .matcher(json);
        assertThat(matcher.find()).as("field %s in %s", field, json).isTrue();
        return matcher.group(1);
    }
}
