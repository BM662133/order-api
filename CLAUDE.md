# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Order API: Spring Boot 3.5.x (Java 21, Maven), root package `com.example.orders`. Partners order a SIM/plan for a subscriber; orders move through the `OrderStatus` state machine. Built in slices (see `git log`). Stay on Spring Boot 3.x (start.spring.io only generates 4.x, so don't regenerate from it).

Packages are by feature: `common/error` (ProblemDetail handling), `common/time` (Clock), `tenant` (partner auth), `order/domain` (state machine).

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

## Errors, tenancy and state

- **Errors:** throw a subclass of `common.error.ApiException` (it carries status, `type` URI, title and extra `properties()`); `GlobalExceptionHandler` renders it as `application/problem+json`. Add new `type` URIs to `ProblemTypes` and never change existing ones. Unexpected exceptions become a generic 500 — never put internal details in a response.
- **Partner auth:** `PartnerAuthenticationInterceptor` resolves the `X-Partner-Id` header (a stand-in for real auth) on the paths in `TenantWebConfig.AUTHENTICATED_PATHS` (`/partners/**`, `/orders/**`); missing/unknown/inactive → 401. Controllers get the caller by declaring a `PartnerContext` parameter. New API paths must be under those patterns.
- **Tenant isolation:** repositories extend Spring Data `Repository` (not `JpaRepository`) and declare only the finders they need; tenant-owned data is looked up by `partnerId` as well as id, and another partner's resource is a 404, never a 403.
- **Status changes** go through `OrderStatus.checkTransitionTo`, which throws `IllegalTransitionException` (409). Allowed transitions are declared in its exhaustive `switch`; update `OrderStatusTest.LEGAL` alongside it.
- **Inject `Clock`** instead of calling `Instant.now()`, so tests can control time.

## Testing notes

- `@WebMvcTest` must name its controllers (`@WebMvcTest(controllers = ...)`) and declare `@MockitoBean PartnerRepository`, because `TenantWebConfig` needs it.
- Test-only controllers are static nested `@RestController`s inside the test class, registered with `@Import`; Spring Boot's `TestTypeExcludeFilter` keeps them out of other test contexts.
- Seed data from `V1__partners_and_plans.sql` is available in `@SpringBootTest`: partners `partner-a`, `partner-b` (active), `partner-suspended` (inactive); plans `BASIC_5G`, `UNLIMITED` (active), `LEGACY_3G` (inactive).