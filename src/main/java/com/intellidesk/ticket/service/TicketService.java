package com.intellidesk.ticket.service;

import com.intellidesk.category.entity.Category;
import com.intellidesk.category.repository.CategoryRepository;
import com.intellidesk.classification.TicketClassification;
import com.intellidesk.classification.TicketClassificationService;
import com.intellidesk.common.exception.InvalidRequestException;
import com.intellidesk.common.exception.InvalidTicketStateException;
import com.intellidesk.common.exception.ResourceNotFoundException;
import com.intellidesk.common.exception.UnauthorizedAccessException;
import com.intellidesk.agent.AssignmentProperties;
import com.intellidesk.agent.service.AssignmentService;
import com.intellidesk.sla.entity.SlaPolicy;
import com.intellidesk.sla.repository.SlaPolicyRepository;
import com.intellidesk.ticket.domain.TicketPriority;
import com.intellidesk.ticket.domain.TicketStatus;
import com.intellidesk.ticket.dto.CreateTicketRequest;
import com.intellidesk.ticket.dto.TicketResponse;
import com.intellidesk.ticket.dto.UpdateTicketRequest;
import com.intellidesk.ticket.entity.Ticket;
import com.intellidesk.ticket.mapper.TicketMapper;
import com.intellidesk.ticket.repository.TicketRepository;
import com.intellidesk.user.domain.Role;
import com.intellidesk.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Ticket use cases. Authorization rules live HERE (not only in the filter
 * chain) because ownership is data-dependent: whether a user may see a ticket
 * depends on the ticket's reporter/agent, which URL rules cannot express.
 * Method-level @PreAuthorize on the controller is the coarse outer gate;
 * this service is the fine-grained inner one (defense in depth).
 *
 * <p>All reads run read-only (Hibernate can skip dirty checking); the two
 * writes are transactional - {@code create} atomically performs the
 * placeholder-INSERT / final-number-UPDATE dance described in
 * {@link TicketNumberGenerator}.</p>
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** Upper bound for page sizes - protects the database from page=1&size=100000. */
    private static final int MAX_PAGE_SIZE = 100;

    private final TicketRepository ticketRepository;
    private final CategoryRepository categoryRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final TicketNumberGenerator numberGenerator;
    private final TicketMapper mapper;
    private final AssignmentService assignmentService;
    private final AssignmentProperties assignmentProperties;
    private final TicketAuditService auditService;
    private final TicketClassificationService classificationService;

    public TicketService(TicketRepository ticketRepository,
                         CategoryRepository categoryRepository,
                         SlaPolicyRepository slaPolicyRepository,
                         TicketNumberGenerator numberGenerator,
                         TicketMapper mapper,
                         AssignmentService assignmentService,
                         AssignmentProperties assignmentProperties,
                         TicketAuditService auditService,
                         TicketClassificationService classificationService) {
        this.ticketRepository = ticketRepository;
        this.categoryRepository = categoryRepository;
        this.slaPolicyRepository = slaPolicyRepository;
        this.numberGenerator = numberGenerator;
        this.mapper = mapper;
        this.assignmentService = assignmentService;
        this.assignmentProperties = assignmentProperties;
        this.auditService = auditService;
        this.classificationService = classificationService;
    }

    // ---- commands ----------------------------------------------------------

    @Transactional
    public TicketResponse create(CreateTicketRequest request, User reporter) {
        // request -> validation (bean validation on the DTO) -> classification
        TicketClassification classification = classifySafely(request.title(), request.description());

        // explicit inputs win over classification (Phase 3 contract stays intact);
        // omitted ones come from the classifier - rule-based today, AI tomorrow
        Category category = resolveCategory(request.categoryId(), classification.categoryCode());
        TicketPriority priority = request.priority() != null
                ? request.priority()
                : classification.priority();

        // SLA calculation from the resolved priority
        SlaPolicy policy = slaPolicyRepository.findByPriorityAndActiveTrue(priority)
                .orElseThrow(() -> new IllegalStateException(
                        "No active SLA policy configured for priority " + priority));

        Instant deadline = Instant.now().plus(Duration.ofHours(policy.getResolutionHours()));
        Ticket ticket = new Ticket(
                numberGenerator.placeholder(),
                request.title().trim(),
                request.description().trim(),
                reporter,
                category,
                priority,
                policy,
                deadline
        );
        ticket.setSentiment(classification.sentiment());
        ticket.setSuggestedResponse(classification.suggestedResponse());

        Ticket saved = ticketRepository.saveAndFlush(ticket); // INSERT, id assigned
        saved.setTicketNumber(numberGenerator.format(saved.getId(), saved.getCreatedAt()));
        ticketRepository.saveAndFlush(saved);                 // UPDATE with final number

        // the audit trail starts at creation: null -> OPEN
        auditService.record(saved, null, TicketStatus.OPEN, reporter, "Ticket created");

        if (assignmentProperties.autoAssignOnCreate()) {
            // same transaction: assign best-scoring agent right away (system actor)
            saved = assignmentService.assign(saved.getId(), null);
        }

        log.info("Created ticket {} (id={}, category={}, priority={}, slaDeadline={}, agent={})",
                saved.getTicketNumber(), saved.getId(), category.getCode(),
                saved.getPriority(), deadline,
                saved.getAssignedAgent() == null ? "-" : saved.getAssignedAgent().getEmail());
        return mapper.toResponse(saved, false); // the reporter never sees agent hints
    }

    /**
     * Availability guard: classification is an ENHANCEMENT, not a dependency.
     * If any implementation (today rule-based, tomorrow an external AI service)
     * throws - provider outage, timeout, bad response - ticket creation
     * continues with safe defaults instead of failing the customer.
     */
    private TicketClassification classifySafely(String title, String description) {
        try {
            return classificationService.classify(title, description);
        } catch (Exception e) {
            log.warn("Ticket classification failed ({}); using fallback category/priority",
                    e.getMessage());
            return TicketClassification.fallback();
        }
    }

    /** Explicit categoryId wins; otherwise the classified code is resolved. */
    private Category resolveCategory(Long categoryId, String classifiedCode) {
        if (categoryId != null) {
            return categoryRepository.findById(categoryId)
                    .filter(Category::isActive)
                    .orElseThrow(() -> new InvalidRequestException(
                            "Category " + categoryId + " does not exist or is inactive"));
        }
        return categoryRepository.findByCode(classifiedCode)
                .filter(Category::isActive)
                .orElseThrow(() -> new InvalidRequestException(
                        "Classified category " + classifiedCode + " does not exist or is inactive"));
    }

    @Transactional
    public TicketResponse update(Long id, UpdateTicketRequest request, User principal) {
        if (request.title() == null && request.description() == null
                && request.categoryId() == null && request.priority() == null) {
            throw new InvalidRequestException("At least one field must be provided to update");
        }

        Ticket ticket = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", id));

        switch (principal.getRole()) {
            case CUSTOMER -> applyCustomerUpdate(ticket, request, principal);
            case ADMIN -> applyAdminUpdate(ticket, request);
            case AGENT -> throw new UnauthorizedAccessException(
                    "Agents change tickets through the workflow (status transitions), not field edits");
        }

        Ticket saved = ticketRepository.save(ticket);
        log.info("Ticket {} updated by {} id={} (role={})",
                saved.getTicketNumber(), principal.getEmail(), principal.getId(), principal.getRole());
        return mapper.toResponse(saved, principal.getRole() != Role.CUSTOMER);
    }

    // ---- queries -----------------------------------------------------------

    @Transactional(readOnly = true)
    public TicketResponse getById(Long id, User principal) {
        Ticket ticket = ticketRepository.findWithDetailsById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", id));
        assertCanView(ticket, principal);
        return mapper.toResponse(ticket, principal.getRole() != Role.CUSTOMER);
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> getTicketsOfCustomer(User customer, Pageable pageable) {
        Page<Ticket> page = ticketRepository.findByReporterId(
                customer.getId(), clamp(pageable));
        return page.map(t -> mapper.toResponse(t, false));
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> getAssignedTickets(User agent, Pageable pageable) {
        Page<Ticket> page = ticketRepository.findByAssignedAgentId(
                agent.getId(), clamp(pageable));
        return page.map(t -> mapper.toResponse(t, true));
    }

    @Transactional(readOnly = true)
    public Page<TicketResponse> getAllTickets(TicketStatus status, Pageable pageable) {
        Page<Ticket> page = (status == null)
                ? ticketRepository.findAllByOrderByCreatedAtDesc(clamp(pageable))
                : ticketRepository.findByStatus(status, clamp(pageable));
        return page.map(t -> mapper.toResponse(t, true));
    }

    // ---- authorization -----------------------------------------------------

    private void assertCanView(Ticket ticket, User user) {
        boolean allowed = switch (user.getRole()) {
            case ADMIN -> true;
            case AGENT -> ticket.getAssignedAgent() != null
                    && user.getId().equals(ticket.getAssignedAgent().getId());
            case CUSTOMER -> user.getId().equals(ticket.getReporter().getId());
        };
        if (!allowed) {
            log.debug("Access denied: user={} role={} ticket={}",
                    user.getId(), user.getRole(), ticket.getTicketNumber());
            throw new UnauthorizedAccessException(
                    "You are not allowed to access this ticket");
        }
    }

    private void applyCustomerUpdate(Ticket ticket, UpdateTicketRequest request, User customer) {
        if (!customer.getId().equals(ticket.getReporter().getId())) {
            throw new UnauthorizedAccessException("You can only edit your own tickets");
        }
        if (request.categoryId() != null || request.priority() != null) {
            throw new UnauthorizedAccessException("Only admins can change category or priority");
        }
        if (ticket.getStatus() != TicketStatus.OPEN) {
            throw new InvalidTicketStateException(
                    "Tickets can only be edited while OPEN (current status: %s)"
                            .formatted(ticket.getStatus()));
        }
        if (request.title() != null) {
            ticket.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            ticket.setDescription(request.description().trim());
        }
    }

    private void applyAdminUpdate(Ticket ticket, UpdateTicketRequest request) {
        if (request.title() != null) {
            ticket.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            ticket.setDescription(request.description().trim());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                    .filter(Category::isActive)
                    .orElseThrow(() -> new InvalidRequestException(
                            "Category " + request.categoryId() + " does not exist or is inactive"));
            ticket.setCategory(category);
        }
        if (request.priority() != null) {
            changePriorityWithSla(ticket, request.priority());
        }
    }

    /**
     * Priority drives the SLA target, so an admin changing the priority
     * recomputes the deadline from the policy of the NEW priority. The ticket's
     * slaPolicy reference is updated for consistency (deadline is authoritative).
     */
    private void changePriorityWithSla(Ticket ticket, TicketPriority newPriority) {
        if (newPriority == ticket.getPriority()) {
            return;
        }
        SlaPolicy policy = slaPolicyRepository.findByPriorityAndActiveTrue(newPriority)
                .orElseThrow(() -> new IllegalStateException(
                        "No active SLA policy configured for priority " + newPriority));
        ticket.setPriority(newPriority);
        ticket.setSlaPolicy(policy);
        ticket.setSlaDeadlineAt(Instant.now().plus(Duration.ofHours(policy.getResolutionHours())));
    }

    private static Pageable clamp(Pageable pageable) {
        // negative page indexes and oversized pages are client input - clamp
        // them, never let PageRequest.of throw a 500 for ?page=-1&size=9999
        return org.springframework.data.domain.PageRequest.of(
                Math.max(pageable.getPageNumber(), 0),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                pageable.getSort());
    }
}
