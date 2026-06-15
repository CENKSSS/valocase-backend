# Deployment & Configuration

Configuration is split across three files under `src/main/resources`:

- `application.properties` — shared, non-secret defaults plus active-profile and
  server-port handling. Contains no database credentials.
- `application-dev.properties` — local development (local PostgreSQL). Active by
  default.
- `application-prod.properties` — production. Reads all sensitive values from
  environment variables; no secrets are committed.

Shared defaults that apply to every profile: Flyway enabled, JPA
`ddl-auto=validate`, `valocase.catalog.import-on-startup=false`.

## Running locally (dev profile)

The `dev` profile is active by default (`spring.profiles.active` defaults to
`dev`), so no extra flags are needed:

```
mvnw spring-boot:run
```

This uses local PostgreSQL at `jdbc:postgresql://localhost:5432/valocase_db`
(user `postgres`). To enable the one-off catalog import:

```
mvnw spring-boot:run -Dspring-boot.run.arguments=--valocase.catalog.import-on-startup=true
```

## Running in production (prod profile)

Activate the `prod` profile and provide the environment variables below:

```
SPRING_PROFILES_ACTIVE=prod java -jar valocase-backend.jar
```

### Required environment variables (prod)

- `DATABASE_URL` — Railway's database URL. It is libpq-style
  (`postgresql://user:pass@host:5432/db`), which is **not** a valid JDBC URL.
  `RailwayDatabaseUrlPostProcessor` rewrites it to `jdbc:postgresql://host:5432/db`
  at startup, so you can paste Railway's value as-is (e.g. `${{Postgres.DATABASE_URL}}`).
- `DATABASE_USERNAME` — database user (e.g. `${{Postgres.PGUSER}}`).
- `DATABASE_PASSWORD` — database password (e.g. `${{Postgres.PGPASSWORD}}`).

The separate `PGHOST` / `PGPORT` / `PGDATABASE` / `PGUSER` / `PGPASSWORD`
variables are no longer needed.

### Optional environment variables

- `SERVER_PORT` — HTTP port (defaults to `8080`; honoured by all profiles)
- `CORS_ALLOWED_ORIGINS` — comma-separated allowed origins, e.g.
  `https://app.valocase.com,https://www.valocase.com`. Defaults to `*` when
  unset. Set this in production to restrict browser/WebGL clients.
- `SPRING_PROFILES_ACTIVE` — set to `prod` for deployment (defaults to `dev`).

Do not commit real production credentials. Provide them through your platform's
secret/environment mechanism.
