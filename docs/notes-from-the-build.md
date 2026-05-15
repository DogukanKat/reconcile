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

## 2026-05-14 — Spring Kafka retry topic wiring

Bean route over annotation. `@RetryableTopic` on the listener method
is the textbook way but it binds the retry config to one consumer,
and Phase 3 will land at least one more (webhook dispatch). A
`RetryTopicConfiguration` bean with `includeTopic("...v1")` is
slightly more code now and saves rewriting later.

Two surprises while writing the test:

- `DestinationTopic.Properties` (returned by
  `getDestinationTopicProperties()`) doesn't expose its
  `shouldRetryOn` BiPredicate. The outer `DestinationTopic` class
  does — its `shouldRetryOn(Integer, Throwable)` method delegates
  to the private field. The test had to wrap each Properties into
  a `new DestinationTopic("name", props)` to reach the predicate.
  Spring Kafka could expose it directly; opening an issue is on my
  list.
- The default `topic.suffix.strategy` is `SUFFIX_WITH_DELAY_VALUE`
  which produces `-retry-1000`, `-retry-3000`, `-retry-9000`. Hard
  to read when you don't know the backoff in your head. Switched
  to `suffixTopicsWithIndexValues()` so the suffixes become
  `-retry-0`, `-retry-1`, `-retry-2` — the attempt number is
  what's meaningful, the delay is config you can tune.

`retryOn(List.of(RetryableConsumerException.class,
SocketTimeoutException.class))` plus `traversingCauses()` mirrors
Feature 02's classifier exactly: explicit-retryable wins, network
timeout in the cause chain triggers retry, everything else falls
through to DLT on first failure. Conservative default in topology,
not just in code.

The `auto-offset-reset: earliest` setting on the main topic kept
the existing Phase 1 backlog-replay behaviour; the retry topics
inherit `latest` by Spring Kafka's default and that's the right
call — a deploy of notification-service should not replay every
retry it ever scheduled. Documented in the YAML next to the
setting.

## 2026-05-14 — DLT recoverer customization

Spring Kafka's `DeadLetterPublishingRecoverer` ships with no
stack-trace length cap. A deep Java trace (a stuck Spring proxy
chain, a recursive serializer) blows past Kafka's
`message.max.bytes=1MB` surprisingly fast. The fix is plugging in a
custom `ExceptionHeadersCreator` and setting it via
`RetryTopicConfigurationSupport.configureCustomizers(...)`. 4KB cap;
the head of the trace survives, which is the only part anyone reads
when triaging.

`HeaderNames` passed to `ExceptionHeadersCreator.create(...)` is for
honoring custom header renames. We don't have any, so the parameter
is ignored and the impl writes default `KafkaHeaders.DLT_*` names.
If we ever want renames, the indirection cost is one map lookup.

Removed `@EnableKafkaRetryTopic` from the main class when I added
the `RetryTopicConfigurationSupport` bean — the annotation imports
its own default support bean and Spring Boot's bean-override
default is `false` in 2.1+, so having both would have failed at
startup. Easy to miss; the test would have been silent because
unit tests don't boot the full context.

The `@DltHandler` lives on the same `AuthorizationEventListener`
class as `@KafkaListener`. Spring Kafka's retry-topic infra
auto-discovers it when the retry config's `includeTopic` matches
the listener's topic. The DLT record's failure metadata lands in
`KafkaHeaders.DLT_*` headers; `DLT_ORIGINAL_PARTITION` and
`DLT_ORIGINAL_OFFSET` are big-endian `Integer.BYTES`/`Long.BYTES`,
not UTF-8 — decoded with `ByteBuffer.wrap(...).getInt() /
.getLong()`. Spent more time on this than I'd like to admit.

The DLT is terminal. Nothing automatic pulls from it. Replay is a
deliberate human action; `docs/failure-modes.md` (Feature 06) will
spell out the playbook.

## 2026-05-14 — Poison-message IT against local Kafka

Picked Option B from the feature spec: real local Kafka via
`make up`, not Testcontainers. Same pattern as the payment-service
ITs around `cd4a681`; Testcontainers' Docker Desktop 4.72 issue is
still open. The IT will work in CI once the workflow gains a
`make up` step.

Five things bit me writing this one. Worth keeping for next time
anything touches retry-topic config.

**Consumer group collision.** Both notification-service and
payment-service were running locally via `bootRun` during PR
review. The IT's listener tried to join the same
`notification-service` consumer group and was starved of
partitions by the live bootRun process — `getAssignedPartitions()`
returned `[]` forever. Fix: make the listener's group ID
configurable (`${reconcile.notification.consumer.group:...}`) and
pin a fresh UUID per JVM in the test via
`@DynamicPropertySource`. The static field holding the UUID is
critical — `${random.uuid}` resolves freshly on each placeholder
access, which would split main and retry containers into
different groups.

**RetryTopicConfigurationSupport must be a subclass, not a
`@Bean`.** Returning the support instance from a `@Bean` method
registers the bean but Spring Kafka's lifecycle hooks
(`configureCustomizers`, `configureDeadLetterPublishingContainerFactory`)
never fire on it. Solution: extend
`RetryTopicConfigurationSupport` directly as a `@Configuration`
class and use `@EnableKafka` instead of `@EnableKafkaRetryTopic`.
Spring Kafka 3.x docs mention this; I missed it the first time.

**RetryTopicSchedulerWrapper required after the subclass move.**
The support subclass takes over the entire bean graph, including
the back-off manager which needs a `TaskScheduler`. Auto-config
no longer provides one. A `ThreadPoolTaskScheduler` with 2
threads covers it.

**`DLT_ORIGINAL_TOPIC` vs `ORIGINAL_TOPIC`.** Spring Kafka 3.x
exposes both `kafka_dlt-original-topic` and `kafka_original-topic`
constants. The retry-topic chain actually writes
`kafka_original-topic` on the DLT record. Tests asserting against
`DLT_ORIGINAL_TOPIC` silently fail with `null`. The `DLT_*`
constants are for the classic `DefaultErrorHandler` flow, not the
retry-topic one. Updated the listener's `@DltHandler` accordingly.

**Listener exception unwrapping.** Spring Kafka stacks at least
two framework wrappers (`ListenerExecutionFailedException` at the
listener boundary, `TimestampedException` inside the retry-topic
processor) around the user's exception. My custom
`ExceptionHeadersCreator` initially wrote the wrapper's FQCN to
the DLT header. Fix: walk the cause chain while the current
exception's class lives in `org.springframework.kafka` and stop
the moment we hit something outside the framework. The default
recoverer does something similar internally; the custom path has
to opt in.

The whole IT runs in ~12 seconds wall clock against the local
broker — three scenarios (retry-then-success, retry-exhausted,
immediate-DLT) with aggressive backoffs in `application-it.yml`
(100ms / x2 / 1s cap / 4 attempts).

## 2026-05-14 — Phase 2 wrap-up

The thing I didn't expect at the start of Phase 2: how much of the
work was operational, not algorithmic. The retry/DLT design is two
days of thinking. Wiring it so Spring Kafka's actual lifecycle
hooks fire correctly was the same again. The `@Bean` vs
`@Configuration` subclass distinction, the `DLT_ORIGINAL_TOPIC` vs
`ORIGINAL_TOPIC` constant split, the listener-exception unwrapping
through framework wrappers — each one was a quiet trap that the
unit tests didn't catch. Feature 05's IT against real Kafka was
the surface that surfaced them.

Counting retries cleanly turned out to be impossible without
overriding more of Spring Kafka's internals than seems worth it.
The `_dlq_total` counter is enough for the dashboards that
matter; the retry chain is observable via
`kafka-console-consumer` on the retry topics. Filed as a Phase 3
follow-up in ADR-0008's open-questions.

One satisfying thing: the consumer-side classifier and the
retry-topic config ended up encoding the same rule in two
different places. The unit tests for the classifier
(`ConsumerErrorClassifierTest`) are the executable spec. The
retry-topic config's `retryOn(List.of(...))` references the same
exception classes. If they ever drift, the IT in Feature 05
catches it. That feels like the right shape — not coupled
implementations, but coupled by their references to the same
domain types.

Phase 2 is six PRs stacked on `main`. The branch ergonomics are
slightly clumsy — each PR has to retarget once its parent merges
— but the alternative was one monster PR. Six reviewable
diffs of ~300 lines each beats one impossible diff of ~2000.

## 2026-05-14 — @DltHandler doesn't auto-discover with the bean route

End-to-end smoke against the running cluster found the third
Spring Kafka gotcha of Phase 2, and the unit tests had no way to
catch it. DLT records were arriving with the right headers — the
`TruncatingExceptionHeadersCreator` was firing, the unwrapped
business-exception FQCN was written — but
`AuthorizationEventListener.onDlt` itself was never called. Spring
Kafka's internal `RetryTopicConfigurer` logged
`Received message in dlt listener` at INFO and the application's
structured `consumer_dlt_received` log plus the
`reconcile_consumer_dlq_total` counter both stayed dark.

The cause: `@DltHandler` is auto-discovered only when the listener
is wired via `@RetryableTopic` on the method. With the bean
approach (`RetryTopicConfiguration` from Feature 03), Spring Kafka
uses a default DLT handler unless you explicitly call
`.dltHandlerMethod(beanName, methodName)` on the builder. The bean
approach was the right call for cross-consumer reuse, but it
silently disables the annotation-based discovery and there's no
warning at startup.

Three things this teaches:

- Unit tests that invoke listener methods directly (the cheap way
  to test message handlers) bypass exactly the discovery paths
  that production relies on. The unit test for `onDlt` passed all
  the way through Phase 2 review; the wiring it relies on was
  never exercised.
- The PoisonMessageIT in Feature 05 asserts on the producer side
  of the DLT (record headers, payload, FQCN). The consumer side
  — the `@DltHandler` actually firing — is a different code path
  that the IT didn't probe. Filed as a follow-up to extend the IT
  to assert on the `ConsumerMetrics.recordDlq` counter
  incrementing.
- Spring Kafka has multiple "auto-discovery" paths
  (`@KafkaListener`, `@RetryableTopic`, `@DltHandler`,
  `RetryTopicConfiguration` bean), and they don't all activate
  the same downstream wiring. The annotation route has more magic
  than the bean route, and the documentation doesn't list the
  differences side-by-side.

The fix is one line on the builder. The bug is one PR. The lesson
— that integration tests need to assert on the side of the
boundary the user sees, not just on what gets written across it —
is the load-bearing part.

## 2026-05-15 — Phase 3 begins: Schema Registry container

`cp-schema-registry:7.8.0` against `apache/kafka:3.9.0` KRaft — no
friction. The registry treats Kafka as a plain backing log over
the standard producer/consumer protocol, so the
Confluent-image-vs-apache-broker mismatch people warn about didn't
bite. Pinned 7.8.x because Confluent Platform 7.8 tracks the
Kafka 3.8/3.9 line; 7.9 would also work but 7.8 is the more
battle-tested tag right now.

Two small decisions worth recording:

- **Host port 8085, not the Confluent default 8081.**
  notification-service's actuator binds 8081 on the host during
  local bootRun (Phase 2, Feature 06). A developer running the
  consumer and the registry at once would collide. Inside the
  docker network the registry still listens on the conventional
  8081, so `http://schema-registry:8081` is what Debezium will use
  in Feature 03 — only the host mapping moved.

- **Fixed a latent Makefile bug while I was in there.** The
  `register-connector` target's update path used
  `--data @<(jq ...)` — bash process substitution. `make` runs
  recipes under `/bin/sh` (dash on this box), which doesn't
  support `<(...)`, so the PUT fallback always failed with
  "option --data: error encountered when reading a file". The
  POST path worked, so this only ever surfaced on the *second*
  `make register-connector` (when the connector already exists
  and the idempotent PUT kicks in). Replaced the process
  substitution with a plain `jq ... | curl --data @-` pipe.
  Pre-existing, not introduced by Phase 3; fixed here because
  Feature 01 already touches the Makefile and the feature's DoD
  is "register-connector still works."

The registry is infra-only. Nothing Java changed; the full Phase
1/2 suite (120 tests including the live-Kafka PoisonMessageIT) is
green against the now-four-service stack, which is the only thing
this feature had to prove.
