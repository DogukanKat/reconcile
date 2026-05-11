# ADR-0007: JdbcClient over JPA for aggregate persistence

- Status: Accepted
- Date: 2026-05-11
- Phase: 1 (Foundations)

## Context

The Phase 1 bootstrap pulled in `spring-boot-starter-data-jpa` as the
default Spring choice for persistence. Before writing the
Authorization aggregate, I sat with that decision for a turn and
decided it doesn't pay off here.

The aggregate is modeled as records and a sealed interface for
status. JPA wants entity classes with a no-arg constructor and
mutable fields it can populate via reflection, plus lifecycle
annotations and lazy-loading proxies. Hibernate 6.x has partial
record support, but the aggregate root and any `@Embeddable` still
have to be classes. Bending records and sealed types into JPA's
shape kills the parts of the model that earn their keep.

Spring 6.1 ships `JdbcClient`, which is a focused SQL helper —
parameterized queries, `RowMapper`s, transactions through Spring's
existing manager. Nothing magic, no proxies, no lazy collections.

## Options considered

### (A) Spring Data JPA with entity classes

Use `JpaRepository<Authorization, UUID>`. Aggregate becomes a
mutable class with `@Entity`, `@Id`, `@Embedded`, `@OneToMany` for
captures. Spring Data generates the read/write paths.

Pros: less SQL to write. Auto-derived query methods. Cache,
dirty-checking, second-level cache available later.

Cons: the aggregate is no longer a record. Captures collection
becomes a `@OneToMany` with cascade and orphanRemoval decisions,
each of which is a footgun. Lazy loading drags
`LazyInitializationException` into testing and debugging. The
"magic" of Spring Data repositories obscures what SQL actually runs;
production payment systems eventually need to know.

### (B) JdbcClient with pure record aggregate

Aggregate stays a record. State is a sealed interface. Repository
hand-writes SQL via `JdbcClient`. Save is a single transaction:
upsert the authorization row, delete-and-insert captures.

Pros: aggregate is JPA-free, immutable, easy to construct in unit
tests without any framework. SQL is visible and reviewable. No
proxy surprises. Spring `@Transactional` still works the same way.

Cons: more lines of SQL and mapping code. Aggregate's captures
collection is managed by the repository, not by ORM cascade — but
that's not really a cost, just an explicit shape.

## Decision

(B). Replace `spring-boot-starter-data-jpa` with
`spring-boot-starter-jdbc` and write the repository against
`JdbcClient`.

## Consequences

What this makes easier:

- The aggregate is exactly what the ADRs said it would be: records,
  sealed interface, immutable transitions. No JPA tax on the
  domain model.
- SQL is in the codebase, not generated. When a query is slow, the
  question of "what's actually running" has a one-file answer.
- The OSIV warning that Spring Boot emits with JPA on the
  classpath goes away on its own — it was a JPA-only feature.

What this makes harder:

- Every save path is hand-written. The aggregate-save pattern
  (`DELETE FROM captures WHERE authorization_id = ?` followed by
  `INSERT INTO captures (...) VALUES (...)` for each capture in the
  current state) lives in the repository and depends on captures
  having domain-generated UUIDs so the re-inserted rows keep their
  identity.
- No dirty-checking. The repository writes whatever the in-memory
  aggregate says, every time. That's a feature for the outbox
  pattern (every save is an explicit choice) but it means there's
  no escape hatch for "I forgot to call save".

## Revisit when

Revisit if the aggregate count multiplies beyond a handful and the
repetitive `RowMapper` / save boilerplate starts to outweigh the
modeling clarity, or if a query layer (read model, Phase 4) wants
projections that JPA-style entity graphs would describe more
concisely. Neither is a Phase 1 concern.

## Related decisions

- ADR-0001: Authorization and Refund as separate aggregates (defines
  what the persistence boundary is)
- ADR-0002: Derived state for authorization status (the read-time
  computation that's easier without JPA in the way)

## References

- Spring Framework 6.1 `JdbcClient` reference
  (https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html#jdbc-JdbcClient).
- Hibernate 6.x record support notes; partial coverage was the
  spark for sitting with this decision instead of accepting the
  starter-data-jpa default.
