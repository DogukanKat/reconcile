# Notes from the build

Working journal kept during construction. Short entries, dated,
written in the moment. Not polished. The polished version is in
the ADRs.

---

## 2026-05-11 — Phase 1 begins

Six ADRs in place; the design discussion is done for now. Today I
bootstrapped the Gradle multi-module structure. Three of the four
modules are empty placeholders — payment-service is the only one
that gets attention in Phase 1.

Decision I almost didn't make: keeping `wal_level=logical` in the
Postgres compose config from day one. Debezium needs it, but
Debezium isn't wired yet. Forgot it once on a previous project,
lost an afternoon to "why isn't replication working." This time
the future-me gets a small gift.

The other three modules each get a placeholder README explaining
why they're empty and when they'll come alive. I'd rather have
empty modules in the right shape than a tidy single-module that
needs reshaping in Phase 2.

## 2026-05-11 — Bootstrap notes

A few things I left in deliberate states. Writing them down so I
don't pretend they were obvious later.

Spring Boot's BOM manages most of the dependencies I pinned in
`libs.versions.toml` — Postgres JDBC, Flyway, JUnit, AssertJ. The
catalog versions are statements of intent; at runtime the BOM wins.
I'm fine with that. The catalog still earns its keep as a visible
declaration of what the project is built on.

Dependency versions were chosen against what was current at start.
By the time Phase 1 ships I'll re-check with a dependency report
and bump anything that drifted.

`wal_level=logical` is in the compose config but Debezium will need
more (`max_replication_slots`, `max_wal_senders`, a publication, a
replication slot) when it shows up. The setting that's there now is
the smallest one that doesn't lock me out later.

`spring.jpa.open-in-view` is on the default (true). It triggers a
warning at startup. Leaving it alone until the Authorization
aggregate arrives — that's when transaction boundaries actually
matter and I'll set it to false with the change documented.

Smoke test passed: payment-service boots clean against Postgres,
Flyway applies the empty V1, Hibernate stays quiet with zero
entities. The bootstrap is real.

## 2026-05-11 — Testcontainers vs Docker Desktop 4.72

`AuthorizationRepositoryIT` is in the repo but `@Disabled` for now.
Testcontainers 1.20.4 and 1.21.3 both fail to bring up a Postgres
container against Docker Desktop 4.72 on this machine. The
`/info` endpoint returns Status 400 with a near-empty body — every
field zero or empty, only the `com.docker.desktop.address` label
populated. `docker info` and `docker compose up` work fine, so the
daemon is reachable; the docker-java client that Testcontainers
embeds doesn't accept what the daemon is sending back to `/info`.

I tried bumping testcontainers (1.20.4 → 1.20.6 → 1.21.3),
setting DOCKER_HOST via `~/.testcontainers.properties` and via the
Gradle test task's environment block. Same failure each time. Not
worth burning another hour on it in this turn.

The integration test is real code, written against the actual
repository, with six round-trip scenarios. The moment the
compatibility gap closes (Docker Desktop point release, a
Testcontainers bump, or a tweak to the Docker context config), the
test runs as-is — only the `@Disabled` annotation comes off.
