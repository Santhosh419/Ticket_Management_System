package com.intellidesk.agent.controller;

import com.intellidesk.agent.dto.AgentSkillRequest;
import com.intellidesk.agent.dto.AgentSkillResponse;
import com.intellidesk.agent.dto.AgentWorkloadResponse;
import com.intellidesk.agent.service.AgentAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADMIN: manage agent skills and inspect workloads. These inputs feed the
 * smart assignment algorithm, so keeping them visible/manageable makes the
 * algorithm's behavior explainable.
 */
@Tag(name = "Admin - Agents", description = "Agent skills and workload management")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/agents")
public class AgentAdminController {

    private final AgentAdminService agentAdminService;

    public AgentAdminController(AgentAdminService agentAdminService) {
        this.agentAdminService = agentAdminService;
    }

    @Operation(summary = "List active agents with skills and current workload")
    @GetMapping
    public List<AgentWorkloadResponse> workloads() {
        return agentAdminService.getAgentWorkloads();
    }

    @Operation(summary = "Set (or update) a category skill for an agent")
    @ApiResponse(responseCode = "201", description = "Skill stored")
    @PostMapping("/{agentId}/skills")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentSkillResponse addSkill(@PathVariable Long agentId,
                                       @Valid @RequestBody AgentSkillRequest request) {
        return agentAdminService.addSkill(agentId, request);
    }

    @Operation(summary = "List an agent's skills")
    @GetMapping("/{agentId}/skills")
    public List<AgentSkillResponse> skills(@PathVariable Long agentId) {
        return agentAdminService.getSkills(agentId);
    }

    @Operation(summary = "Remove a category skill from an agent")
    @ApiResponse(responseCode = "204", description = "Removed")
    @DeleteMapping("/{agentId}/skills/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSkill(@PathVariable Long agentId, @PathVariable Long categoryId) {
        agentAdminService.removeSkill(agentId, categoryId);
    }
}
