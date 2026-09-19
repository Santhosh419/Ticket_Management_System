package com.intellidesk.ticket;

import com.intellidesk.agent.repository.AgentSkillRepository;
import com.intellidesk.agent.service.AgentAdminService;
import com.intellidesk.agent.dto.AgentSkillRequest;
import com.intellidesk.sla.service.SlaMonitorJob;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full lifecycle over real HTTP: create (auto-assigned) -> start -> wait ->
 * resume -> resolve -> customer reopen -> resolve -> close -> reopen closed ->
 * close again. Plus the audit trail endpoint, the skills admin API, SLA
 * fields in responses, and scheduler-driven escalation (deadline shifted
 * into the past, monitor invoked, idempotency verified).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:intellidesk_wf_test;DB_CLOSE_DELAY=-1",
                "intellidesk.assignment.auto-assign-on-create=true"
        })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TicketWorkflowIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentSkillRepository agentSkillRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private com.intellidesk.category.repository.CategoryRepository categoryRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AgentAdminService agentAdminService;
    @Autowired private SlaMonitorJob slaMonitorJob;

    private String customer;
    private String admin;
    private Long agentId;
    private Long ticketId;

    // ---- setup --------------------------------------------------------------

    @org.junit.jupiter.api.BeforeAll
    void setUp() {
        customer = registerAndLogin("wf-cust-" + suffix() + "@test.local");
        admin = login("test-admin@intellidesk.local", "TestAdmin@123");

        User agentUser = new User("wf-agent-" + suffix() + "@test.local",
                passwordEncoder.encode("AgentPass1"), "Wave Agent", null, Role.AGENT);
        userRepository.save(agentUser);
        agentId = agentUser.getId();
        agentAdminService.addSkill(agentId, new AgentSkillRequest(
                categoryByCode("PAYMENT"), 5));
    }

    // ---- the lifecycle ------------------------------------------------------

    @Test
    @Order(1)
    void createAutoAssignsToSkilledAgentAndWritesFirstHistoryEntries() {
        ResponseEntity<String> created = post("/api/tickets", customer, Map.of(
                "title", "Payment deducted but order failed",
                "description", "Rs.500 was deducted from my account but the order was cancelled.",
                "categoryId", categoryByCode("PAYMENT"),
                "priority", "CRITICAL"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        String body = created.getBody();
        // auto-assignment found the skilled agent immediately
        assertThat(body).contains("\"status\":\"ASSIGNED\"");
        assertThat(body).contains("\"fullName\":\"Wave Agent\"");
        assertThat(body).contains("\"sla\":{\"status\":\"ON_TRACK\"");
        ticketId = Long.parseLong(extractNumber(body, "id"));

        // audit trail: creation + auto-assignment
        ResponseEntity<String> history = get("/api/tickets/" + ticketId + "/history", customer);
        assertThat(history.getStatusCode().value()).isEqualTo(200);
        assertThat(history.getBody())
                .contains("\"newStatus\":\"OPEN\"")
                .contains("\"newStatus\":\"ASSIGNED\"")
                .contains("Auto-assigned")
                .contains("System");
    }

    @Test
    @Order(2)
    void customerCannotStartWork_agentCan() {
        ResponseEntity<String> forbidden = post("/api/tickets/" + ticketId + "/status",
                customer, Map.of("target", "IN_PROGRESS"));
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

        String agentToken = login(agentEmail(), "AgentPass1");
        ResponseEntity<String> started = post("/api/tickets/" + ticketId + "/status",
                agentToken, Map.of("target", "IN_PROGRESS", "reason", "Investigating the charge"));
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        assertThat(started.getBody()).contains("\"status\":\"IN_PROGRESS\"");
    }

    @Test
    @Order(3)
    void agentWaitsForCustomer_thenResumes() {
        String agentToken = login(agentEmail(), "AgentPass1");

        ResponseEntity<String> waiting = post("/api/tickets/" + ticketId + "/status",
                agentToken, Map.of("target", "WAITING_FOR_CUSTOMER", "reason", "Need transaction id"));
        assertThat(waiting.getBody()).contains("\"status\":\"WAITING_FOR_CUSTOMER\"");

        ResponseEntity<String> resumed = post("/api/tickets/" + ticketId + "/status",
                agentToken, Map.of("target", "IN_PROGRESS", "reason", "Customer replied"));
        assertThat(resumed.getBody()).contains("\"status\":\"IN_PROGRESS\"");
    }

    @Test
    @Order(4)
    void resolveRequiresResolutionNote_thenCustomerReopens() {
        String agentToken = login(agentEmail(), "AgentPass1");

        ResponseEntity<String> withoutNote = post("/api/tickets/" + ticketId + "/status",
                agentToken, Map.of("target", "RESOLVED"));
        assertThat(withoutNote.getStatusCode().value()).isEqualTo(400);
        assertThat(withoutNote.getBody()).contains("resolution note");

        ResponseEntity<String> resolved = post("/api/tickets/" + ticketId + "/status",
                agentToken, Map.of("target", "RESOLVED",
                        "resolution", "Refunded the duplicate charge."));
        assertThat(resolved.getBody())
                .contains("\"status\":\"RESOLVED\"")
                .contains("\"resolution\":\"Refunded the duplicate charge.\"")
                .contains("\"resolvedAt\":");

        // customer reopens: resolution data cleared
        ResponseEntity<String> reopened = post("/api/tickets/" + ticketId + "/status",
                customer, Map.of("target", "IN_PROGRESS", "reason", "Money not received yet"));
        assertThat(reopened.getBody()).contains("\"status\":\"IN_PROGRESS\"");
        assertThat(reopened.getBody()).doesNotContain("resolution");
    }

    @Test
    @Order(5)
    void resolveAgain_close_customerReopensClosedInsideWindow() {
        String agentToken = login(agentEmail(), "AgentPass1");

        assertThat(post("/api/tickets/" + ticketId + "/status", agentToken,
                Map.of("target", "RESOLVED", "resolution", "Second refund processed."))
                .getBody()).contains("\"status\":\"RESOLVED\"");
        String closed = post("/api/tickets/" + ticketId + "/status", customer,
                Map.of("target", "CLOSED", "reason", "Money received, thank you")).getBody();
        assertThat(closed).contains("\"status\":\"CLOSED\"")
                // regression: transitions answered to CUSTOMERS never carry agent hints
                .doesNotContain("suggestedResponse");

        // reopen inside the window is allowed for the owner; OPEN clears the agent
        ResponseEntity<String> reopened = post("/api/tickets/" + ticketId + "/status",
                customer, Map.of("target", "OPEN", "reason", "Need one more change"));
        assertThat(reopened.getBody())
                .contains("\"status\":\"OPEN\"")
                .doesNotContain("assignedAgent");

        // from OPEN the only way forward is through the normal graph: assign -> work -> resolve -> close
        assertThat(post("/api/tickets/" + ticketId + "/assign", admin, Map.of()).getBody())
                .contains("\"status\":\"ASSIGNED\"");
        String agentToken2 = login(agentEmail(), "AgentPass1");
        assertThat(post("/api/tickets/" + ticketId + "/status", agentToken2,
                Map.of("target", "IN_PROGRESS")).getBody()).contains("\"status\":\"IN_PROGRESS\"");
        assertThat(post("/api/tickets/" + ticketId + "/status", agentToken2,
                Map.of("target", "RESOLVED", "resolution", "Adjusted the order manually."))
                .getBody()).contains("\"status\":\"RESOLVED\"");
        // ...and then close it again via the owner
        assertThat(post("/api/tickets/" + ticketId + "/status", customer,
                Map.of("target", "CLOSED")).getBody()).contains("\"status\":\"CLOSED\"");
    }

    @Test
    @Order(6)
    void illegalTransitionsAreRejectedWith409() {
        // fresh tickets auto-assign, so illegal jumps are tried from ASSIGNED
        Long assignedId = createOpenTicket();
        ResponseEntity<String> illegal = post("/api/tickets/" + assignedId + "/status",
                admin, Map.of("target", "CLOSED"));
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
        assertThat(illegal.getBody())
                .contains("INVALID_TICKET_STATE")
                .contains("ASSIGNED -> CLOSED")
                .contains("Allowed: [IN_PROGRESS, ESCALATED]");

        ResponseEntity<String> resolveJump = post("/api/tickets/" + assignedId + "/status",
                admin, Map.of("target", "RESOLVED", "resolution", "skipping work"));
        assertThat(resolveJump.getStatusCode().value()).isEqualTo(409);
        assertThat(resolveJump.getBody()).contains("ASSIGNED -> RESOLVED");
    }

    @Test
    @Order(7)
    void manualAssignmentByAdmin_reassignsOnlyOpenTickets() {
        Long id = createOpenTicket(); // arrives ASSIGNED (auto-assign)
        String agentToken = login(agentEmail(), "AgentPass1");

        // walk to CLOSED so the customer can reopen it back to OPEN (agent cleared)
        assertThat(post("/api/tickets/" + id + "/status", agentToken,
                Map.of("target", "IN_PROGRESS")).getBody()).contains("\"status\":\"IN_PROGRESS\"");
        assertThat(post("/api/tickets/" + id + "/status", agentToken,
                Map.of("target", "RESOLVED", "resolution", "Restored from backup."))
                .getBody()).contains("\"status\":\"RESOLVED\"");
        assertThat(post("/api/tickets/" + id + "/status", customer,
                Map.of("target", "CLOSED")).getBody()).contains("\"status\":\"CLOSED\"");
        assertThat(post("/api/tickets/" + id + "/status", customer,
                Map.of("target", "OPEN", "reason", "It broke again")).getBody())
                .contains("\"status\":\"OPEN\"");

        // admin manual assignment is only legal from OPEN
        ResponseEntity<String> assigned = post("/api/tickets/" + id + "/assign", admin, Map.of());
        assertThat(assigned.getStatusCode().value()).isEqualTo(200);
        assertThat(assigned.getBody()).contains("\"status\":\"ASSIGNED\"").contains("Wave Agent");

        ResponseEntity<String> again = post("/api/tickets/" + id + "/assign", admin, Map.of());
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).contains("Only OPEN tickets can be assigned");
    }

    @Test
    @Order(8)
    void slaMonitorEscalatesBreachedTicketExactlyOnce() {
        Long openId = createOpenTicket();

        // simulate an old ticket: shift the deadline into the past
        com.intellidesk.ticket.entity.Ticket t = ticketRepository.findById(openId).orElseThrow();
        t.setSlaDeadlineAt(java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.MINUTES));
        ticketRepository.save(t);

        int firstRun = slaMonitorJob.escalateBreachedTickets(java.time.Instant.now());
        assertThat(firstRun).isEqualTo(1);

        ResponseEntity<String> view = get("/api/tickets/" + openId, admin);
        assertThat(view.getBody())
                .contains("\"status\":\"ESCALATED\"")
                .contains("\"escalatedAt\":")
                .contains("\"sla\":{\"status\":\"BREACHED\"");

        ResponseEntity<String> history = get("/api/tickets/" + openId + "/history", admin);
        assertThat(history.getBody())
                .contains("SLA breached - auto-escalated by monitor")
                .contains("\"newStatus\":\"ESCALATED\"")
                .contains("\"changedBy\":\"System\"");

        // second run: nothing new (idempotent)
        int secondRun = slaMonitorJob.escalateBreachedTickets(java.time.Instant.now());
        assertThat(secondRun).isZero();

        // exactly ONE escalation audit row exists
        long escalationRows = history.getBody().split("auto-escalated by monitor", -1).length - 1;
        assertThat(escalationRows).isEqualTo(1);
    }

    @Test
    @Order(9)
    void escalatedTicketCanBeResumedByAgentOrAdmin() {
        // the escalated ticket from order(8) goes back to work via admin
        Long escalatedId = ticketRepository.findAll().stream()
                .filter(t -> t.getStatus() == com.intellidesk.ticket.domain.TicketStatus.ESCALATED)
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> resumed = post("/api/tickets/" + escalatedId + "/status",
                admin, Map.of("target", "IN_PROGRESS", "reason", "Senior agent took over"));
        assertThat(resumed.getBody()).contains("\"status\":\"IN_PROGRESS\"");
        // escalatedAt is kept as the record of the last escalation
        assertThat(resumed.getBody()).contains("\"escalatedAt\":");
    }

    @Test
    @Order(10)
    void adminSkillsAndWorkloadEndpointsExposeAssignmentInputs() {
        ResponseEntity<String> skills = get("/api/admin/agents/" + agentId + "/skills", admin);
        assertThat(skills.getBody()).contains("\"categoryCode\":\"PAYMENT\"").contains("\"proficiencyLevel\":5");

        ResponseEntity<String> workloads = get("/api/admin/agents", admin);
        assertThat(workloads.getBody())
                .contains("Wave Agent")
                .contains("\"categoryCode\":\"PAYMENT\"")
                .contains("\"activeTickets\":");

        // customers are locked out of admin endpoints
        assertThat(get("/api/admin/agents", customer).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @Order(11)
    void unclassifiedTicketIsClassifiedOnCreateAndHintsAreAgentOnly() {
        // no categoryId, no priority -> the rule-based classifier decides
        ResponseEntity<String> created = post("/api/tickets", customer, Map.of(
                "title", "Urgent: payment gateway not working",
                "description", "We are losing money every minute, please fix this immediately."));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody())
                .contains("\"status\":\"ASSIGNED\"")     // classified category feeds auto-assignment
                .contains("\"code\":\"PAYMENT\"")
                .contains("\"priority\":\"HIGH\"")
                .contains("\"sentiment\":\"NEUTRAL\"")
                .doesNotContain("suggestedResponse"); // creating customer: no agent hints

        Long id = Long.parseLong(extractNumber(created.getBody(), "id"));

        // the assigned agent DOES see the suggested response
        String agentToken = login(agentEmail(), "AgentPass1");
        assertThat(get("/api/tickets/" + id, agentToken).getBody())
                .contains("suggestedResponse")
                .contains("Verify the transaction in the payment gateway dashboard");
    }

    // ---- helpers -------------------------------------------------------------

    private Long createOpenTicket() {
        // auto-assignment is ON, so kill the assignment to keep it OPEN for the caller
        ResponseEntity<String> created = post("/api/tickets", customer, Map.of(
                "title", "Order stuck at pending",
                "description", "My order has shown pending for five days now, please check.",
                "categoryId", categoryByCode("ORDER"),
                "priority", "LOW"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return Long.parseLong(extractNumber(created.getBody(), "id"));
    }

    private Long categoryByCode(String code) {
        return categoryRepository.findByCode(code).orElseThrow().getId();
    }

    private String agentEmail() {
        return userRepository.findById(agentId).orElseThrow().getEmail();
    }

    private String registerAndLogin(String email) {
        ResponseEntity<String> register = post("/api/auth/register", null,
                Map.of("email", email, "password", "Passw0rd123", "fullName", "WF Customer"));
        assertThat(register.getStatusCode().value()).isEqualTo(201);
        return extractString(register.getBody(), "token");
    }

    private String login(String email, String password) {
        ResponseEntity<String> login = post("/api/auth/login", null,
                Map.of("email", email, "password", password));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        return extractString(login.getBody(), "token");
    }

    private ResponseEntity<String> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String extractString(String jsonBody, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]*)\"")
                .matcher(jsonBody);
        assertThat(matcher.find()).as("field %s in %s", field, jsonBody).isTrue();
        return matcher.group(1);
    }

    private static String extractNumber(String jsonBody, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":(\\d+)")
                .matcher(jsonBody);
        assertThat(matcher.find()).as("field %s in %s", field, jsonBody).isTrue();
        return matcher.group(1);
    }
}
