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

## 2026-05-12 — Debezium wiring

Compose now brings up three containers: Postgres (already there),
Kafka in KRaft mode (single-node, no Zookeeper), and Debezium Connect.
Kafka's two-listener split — PLAINTEXT for inside-docker traffic on
9092, EXTERNAL for host on 9094 — is the smallest config that lets
both Debezium (inside the network) and any local consumer (outside)
talk to the same broker without an advertised-listener fight.

Connector config in `infra/debezium/outbox-connector.json`. Three
choices worth recording:

- `snapshot.mode: no_data` skips the initial snapshot. Phase 1 has no
  meaningful pre-existing rows worth replaying; we start streaming
  from the WAL position the connector grabs on first boot. Phase 2
  retry scenarios can revisit this.
- `EventRouter` SMT maps `aggregatetype` → topic suffix. Routing
  template `reconcile.$${routedByValue}.v1` produces
  `reconcile.authorization.v1` and `reconcile.refund.v1` once events
  start flowing. Aligns with ADR-0005.
- `table.expand.json.payload: true` unwraps the JSONB `payload`
  column into the Kafka message value, so consumers don't have to
  parse a JSON string inside a JSON envelope. Costs a little CPU per
  event, buys readable downstream code.

`make register-connector` is idempotent — POSTs the connector on first
call, PUTs the config on subsequent calls. Connector ID is fixed
(`payment-outbox`) so the Connect cluster remembers it across docker
restarts via the `reconcile-connect-configs` topic.

Open thing for later: secrets. Right now the Postgres password sits
in the connector JSON in cleartext. Fine for local dev, ugly for any
shared environment. Debezium supports config providers; the upgrade
is one block in the Connect config.

## 2026-05-12 — Docker Desktop socket layering investigation

Came back the next day to take another swing at this, narrower
hypothesis: `~/.testcontainers.properties` is a config path that
Testcontainers reads directly (not via env or system properties).
Wrote:

```
docker.host=unix:///Users/kadogukan/Library/Containers/com.docker.docker/Data/docker.raw.sock
docker.api.version=1.41
```

Result is mixed and worth recording. `docker.host` did get picked
up — `EnvironmentAndSystemPropertyClientProviderStrategy` connected
to `docker.raw.sock` (the real daemon, not the CLI relay), and the
error mode flipped from "stub /info body" to "client version 1.32
is too old. Minimum supported API version is 1.40". So the daemon
sees the client now. But `docker.api.version=1.41` did not move the
needle — the request still goes out as `/v1.32/info`. The key name
is probably wrong (testcontainers' docs are quiet about this) or
docker-java's `DefaultDockerClientConfig` is ignoring this slot of
the properties file by design.

The other two strategies (`UnixSocketClientProviderStrategy`,
`DockerDesktopClientProviderStrategy`) kept hitting
`~/.docker/run/docker.sock`, which is still the CLI relay, and
still returns the stub body that probe rejects.

Net: I now know two of the three pieces and I'm one undocumented
property name (or one source-dive into docker-java) away from a
clean fix. Not going to spend another turn on it; the local-Postgres
IT keeps the test suite honest, and the day this resolves the diff
is small (~20 lines).

## 2026-05-13 — Correlation IDs across the outbox boundary

Wired a correlation ID end-to-end without adding a parameter to a
single call site. The trick was MDC: a servlet filter binds
`X-Correlation-Id` (or a generated UUID) under `correlationId`, and
`OutboxWriter.publish` reads `MDC.get("correlationId")` when it
constructs the entry. The application/service layers don't know the
correlation ID exists. Same shape on the consumer side — the listener
reads the Kafka header and binds it to MDC for the duration of
`onAuthorizationEvent`.

The Debezium piece is one line in the connector config: add
`correlation_id:header:correlationId` to
`transforms.outbox.table.fields.additional.placement`. The SMT
projects the column straight onto a Kafka header. No code change in
Debezium-land.

Two small surprises:

- `OutboxEntry` is a record. Adding a 7th field broke every direct
  constructor call in the repository IT, which I'd forgotten about.
  Mechanical fix but a reminder that record-as-record means you don't
  get optional-arg ergonomics — every caller is a hard dependency on
  the shape. Worth it for the immutability and the compact
  constructor's null checks; just don't pretend the migration is
  free.
- Spring Boot auto-wires `@Component` filters into the security
  chain order. `@Order(Ordered.HIGHEST_PRECEDENCE)` on
  `CorrelationIdFilter` was probably unnecessary — Boot would have
  put it first anyway because no other filter in this app
  precedes it. Left the explicit ordering in because the next
  filter that lands (auth, rate-limit, whatever) will need
  to think about ordering anyway and the explicit annotation
  documents the intent.

ArchUnit test for the `@PostMapping` → `@RequestHeader("Idempotency-Key")`
rule lives under `..architecture..` with a `BadController` fixture
that violates it. Negative test passes, positive test passes against
the real `..api..` package. The negative case isn't theatre — it's the
only thing that proves the rule is restrictive enough.

## 2026-05-14 — Consumer error taxonomy

Two marker exceptions (`RetryableConsumerException`,
`NonRetryableConsumerException`) plus a `ConsumerErrorClassifier`
that returns one of two `Classification` enum values. No sealed
parent. I considered one — it would let the classifier use an
exhaustive switch on the two children — but the classifier already
has to handle every random `RuntimeException` that hits the
listener, so the exhaustive switch wouldn't actually be exhaustive
over what the classifier sees. A common parent just adds a layer.

The feature spec listed `TransientDataAccessException` as a
retryable case. Dropped it: spring-data isn't on
notification-service's classpath (only `spring-boot-starter` and
`spring-kafka`), and pulling spring-data in just to reference one
exception type would be the kind of "import a framework to use one
class" pattern I'd push back on in review. The retry chain can
look at the cause for a `SocketTimeoutException` for the same
practical effect.

The conservative default — unknown `RuntimeException` →
NON_RETRYABLE — is the most important decision in the file. The
alternative (retry by default) is the textbook way to amplify a
poison pill into a slow-motion incident. ADR-0008 in Feature 06
will spell this out for the reader.

One subtle case I tested explicitly:
`RetryableConsumerException` wrapping a `DeserializationException`
classifies as RETRYABLE. The thrower made an explicit claim and
the classifier honors it instead of walking the cause chain
looking for a reason to override. If we ever need the opposite,
that's a documented decision, not a bug.

