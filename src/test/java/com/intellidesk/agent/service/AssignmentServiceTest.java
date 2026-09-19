package com.intellidesk.agent.service;

import com.intellidesk.agent.AssignmentProperties;
import com.intellidesk.agent.repository.AgentSkillRepository;
import com.intellidesk.category.entity.Category;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.ticket.service.TicketWorkflowService;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import com.intellidesk.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent-selection tests: the pool construction and winner determination -
 * the layer between the raw score and the audited assignment.
 */
@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    @Mock private AgentSkillRepository agentSkillRepository;
    @Mock private UserRepository userRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private TicketWorkflowService workflowService;

    private AssignmentService assignmentService;

    private Category payment;
    private User expertBusy;
    private User noviceIdle;
    private User inactiveExpert;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        AgentScorer scorer = new AgentScorer(new AssignmentProperties(true, 0.5, 0.3, 0.2, 10));
        assignmentService = new AssignmentService(
                agentSkillRepository, userRepository, ticketRepository, scorer, workflowService);

        payment = new Category("PAYMENT", "Payment", "p");
        ReflectionTestUtils.setField(payment, "id", 1L);

        expertBusy = agent(10L, "expert@test.local");
        noviceIdle = agent(20L, "novice@test.local");
        inactiveExpert = agent(30L, "inactive@test.local");
        inactiveExpert.setActive(false);

        ticket = new Ticket("TKD-2026-000009", "Payment failed", "Money gone, order cancelled",
                agent(99L, "cust@test.local"), payment, TicketPriority.HIGH, null,
                Instant.now().plus(8, ChronoUnit.HOURS));
        ReflectionTestUtils.setField(ticket, "id", 500L);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.now());
        ReflectionTestUtils.setField(ticket, "updatedAt", Instant.now());
    }

    private static User agent(long id, String email) {
        User u = new User(email, "$2a$10$hashhashhashhashhashhashhashhashhashhashhash", "Agent " + id, null, Role.AGENT);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    private void skilled(User agent, int proficiency) {
        when(agentSkillRepository.findByAgentId(agent.getId())).thenReturn(List.of(
                new com.intellidesk.agent.entity.AgentSkill(agent, payment, proficiency) {{
                    ReflectionTestUtils.setField(this, "id", 1L);
                }}));
    }

    private void poolSetup() {
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(
                List.of(new com.intellidesk.agent.entity.AgentSkill(expertBusy, payment, 5),
                        new com.intellidesk.agent.entity.AgentSkill(noviceIdle, payment, 2),
                        new com.intellidesk.agent.entity.AgentSkill(inactiveExpert, payment, 5)));
        skilled(expertBusy, 5);
        skilled(noviceIdle, 2);
    }

    @Test
    void winnerIsHighestScoringActiveSkilledAgent() {
        poolSetup();
        // expert busy (8 active), novice idle (0): 0.5*1 + 0.3*0.2 + 0.2*1 = 0.76
        // vs novice: 0.5*0.4 + 0.3*1 + 0.2*1 = 0.70 -> expert still wins
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(10L), any())).thenReturn(8L);
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(20L), any())).thenReturn(0L);

        User winner = assignmentService.selectAgent(ticket);

        assertThat(winner).isEqualTo(expertBusy);
    }

    @Test
    void leastLoadedWinsAmongEqualExpertise() {
        poolSetup();
        // identical proficiency (5 vs 5) -> workload alone decides
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(
                List.of(new com.intellidesk.agent.entity.AgentSkill(expertBusy, payment, 5),
                        new com.intellidesk.agent.entity.AgentSkill(noviceIdle, payment, 5)));
        when(agentSkillRepository.findByAgentId(20L)).thenReturn(List.of(
                new com.intellidesk.agent.entity.AgentSkill(noviceIdle, payment, 5)));
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(10L), any())).thenReturn(0L);
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(20L), any())).thenReturn(5L);

        User winner = assignmentService.selectAgent(ticket);

        assertThat(winner).isEqualTo(expertBusy); // same skill, but idle
    }

    @Test
    void inactiveAgentsAreExcludedFromThePool() {
        // ONLY the inactive expert has the skill -> fallback must exclude them
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(List.of(
                new com.intellidesk.agent.entity.AgentSkill(inactiveExpert, payment, 5)));
        when(userRepository.findByRoleAndActiveTrue(Role.AGENT)).thenReturn(List.of(noviceIdle));

        User winner = assignmentService.selectAgent(ticket);

        assertThat(winner).isEqualTo(noviceIdle);
    }

    @Test
    void noSkilledAgentFallsBackToAllActiveAgents() {
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(List.of());
        when(userRepository.findByRoleAndActiveTrue(Role.AGENT)).thenReturn(List.of(noviceIdle));

        User winner = assignmentService.selectAgent(ticket);

        assertThat(winner).isEqualTo(noviceIdle);
    }

    @Test
    void tiesBreakToTheLowerAgentId() {
        poolSetup();
        // identical proficiency AND identical workload -> deterministic id tie-break
        when(agentSkillRepository.findByAgentId(20L)).thenReturn(List.of(
                new com.intellidesk.agent.entity.AgentSkill(noviceIdle, payment, 5)));
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(
                List.of(new com.intellidesk.agent.entity.AgentSkill(expertBusy, payment, 5),
                        new com.intellidesk.agent.entity.AgentSkill(noviceIdle, payment, 5)));
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(anyLong(), any())).thenReturn(3L);

        User winner = assignmentService.selectAgent(ticket);

        assertThat(winner).isEqualTo(expertBusy); // id 10 beats id 20 on a perfect tie
    }

    @Test
    void criticalTicketPrefersExpertDespiteLoad() {
        ticket.setPriority(TicketPriority.CRITICAL);
        poolSetup();
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(10L), any())).thenReturn(9L);
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(eq(20L), any())).thenReturn(0L);

        User winner = assignmentService.selectAgent(ticket);

        // expert at near-capacity still beats idle novice on CRITICAL (+0.20 boost)
        assertThat(winner).isEqualTo(expertBusy);
    }

    @Test
    void selectionIsDeterministicAcrossRuns() {
        poolSetup();
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(anyLong(), any())).thenReturn(2L);

        User first = assignmentService.selectAgent(ticket);
        User second = assignmentService.selectAgent(ticket);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void emptyPoolIsRejectedWithClearError() {
        when(agentSkillRepository.findByCategoryId(1L)).thenReturn(List.of());
        when(userRepository.findByRoleAndActiveTrue(Role.AGENT)).thenReturn(List.of());

        assertThatThrownBy(() -> assignmentService.selectAgent(ticket))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("No active agents");
    }

    @Test
    void assignDelegatesToWorkflowAndGuardsNonOpenTickets() {
        ticket.changeStatus(TicketStatus.ASSIGNED);
        when(ticketRepository.findWithDetailsById(500L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> assignmentService.assign(500L, null))
                .isInstanceOf(InvalidTicketStateException.class)
                .hasMessageContaining("OPEN");

        verify(workflowService, org.mockito.Mockito.never()).assignToAgent(any(), any(), any(), any());
    }

    @Test
    void assignHappyPathGoesThroughWorkflow() {
        poolSetup();
        when(ticketRepository.findWithDetailsById(500L)).thenReturn(Optional.of(ticket));
        when(ticketRepository.countByAssignedAgentIdAndStatusIn(anyLong(), any())).thenReturn(0L);
        when(workflowService.assignToAgent(any(), any(), any(), any())).thenReturn(ticket);

        Ticket assigned = assignmentService.assign(500L, null);

        assertThat(assigned).isSameAs(ticket);
        org.mockito.Mockito.verify(workflowService).assignToAgent(
                eq(ticket), eq(expertBusy), eq(null),
                org.mockito.ArgumentMatchers.contains("PAYMENT"));
    }
}
