# IntelliDesk — Development Notes

Engineering log: bugs hit during development, root causes, fixes, and the
regression tests added for each. Written to stay useful during debugging
assessments: always explain **why**, not just what.

---

## Phase 1 (setup, entities, repositories)

### 1. Sandbox toolchain (environment, not code)

This project was developed in a restricted sandbox with no direct access to
Maven Central. The toolchain (JDK, Maven 3.9.7, dependency repository) was
assembled from git-clonable sources and PyPI packages. **This affects only the
sandbox — anyone with normal internet uses the standard `pom.xml` and Maven
Central as usual.** Noted here for transparency; zero impact on the deliverable.

### 2. `user` is a reserved SQL word (design, not a bug)

Root cause: mapping the `User` entity to a table named `user` breaks on
PostgreSQL (`user` is reserved).
Fix: explicit `@Table(name = "users")` + named unique constraint.
Lesson: always name JPA tables explicitly; never rely on the default
entity-name-to-table translation.

### 3. JPA auditing inside `@DataJpaTest`

Symptom-to-be: `createdAt` would have been null inside `@DataJpaTest` slices if
`@EnableJpaAuditing` lived only on a `@Configuration` class, because slices do
not load arbitrary configurations — but they always load the
`@SpringBootConfiguration` class.
Fix: `@EnableJpaAuditing` sits on `IntelliDeskApplication` itself.
Regression test: any `@DataJpaTest` persisting an entity with `createdAt`
(asserted non-null via audit behavior in `TicketJpaMappingTest`).

### 4. `@Lob` vs `text` on PostgreSQL (avoided by design)

`@Lob String` maps to `oid`/large-object handling on PostgreSQL, which leaks
into JDBC as a separate storage mechanism and complicates reads.
Fix: `@Column(columnDefinition = "text")` for long strings (`tickets.description`,
`ticket_comments.message`) — portable across PostgreSQL and H2.

### 5. Optimistic locking smoke check

The `version` column with `@Version` must actually increment on updates or it is
decoration. Regression test: `optimisticLockingVersionIncrements`.

---

## Phase 1 review round (senior-review pass, no new features)

### 6. Missing index on `agent_skills(category_id)`

Root cause: the composite unique constraint `(agent_id, category_id)` only serves
agent-first lookups. `findByCategoryId` (Phase 5 assignment algorithm) would full-scan.
Fix: added `idx_agent_skills_category`. Lesson: every FK/access path that a known
query will use needs its own index — composite indexes are not interchangeable.

### 7. Unused dependencies in the POM (jjwt)

Root cause: I declared `jjwt-api/impl/jackson` during initial setup for Phase 2,
violating "no dead dependencies" review hygiene.
Fix: removed all three; they will be re-added in Phase 2 when `JwtService` actually
imports them. Lesson: dependencies enter the build when code starts using them.

### 8. Two mutation paths for ticket status

Root cause: `Ticket` exposed both `setStatus` and `changeStatus`, so future code
could bypass the state-machine contract silently.
Fix: removed the public setter; `changeStatus` is the only door. The mapping test
now exercises `changeStatus`. Lesson: aggregate invariants need a single mutation
entry point (DRY applied to domain rules, not just code).

### 9. Auditing behavior was implicit, not pinned

Root cause: `createdAt/updatedAt` are `NOT NULL`, so a broken auditing setup fails
every insert — but the failure message would point at the column, not the wiring.
Fix: `TicketJpaMappingTest` now asserts both fields explicitly after a persist.
Lesson: pin cross-cutting framework behavior where it can fail confusingly.

### 10. Observation (no change): enum portability

On H2, `@Enumerated(STRING)` maps to a native H2 `enum` column; on PostgreSQL to
`varchar(20)` + a CHECK constraint (Hibernate 6.5 behavior). Both round-trip
correctly and tests cover both dialects. Production target is PostgreSQL.

### 11. Fuzzy search/replace silently under-applied (tooling, twice)

Symptom: after "removing" the jjwt dependencies and "adding" an import, the build
failed with the OLD state (unresolved `${jjwt.version}`, missing `Index` symbol).
Root cause: fuzzy-match edits whose old/new text differ by only one line can match
a smaller region than intended or appear to succeed while changing nothing.
Fix: re-applied with exact anchors and verified each change with `grep` before
rebuilding. Lesson (debugging assessment gold): a failed build right after an edit
means the edit, not the compiler, is the first suspect — always diff the file
before theorizing.

---

## Phase 2 (authentication, JWT, security)

### 12. `expirationMinutes` bound to `0` -> startup refused

Symptom: `Binding to target JwtProperties failed ... Value: "0", Reason: must be positive`.
Root cause: the default-profile `intellidesk.security.jwt` block was missing from
application.yml (lost during a sandbox restore + manual history reconstruction).
Spring binds a missing primitive `int` property as 0, and our `@Positive`
validation refused the misconfiguration. Fix: restored the block.
Lesson: primitive binding defaults are silent danger - `@Validated` on
`@ConfigurationProperties` turns them into loud startup failures, which is
exactly what you want for security configuration.

### 13. TestRestTemplate: "cannot retry due to server authentication, in streaming mode"

Symptom: integration tests failed with an I/O error on `POST /api/auth/login`
when the server answered 401.
Root cause: JDK `HttpURLConnection` (the default TestRestTemplate engine) treats
a 401 as an authentication challenge and tries to REPLAY the request body, but a
streamed POST body is not repeatable.
Fix: added `org.apache.httpcomponents.client5:httpclient5` (test scope, version
managed by the Boot BOM); Boot auto-detects it and uses Apache HttpClient, which
returns 401 as an ordinary response.
Lesson: test-tooling failures can be HTTP-client internals, not app bugs -
always read the exact I/O error before touching production code.

### 14. Spring Security 6.3 API detail: dispatcher-type matchers

`requestMatchers(DispatcherType.ERROR)` does not compile in Security 6.3 - the
varargs overload takes `RequestMatcher`s. Correct form:
`requestMatchers(new DispatcherTypeRequestMatcher(DispatcherType.ERROR))`.
Lesson: security DSLs change between minor versions; the compiler list of
"applicable methods" in the error tells you the real signatures.

### 15. Design note: one DB query per authenticated request

`JwtAuthenticationFilter` re-loads the user from the database on every request.
Trade-off: instant revocation (deactivate a user -> their existing tokens die on
the next request) at the cost of one cheap primary-key query. Caching claims
only would trade that away; noted as a conscious decision, revisitable with a
short-TTL cache if profiling ever demands it.

---

## Phase 3 (ticket CRUD, DTOs, authorization)

### 16. Ticket numbers: derived from the primary key, written in two steps

Requirements collision: the human-readable number must be UNIQUE + NOT NULL,
but the natural source of uniqueness (the id) only exists AFTER the INSERT.
Options considered: pre-guessed counters (race under concurrency), random
suffixes (needs retry-on-conflict, non-deterministic), DB sequences (dialect
portability). Chosen: persist with a unique 13-char placeholder, flush (id
assigned), overwrite with `TKD-<year>-%06d(id)`, commit - all inside ONE
transaction, so the placeholder is never visible to anyone else. Bonus: purely
sequential numbers leak ticket volume to outsiders; id-derived numbers are
stable and opaque enough. Regression tests: `TicketNumberGeneratorTest`
(format, UTC year boundary, column length) + create tests asserting the final
number.

### 17. `non_null` JSON inclusion means "absent", not "null"

`spring.jackson.default-property-inclusion: non_null` drops null fields from
responses. The first integration test wrongly asserted `contains("assignedAgent":null)`
- the field is OMITTED. The test was fixed, not the API: omitting is the better
contract (smaller payloads; clients use presence checks).
Lesson: write API tests against the intended CONTRACT, then make the config
match - not the other way round.

### 18. Unknown sort properties: map `PropertyReferenceException` or clients get 500

`GET /api/tickets?sort=bogusProperty` blew up with a 500 because Spring Data
validates sort properties lazily at query creation and throws
`PropertyReferenceException`, which had no handler. Fix: dedicated handler ->
400 `INVALID_SORT_PROPERTY`.
Lesson: every user-controllable input that reaches the data layer (page, size,
sort, filters) is an attack/error surface - clamp sizes, map framework
exceptions.

### 19. Why `PageResponse<T>` instead of returning `Page<T>`

Serializing Spring Data's `PageImpl` is explicitly unsupported long-term (its
JSON shape depends on internals and has changed across versions). A tiny
envelope (`content, page, size, totalElements, totalPages, first, last`)
freezes the public contract.

### 20. Authorization layering

URL rules (`/api/admin/**`) cannot express ownership ("customer sees only HIS
tickets"), so `TicketService` performs data-dependent checks
(`assertCanView`, per-role update rules). The controller's `@PreAuthorize`
remains as the coarse outer gate - two independent layers, either one stops a
violation.

---

## Phase 4 (business intelligence: workflow, audit, assignment, SLA monitor)

### 21. `Set.of` iteration order is unspecified - error messages were flaky

`allowedTransitions()` returned `Set.copyOf(...)`, and the 409 message printed
that set (`Allowed: [IN_PROGRESS, ESCALATED]`). `Set.of`/`Set.copyOf` make no
ordering promise, so the same request could answer with a different bracket
order on different JVM runs - the integration test asserting the message was
flaky **by construction of the JDK, not the test**. Fix: `EnumSet.copyOf` +
unmodifiable view; `EnumSet` iterates in declaration order, so the message is
deterministic and slightly cheaper.
Lesson: any collection that ends up in user-visible text or test assertions
must have a defined order.

### 22. `Stream.max` on a single element never calls the comparator

Two pool tests stubbed the workload count of their only candidate and Mockito
(Strict Stubs) failed them with `UnnecessaryStubbingException`. Root cause:
`reduce` initializes with the first element, so for a one-element stream the
comparator - and therefore `factsOf` - never runs. The stubs really were
unused. This is also why tie-break bugs survive single-candidate tests.
Lesson: strict stubbing is a design-reviewer; when it complains, first ask
which production path was not exercised.

### 23. Tie-break direction: express intent, don't rely on comparator gymnastics

The first tie-break implementation was
`max(comparingDouble(score).thenComparing(comparingLong(id).reversed()))`,
which picks the HIGHER id on ties while the documentation promised the lower
one - the bug survived because no test pinned an exact tie. The fix reads as
the rule: `min(score.reversed().thenComparingLong(id))` = best score, then
lower id, with a dedicated `tiesBreakToTheLowerAgentId` test.
Lesson: document-then-test the tie-break explicitly; comparators are the
classic place where intent and code diverge silently.

### 24. Reopen semantics: OPEN means "back in the assignment queue"

The first reopen implementation cleared resolution data but kept the assigned
agent, producing tickets that were `OPEN` yet attached to an agent - and
unassignable, because `POST /assign` only accepts OPEN tickets. The
integration test caught it (expected 200, got 409). Fix: reopening to OPEN
clears the stale agent; the admin can reassign; `escalatedAt` is deliberately
kept as the record of the last escalation.
Lesson: every status must have one clear queue semantics, and tests that walk
the full lifecycle are the only ones that surface state-machine compost.

### 25. Feature flags and contract tests: default OFF, enable per context

`auto-assign-on-create=true` as the global default silently changed the
Phase-3 contract (create returned ASSIGNED instead of OPEN) and cascaded into
six failures. Decision: ship the flag default-OFF in `application.yml`, turn
it on explicitly where the feature is exercised (the workflow integration
test passes it as a `@SpringBootTest` property). Old contract stays green,
new behavior is still fully verified.
Lesson: new features that change existing endpoints' observable behavior
belong behind flags whose default keeps the old contract.

### 26. Audit trail from day one: creation is a transition too

History rows for `null -> OPEN` (`oldStatus` nullable) mean the trail starts
at creation, not at the first transition - and auto-assignment shows up as
its own `OPEN -> ASSIGNED` row with the scoring reason in plain text. The
duplicate guard in `TicketAuditService` (same ticket/status/reason) is what
makes scheduler escalation idempotent even if the row-recheck inside the
transaction ever regressed: three independent layers (scan filter excludes
ESCALATED, per-row status recheck, audit guard).
Lesson: idempotency of background jobs is cheapest when the audit log itself
refuses duplicates.

### 27. Edit tooling: verify every multi-line edit by grep

Twice in this phase a fuzzy "search & replace" edit reported success while
applying only partially (missing imports in `TicketController`, a leftover
`REOPEN_WINDOW` constant). The fix each time was a deterministic scripted
edit plus `grep` verification. The compiler catches these eventually - but
only after a full failed compile cycle.
Lesson (recurring): trust anchors, not fuzzy matches; grep after batches.

### 28. The classification seam: enhancement, never dependency

`TicketClassificationService` is one interface, one result record, and a
config-selected implementation (`@ConditionalOnProperty` with
`matchIfMissing = true` for the rule-based default). Three decisions worth
remembering:

1. The contract returns a category CODE, not the entity - the abstraction
   stays persistence-free, the service layer resolves the code (and falls
   back to OTHER if a code is unknown).
2. `TicketService.create` calls it through `classifySafely`: any exception
   becomes a warning plus `TicketClassification.fallback()`. When an AI
   provider is added later, its outage degrades to "ticket lands in the
   general queue" - ticket creation can never be down because a
   classification dependency is down. The fallback path has its own unit test
   (classifier throws -> ticket still created).
3. The suggested response is an agent hint, so the mapper gained a
   viewer-aware overload and every customer-facing path strips it. Sentiment
   stays visible - it describes the customer's OWN message.
Contract preservation: explicit `categoryId`/`priority` in the request still
win; classification only fills gaps. That is why 127 pre-existing tests kept
passing unchanged while the creation flow grew a whole new stage.

## Audit round (senior review, no new features)

### 29. A transition response leaked the agent hint to customers

`POST /api/tickets/{id}/status` is used by CUSTOMERS (close, reopen) and was
mapped with `toResponse(ticket)` - the hints-enabled overload - so the
internal `suggestedResponse` rode along in every customer's 200 response.
Every other path was viewer-aware; this one endpoint was missed. Fix: role
aware flag here too; regression pinned in the lifecycle integration test
(customer close response must not contain "suggestedResponse").
Lesson: when a DTO gains a sensitive field, grep EVERY mapper call site -
"who is the viewer?" is a per-endpoint question.

### 30. The SLA monitor ran the whole batch in one transaction

`escalateBreachedTickets` was `@Transactional`, so all pages of a scan shared
one transaction: a single poisoned row rolled back every other escalation in
the run, and row locks were held across the whole scan. It also paged with
`page++` while successful escalations removed rows from the result set - the
classic paging-while-mutating skip (rows shifted from page 1 into page 0 were
missed until the NEXT run). Fix: no outer transaction (`escalateForSla`
already opens one per row), per-row try/catch so one bad ticket cannot stop
the scan, and always fetch page 0 with a no-progress break - successful
escalations refill page 0, failures are retried next run. Two new unit tests:
failure isolation and shifted-row pickup.
Lesson: background jobs want per-work-item transactions; paging a live,
mutating set requires either keyset pagination or always-page-0 with a
progress guarantee.

### 31. Expected conflicts answered 500: optimistic-lock and unique races

Two actors transitioning/assigning/editing the same ticket end with the
loser's `@Version` check failing -> `ObjectOptimisticLockingFailureException`
-> generic 500. Two parallel registrations with the same email (after both
passed the pre-check) -> `DataIntegrityViolationException` -> 500. Both are
normal, retryable business conflicts: dedicated handlers now answer 409
(`CONCURRENT_MODIFICATION` / `DUPLICATE_RESOURCE`). Unit-tested directly on
the handler.
Lesson: any entity with `@Version` and any table with a unique constraint
WILL produce these exceptions in production - map them the day you add the
constraint, not after the first 500 page.

### 32. Assignment queried the DB per candidate - inside the comparator

`factsOf` (2 queries per agent) was invoked from the comparator, so scoring
N candidates issued up to ~2N queries and re-scored the same agent repeatedly.
Fixed with two batched reads (skills by `agentIdIn`, one grouped workload
count) and a single pass with an explicit lower-id tie-break; the workload
admin endpoint had the same shape and got the same treatment. A regression
test pins "one batched call per table, never a per-agent query".
Lesson: never do data access inside a comparator; batch first, score once,
then select.

### 33. Small catches: negative page indexes and an honest JWT log

`clamp` only capped the page size, so `?page=-1` reached `PageRequest.of`
and blew up with a 500; it now clamps page and size (mocked-Pageable unit
test, because `PageRequest.of` refuses negative indexes at construction).
And `JwtService` logged "HS256" regardless of key size while jjwt picks the
SHA variant from the key - the log now states the key bits instead of an
algorithm it does not control. Debugging starts from the logs; logs must not
lie.
