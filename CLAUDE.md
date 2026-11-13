# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Order API: Spring Boot 3.5.x (Java 21, Maven), root package `com.example.orders`. Currently a skeleton — no domain code yet. Stay on Spring Boot 3.x (start.spring.io only generates 4.x, so don't regenerate from it).

## Commands

Use the Maven wrapper (`./mvnw`), not a system `mvn`.

- Build + run all tests: `./mvnw test`
- Single test class: `./mvnw test -Dtest=OrderApiApplicationTests`
- Single test method: `./mvnw test -Dtest=OrderApiApplicationTests#healthEndpointReportsUp`
- Run the app: `./mvnw spring-boot:run` (port 8080; health at `GET /actuator/health`)
- Package: `./mvnw package`

There is no linter or formatter configured.

## Architecture and conventions

- **Schema is owned by Flyway, not Hibernate.** `spring.jpa.hibernate.ddl-auto=validate`, so every new `@Entity` needs a matching migration in `src/main/resources/db/migration/` (`V<n>__<description>.sql`), or the app fails to start. Never edit an applied migration; add a new one.
- **Database** is in-memory H2 (`jdbc:h2:mem:orders`), recreated on every start — no data persists between runs or tests.
- `spring.jpa.open-in-view=false`: load whatever a response needs inside the service/transaction layer, not lazily in controllers.
- **Actuator** exposes only the `health` endpoint over HTTP (`management.endpoints.web.exposure.include`).

- **Lombok** is optional and configured as an annotation processor in `pom.xml`; it is excluded from the packaged jar.
- **Smoke test** `OrderApiApplicationTests` boots the full app on a random port and asserts `/actuator/health` returns `UP` — it covers context startup, DB connection, and Flyway together. Keep it passing.

- Use constructor injection, no field @Autowired
- Return RFC 7807 ProblemDetail for errors
- Every feature needs tests; run ./mvnw test after changes
- Keep controllers thin; logic lives in services