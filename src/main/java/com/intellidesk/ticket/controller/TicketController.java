package com.intellidesk.ticket.controller;

import com.intellidesk.agent.service.AssignmentService;
import com.intellidesk.common.response.PageResponse;
import com.intellidesk.security.IntelliDeskUserDetails;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.dto.CreateTicketRequest;
import com.intellidesk.ticket.dto.TicketHistoryResponse;
import com.intellidesk.ticket.dto.TicketResponse;
import com.intellidesk.ticket.dto.TransitionRequest;
import com.intellidesk.ticket.dto.UpdateTicketRequest;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.mapper.TicketMapper;
import com.intellidesk.ticket.service.TicketAuditService;
import com.intellidesk.ticket.service.TicketService;
import com.intellidesk.ticket.service.TicketWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Ticket endpoints. Role gates here are the COARSE layer (@PreAuthorize);
 * ownership checks (a customer sees only his own ticket, an agent only his
 * assigned ones) are data-dependent and live in {@link TicketService} /
 * {@link TicketWorkflowService}.
 */
@Tag(name = "Tickets", description = "Ticket lifecycle: creation, retrieval, updates, workflow")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketWorkflowService workflowService;
    private final TicketAuditService auditService;
    private final AssignmentService assignmentService;
    private final TicketMapper mapper;

    public TicketController(TicketService ticketService,
                            TicketWorkflowService workflowService,
                            TicketAuditService auditService,
                            AssignmentService assignmentService,
                            TicketMapper mapper) {
        this.ticketService = ticketService;
        this.workflowService = workflowService;
        this.auditService = auditService;
        this.assignmentService = assignmentService;
        this.mapper = mapper;
    }

    @Operation(summary = "Create a ticket (CUSTOMER)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed / unknown category"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Not a customer")
    })
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        TicketResponse response = ticketService.create(request, principal.getUser());
        return ResponseEntity
                .created(URI.create("/api/tickets/" + response.id()))
                .body(response);
    }

    @Operation(summary = "Get one ticket (owner CUSTOMER / assigned AGENT / any ADMIN)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "403", description = "No access to this ticket"),
            @ApiResponse(responseCode = "404", description = "Ticket does not exist")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','AGENT','ADMIN')")
    public TicketResponse getById(
            @PathVariable Long id,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        return ticketService.getById(id, principal.getUser());
    }

    @Operation(summary = "List the caller's own tickets (CUSTOMER)")
    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    public PageResponse<TicketResponse> myTickets(
            @AuthenticationPrincipal IntelliDeskUserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.from(ticketService.getTicketsOfCustomer(principal.getUser(), pageable));
    }

    @Operation(summary = "List tickets assigned to the caller (AGENT)")
    @GetMapping("/assigned")
    @PreAuthorize("hasRole('AGENT')")
    public PageResponse<TicketResponse> assignedTickets(
            @AuthenticationPrincipal IntelliDeskUserDetails principal,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.from(ticketService.getAssignedTickets(principal.getUser(), pageable));
    }

    @Operation(summary = "List all tickets, optionally filtered by status (ADMIN)")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<TicketResponse> allTickets(
            @RequestParam(required = false) TicketStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.from(ticketService.getAllTickets(status, pageable));
    }

    @Operation(summary = "Partial field update (CUSTOMER: own+OPEN title/description, ADMIN: all fields)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Empty update / unknown category"),
            @ApiResponse(responseCode = "403", description = "Not allowed to change these fields"),
            @ApiResponse(responseCode = "404", description = "Ticket does not exist"),
            @ApiResponse(responseCode = "409", description = "Ticket no longer editable (not OPEN)")
    })
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")
    public TicketResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTicketRequest request,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        return ticketService.update(id, request, principal.getUser());
    }

    // ---- workflow (Phase 4/5/6) --------------------------------------------

    @Operation(summary = "Perform a status transition (role rules enforced per transition)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transitioned"),
            @ApiResponse(responseCode = "400", description = "Missing resolution note"),
            @ApiResponse(responseCode = "403", description = "Role may not perform this transition"),
            @ApiResponse(responseCode = "404", description = "Ticket does not exist"),
            @ApiResponse(responseCode = "409", description = "Illegal transition for current status")
    })
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('CUSTOMER','AGENT','ADMIN')")
    public TicketResponse transition(
            @PathVariable Long id,
            @Valid @RequestBody TransitionRequest request,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        Ticket ticket = workflowService.transition(
                id, request.target(), principal.getUser(), request.reason(), request.resolution());
        return mapper.toResponse(ticket);
    }

    @Operation(summary = "Append-only audit trail of the ticket (viewer must have ticket access)")
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('CUSTOMER','AGENT','ADMIN')")
    public List<TicketHistoryResponse> history(
            @PathVariable Long id,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        ticketService.getById(id, principal.getUser()); // access check with full graph
        return auditService.getHistory(id);
    }

    @Operation(summary = "Assign to the best-scoring agent (ADMIN; scoring documented in README)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned"),
            @ApiResponse(responseCode = "409", description = "Ticket not OPEN or already assigned")
    })
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasRole('ADMIN')")
    public TicketResponse assign(
            @PathVariable Long id,
            @AuthenticationPrincipal IntelliDeskUserDetails principal) {
        Ticket assigned = assignmentService.assign(id, principal.getUser());
        return mapper.toResponse(assigned);
    }
}
