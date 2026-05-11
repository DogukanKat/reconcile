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

Docker Desktop 4.72 + Testcontainers 1.21.3 don't see eye to eye on
this machine. After enough digging the root cause is specific:
Testcontainers' embedded docker-java builds request URLs as
`/v1.32/info`, Docker Desktop 4.72's daemon rejects any client API
version below 1.40 with a 400, and the API version is hardcoded
somewhere deep inside Testcontainers. DOCKER_API_VERSION env and
docker.api.version system property were both ignored, even after
pointing DOCKER_HOST at the raw daemon socket
(`~/Library/Containers/com.docker.docker/Data/docker.raw.sock`).
Bumping docker-java to 3.4.2 didn't move the default either.

Things worth remembering for next time:

- `~/.docker/run/docker.sock` is a CLI relay; the real daemon socket
  is `docker.raw.sock`. The CLI relay returns a near-empty stub
  `/info` body that doesn't even reach the API-version check.
- A clean override probably needs Testcontainers ≥ 1.22 (if the
  default has been raised there) or a code-level injection that
  bypasses the default `DockerClientFactory`. Neither is worth a
  turn at this stage.

What I did instead: rewrote `AuthorizationRepositoryIT` to bind
against the local Postgres that `make up` brings up. It autowires
the `JdbcClient` and truncates the authorizations table in
`@BeforeEach` (captures cascade). All six round-trip scenarios run
and pass with `./gradlew test` as long as the local Postgres is up.

The testcontainers-postgresql / testcontainers-junit-jupiter
dependencies are still in `libs.versions.toml`; CI on a Linux daemon
won't hit this version-negotiation wall and `@Testcontainers` is
still the right tool there. Re-enabling it locally, once the gap
closes, is one annotation flip plus the container boilerplate.
