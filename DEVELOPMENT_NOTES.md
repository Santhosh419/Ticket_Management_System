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
