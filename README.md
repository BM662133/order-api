# Order API

A Spring Boot 3 practice project: partners order a SIM/plan for a subscriber, and each order moves through a state machine. Provisioning runs asynchronously through a transactional outbox. Event delivery is in-process by default, with Kafka as an option.

```
CREATED ──► VALIDATED ──► PROVISIONING ──► ACTIVE
   │            │              │
   ├────────────┴──────────────┴──► FAILED
   └────────────┴──────────────┴──► CANCELLED
```

Features:

- **Idempotent create:** an `Idempotency-Key` header that expires after 24 hours.
- **Tenant isolation:** a partner can never see another partner's orders.
- **Optimistic locking:** concurrent updates are detected with versioned rows.
- **Cancel during provisioning:** the cancel is accepted while activation is in progress, and the activation is undone (compensation).
- **Status history:** an audit trail of every status change.
- **Background sweeper:** finds stalled orders and checks failed ones with the provisioning gateway.
- **Errors:** every error is RFC 7807 `application/problem+json`.

## Requirements

- **Java 21.** Maven is not needed; use the bundled wrapper, `./mvnw`.
- **Docker:** optional, and only needed to run against a real Kafka broker or PostgreSQL, and for the Postgres tests. On a Mac, Docker Desktop works, or a command-line setup through Homebrew:
  ```bash
  brew install colima docker docker-compose
  # let Docker find the Compose plugin:
  mkdir -p ~/.docker && echo '{"cliPluginsExtraDirs": ["/opt/homebrew/lib/docker/cli-plugins"]}' > ~/.docker/config.json
  colima start          # starts the VM that runs containers; `colima stop` when done
  ```

There is nothing else to install. By default the database is in-memory H2, created fresh on every start, with the schema applied by Flyway migrations. PostgreSQL is optional (see [Run with PostgreSQL](#run-with-postgresql-optional)).

## Build and test

```bash
./mvnw test                                    # build and run all tests (~20 s, no broker needed)
./mvnw test -Dtest=ProvisioningFlowTest        # one test class
./mvnw test -Dtest=OrderApiTest#getReturnsExactlyWhatCreateReturned   # one test method
./mvnw package                                 # build target/order-api-0.0.1-SNAPSHOT.jar
```

The Kafka tests use an embedded broker, so Docker is not needed for tests either.

`PostgresSmokeTest` runs the app on a real PostgreSQL through Testcontainers, and is skipped when Docker isn't available (the summary shows 6 skipped). With Colima, Testcontainers needs to be told where Docker is:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test
```

## Run the app

```bash
./mvnw spring-boot:run
# or, after ./mvnw package:
java -jar target/order-api-0.0.1-SNAPSHOT.jar
```

The app listens on port 8080. Check it with:

```bash
curl localhost:8080/actuator/health
# {"status":"UP"}
```

## Try it with curl

**Authentication.** Requests identify the caller with an `X-Partner-Id` header, which stands in for real authentication.

**Seed data.** Each start loads:

- active partners `partner-a` and `partner-b`;
- an inactive partner, `partner-suspended`;
- plans `BASIC_5G` and `UNLIMITED` (active) and `LEGACY_3G` (inactive).

### Create an order

```bash
curl -i -X POST localhost:8080/partners/partner-a/orders \
  -H 'X-Partner-Id: partner-a' \
  -H 'Idempotency-Key: demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}'
```

This returns `201 Created` with a `Location: /orders/{id}` header. The order starts in `CREATED`, and within about a second the background worker moves it to `ACTIVE`.

**Retries with the same key:**

| Request | Response |
|---|---|
| Same key, same body | The same order again, with `Idempotent-Replayed: true` |
| Same key, different body | `422 idempotency-key-reused` |

A key is reserved for 24 hours, then can be used again.

### Read an order and its history

```bash
curl localhost:8080/orders/{id} -H 'X-Partner-Id: partner-a'
curl localhost:8080/orders/{id}/history -H 'X-Partner-Id: partner-a'
```

The history lists every status change, oldest first, for example:

```json
{"orderId": "…", "history": [
  {"from": null,           "to": "CREATED",      "reason": null, "triggeredBy": "CLIENT", "occurredAt": "…"},
  {"from": "CREATED",      "to": "VALIDATED",    "reason": null, "triggeredBy": "SYSTEM", "occurredAt": "…"},
  {"from": "VALIDATED",    "to": "PROVISIONING", "reason": null, "triggeredBy": "SYSTEM", "occurredAt": "…"},
  {"from": "PROVISIONING", "to": "ACTIVE",       "reason": null, "triggeredBy": "SYSTEM", "occurredAt": "…"}
]}
```

If another partner asks for this order (`-H 'X-Partner-Id: partner-b'`), they get `404`, exactly as if it didn't exist.

### Cancel an order

```bash
curl -i -X POST localhost:8080/orders/{id}/cancel -H 'X-Partner-Id: partner-a'
```

| Order status | Response |
|---|---|
| `CREATED`, `VALIDATED` | `200`; the order is now `CANCELLED` |
| `CANCELLED` | `200`, and nothing changes (cancelling twice is safe) |
| `PROVISIONING` | `202`; `cancelRequestedAt` is set, and the worker undoes the activation, then sets `CANCELLED` |
| `ACTIVE`, `FAILED` | `409 illegal-status-transition` |

**Note:** the fake gateway activates instantly, so the `202` case is hard to trigger by hand. `ProvisioningFlowTest.aCancelDuringActivationIsCompensated` covers it deterministically.

### See every provisioning outcome

The fake provisioning gateway picks its outcome from the MSISDN's last four digits:

| MSISDN ends in | Outcome |
|---|---|
| `0000` | Activation rejected → `FAILED`, `failureReason: GATEWAY_REJECTED` |
| `0001` | Gateway unavailable → retried with backoff (1 s, 2 s, 4 s, 8 s), then `FAILED`, `failureReason: PROVISIONING_UNAVAILABLE` |
| anything else | `ACTIVE` |

Two more failure reasons come from validation:

- **`PLAN_INACTIVE`:** the plan was switched off after the order was accepted.
- **`MSISDN_IN_USE`:** the same partner already has an active or in-progress order for that number.

### Errors

Every error is `application/problem+json` with a stable `type` URI, for example:

```json
{"type": "https://order-api.example.com/problems/validation-error", "title": "Validation failed",
 "status": 400, "detail": "Request has 1 invalid field(s).",
 "errors": [{"field": "msisdn", "message": "must be an E.164 number, e.g. +447700900123"}]}
```

| Status | When |
|---|---|
| 400 | Invalid body or ID, or a missing or invalid `Idempotency-Key` |
| 401 | Missing, unknown or inactive partner (with a `WWW-Authenticate: Partner realm="order-api"` header) |
| 403 | The partner in the path doesn't match `X-Partner-Id` |
| 404 | Unknown order, or another partner's order |
| 409 | Illegal status change, or a concurrent update |
| 422 | Idempotency key reused for a different request |

## Run with Kafka (optional)

By default, outbox events are delivered in-process. With the `kafka` profile they go through a Kafka topic instead:

```bash
docker compose up -d kafka                                 # single-node Kafka on localhost:9092
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka
docker compose down                                        # when finished
```

The API behaves the same in both modes. In Kafka mode:

- **Topics:** events go to `orders.events`. Records that keep failing go to `orders.events.DLT`.
- **Keys:** records are keyed by `partnerId:msisdn`, so orders for the same number are processed in order.
- **Configuration:** the settings are in `src/main/resources/application-kafka.properties`.

To see what's on the topics, including each record's key and headers:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic orders.events --from-beginning --property print.key=true --property print.headers=true
```

**Verified against a real broker** (`compose.yaml`, Kafka 3.9.0):

| Scenario | Result |
|---|---|
| Normal order | `ACTIVE` in under a second |
| MSISDN ending `0001` | 5 consumer attempts over about 16 s, then `FAILED` `PROVISIONING_UNAVAILABLE` and a record on `orders.events.DLT` (same partition number, cause header `GatewayUnavailableException`) |
| Broker stopped, then started again | The API still accepts orders; they wait in `CREATED` and reach `ACTIVE` once Kafka is back |

The relay publishes a whole batch (up to `orders.outbox.batch-size`) before waiting for acks, so during an outage a relay pass waits at most about 10 s (`delivery.timeout.ms` in `application-kafka.properties`), however many events it holds. If the broker can't be reached at all, the pass stops after the first failed send. An outage doesn't use up delivery attempts: the relay pauses (1 s, doubling up to 60 s) and tries again, so however long Kafka is down, events wait and are published once it's back. Events with the same key are always delivered in order, even across retries.

The automated tests use an embedded broker instead (`OutboxKafkaIntegrationTest`).

## Run with PostgreSQL (optional)

By default the data is in memory and gone on restart. The `postgres` profile uses a persistent PostgreSQL instead:

```bash
docker compose up -d postgres                                 # PostgreSQL 17 on localhost:5432
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres    # or postgres,kafka for both
```

- **Data** lives in the `postgres-data` Docker volume, so orders survive restarts of both the app and the database. `docker compose down -v` deletes it.
- **Schema:** Flyway applies the migrations on first start and only new ones after that.
- **Connection:** database, user and password are all `orders` (in `src/main/resources/application-postgres.properties`).

The app still runs as a single instance: two instances against one database would deliver outbox events twice.

## Configuration

Every setting has a default in `src/main/resources/application.properties`. Override any of them with `--name=value` (for example `java -jar … --orders.idempotency.key-ttl=1h`) or an environment variable: upper-case it, replace `.` with `_` and drop `-`, so `ORDERS_IDEMPOTENCY_KEYTTL=1h`.

| Property | Default | Meaning |
|---|---|---|
| `orders.outbox.transport` | `in-process` | `in-process` or `kafka` |
| `orders.outbox.poll-interval` | `500ms` | How often the relay checks for events to deliver |
| `orders.outbox.max-attempts` | `5` | Delivery attempts before an event is given up |
| `orders.outbox.initial-backoff` / `max-backoff` | `1s` / `60s` | Retry delay: doubles each attempt, up to the maximum |
| `orders.idempotency.key-ttl` | `24h` | How long an `Idempotency-Key` is reserved |
| `orders.idempotency.purge-interval` | `1h` | How often expired keys are deleted |
| `orders.provisioning.sweep.interval` | `1m` | How often the sweeper runs |
| `orders.provisioning.sweep.stalled-after` | `15m` | Idle time after which an unfinished order is retried |

## Project layout

```
src/main/java/com/example/orders/
  common/        error handling (ProblemDetail), injectable Clock
  tenant/        partner authentication (X-Partner-Id) and PartnerContext
  plan/          plan catalogue and the @KnownPlan validator
  order/         api (controller, DTOs) → app (services) → domain (Order, state machine)
                 → persistence; history/ (status audit trail)
  outbox/        transactional outbox, relay, in-process transport; kafka/ (Kafka transport)
  provisioning/  worker, sweeper, eligibility checks, fake gateway
src/main/resources/db/migration/   Flyway migrations V1–V9
```

`CLAUDE.md` describes the architecture and conventions in more detail.
