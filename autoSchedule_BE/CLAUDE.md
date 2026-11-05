# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

autoschedule is a Spring Boot 4.1.1 web app (Java 17 toolchain, Gradle, group `com.example`). All code lives under `com.example.autoschedule` (entry point `AutoscheduleApplication`): `auth/` (email login, JWT), `controller`/`service`/`repository`/`entity`/`dto`, `ai/` (AI schedule generation). Tables: `users`, `stores`, `store_hours`, `store_demand`, `parts`, `workers`, `worker_availability`, `templates`, `schedules`, `shifts`. They are created by Hibernate from the entities; `src/main/resources/schema.sql` is an old schema that is not run against MySQL (Spring only runs it for embedded DBs).

The frontend is the sibling folder `../autoSchedule_FE` (React, `web/`). With `VITE_USE_MOCK=false` its vite dev server proxies `/api` to localhost:8080, so cookies are same-origin.

Stack: Spring Data JPA (Hibernate) + Spring Web MVC (`spring-boot-starter-webmvc`) + MySQL (`mysql-connector-j`) + Lombok (annotation processor is configured for both main and test).

## Commands

Use the Gradle wrapper (on Windows PowerShell: `.\gradlew.bat`):

- Run app: `.\gradlew.bat bootRun`
- Build: `.\gradlew.bat build`
- All tests: `.\gradlew.bat test`
- Single test class: `.\gradlew.bat test --tests "com.example.autoschedule.service.ShiftValidatorTest"`
- Single test method: `.\gradlew.bat test --tests "com.example.autoschedule.service.ShiftValidatorTest.methodName"`

No lint/formatter is configured.

On Linux/macOS use `./gradlew`. `settings.gradle` applies the foojay toolchain resolver, so Gradle downloads JDK 17 if it is not installed.

Docker: `cp .env.example .env`, fill in `DB_ROOT_PASSWORD`, `DB_PASSWORD` and `JWT_SECRET`, then `docker compose up -d --build`. The stack is MySQL 8.4 (volume `db-data`; the app connects as user `autoschedule`, created only when the volume is empty) plus the app on host port `APP_PORT` (default 8082; 8080 inside the container). To run the frontend too, use `../docker-compose.yml` (next to both repos): it includes this compose file and adds the `web` service (nginx on port `WEB_PORT`, default 3002, proxying `/api` to `app:8080`).

## Runtime configuration

`src/main/resources/application.properties` points at a local MySQL: `jdbc:mysql://localhost:3306/autoschedule` (timezone `Asia/Seoul`, user `root`). A MySQL instance with an `autoschedule` database must be running, otherwise the app and the `@SpringBootTest` context-load test fail at startup (the test has no DB override or embedded DB).

`spring.jpa.hibernate.ddl-auto=update` means entity classes drive the schema; there are no migration scripts. SQL logging (`show-sql`, `format_sql`, bound values at TRACE) is only on in the `dev` profile (`application-dev.properties`, enable with `SPRING_PROFILES_ACTIVE=dev`), because bound values include password and token hashes.

### Local DB setup

The project was renamed from MoneyBug to autoschedule, and the DB from `moneybug` to `autoschedule`. DB contents are not in git (only `schema.sql`, which is stale relative to the entities; JPA is the source of truth). On a fresh machine, create the DB once, and the tables are created on first start:

`CREATE DATABASE IF NOT EXISTS autoschedule DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`

Data in the old `moneybug` DB is not migrated automatically. To carry an account over without its stores/staff, copy just the `users` row (the table is `users`, keyed by `email`):

`CREATE TABLE IF NOT EXISTS autoschedule.users LIKE moneybug.users; INSERT INTO autoschedule.users SELECT * FROM moneybug.users WHERE email LIKE 'demo2%';`

The default password in `application.properties` (`1234`) is a placeholder; the real root password is passed via `SPRING_DATASOURCE_PASSWORD`. The start script `~/run-moneybug.ps1` (name unchanged) prompts for that password and an optional Gemini key, sets `JWT_SECRET`, then runs `.\gradlew.bat bootRun`. Run it in its own PowerShell window. Built jars are now named `autoschedule-0.0.1-SNAPSHOT.jar`.

## Auth architecture

Email + password login. `POST /api/auth/signup` and `/login` return `{id, name, email}` and set two HttpOnly cookies (`SameSite=Lax`): `access_token` (JWT, `jwt.access-ttl-seconds`) and `refresh_token` (opaque, only its SHA-256 is stored in `RefreshToken`, rotated on `POST /api/auth/refresh`). `POST /api/auth/logout` revokes the refresh token and clears both cookies. Signup logs the user in immediately.
- `auth/jwt/JwtAuthenticationFilter` reads the JWT from the `access_token` cookie, or from an `Authorization: Bearer` header (for curl/API tools), and sets the user id (`Long`) as the principal. Controllers take it with `@AuthenticationPrincipal Long userId`.
- Passwords are stored as BCrypt hashes (`users.password_hash`). Auth failures are `AuthException` (401); everything else uses `ApiException`.
- Error bodies are plain Korean text (the frontend shows `res.text()` as-is), see `exception/ApiExceptionHandler` and `ValidationExceptionHandler`.

Required env vars: `JWT_SECRET` (>= 32 bytes; the app won't start without it) and `SPRING_DATASOURCE_PASSWORD` if your MySQL password is not the one in `application.properties`. Optional: `COOKIE_SECURE=true` behind HTTPS, AI settings (next section), `CORS_ALLOWED_ORIGINS` (comma separated) when the frontend runs on another origin of the same site (cookies are `SameSite=Lax`). `GET /actuator/health` is public (status only) and is used by the compose healthcheck. `@SpringBootTest` tests need `JWT_SECRET` too. jjwt uses the `orgjson` serializer on purpose (Boot 4 ships Jackson 3).

## AI generation

`POST /api/schedules/generate` runs `ai/AiSchedulePipeline` (prompt, validation, one correction round, DRAFT save) through a `ScheduleAiClient`, picked by `AI_PROVIDER`:
- `ollama` (default): `ai/OllamaClient` calls an Ollama-compatible `/api/chat` at `LLM_BASE_URL` (default `https://ai.ruraeru.xyz`) with model `LLM_MODEL` (default `qwen3.5:9b`) and `Authorization: Bearer LLM_API_KEY`. It streams (NDJSON) so proxies don't cut idle connections, and converts the Gemini-style response schema (`AiPromptBuilder.responseSchema()`) to JSON Schema for `format`. `LLM_NUM_CTX` (default 16384), `LLM_TIMEOUT_SECONDS` (default 300). One run with qwen 9b took about 3.5 minutes.
- `gemini`: `ai/GeminiClient`, needs `GEMINI_API_KEY`.

## Store / staff API (frontend shape)

`GET/PUT /api/stores/me` exchange the frontend `Store` as one object (hours, parts, demand included); `GET` creates a default store for a new user. `GET/POST /api/staff`, `PUT/DELETE /api/staff/{id}` exchange the frontend `Staff`. The service unpacks them into `stores`/`store_hours`/`parts`/`store_demand` and `workers`/`worker_availability`. Weekday arrays are 0=Mon..6=Sun.
- Store size: `sizeMode` (`AUTO`|`UNDER_5`|`OVER_5`, column `size_mode`, null = AUTO) is the setting; `size` (column `scale`) is the applied value. AUTO means 5+ registered staff = `OVER_5` (same rule as the frontend `sizeOf`), recomputed on store save and on staff create/delete.
- Parts carry `required` (column `required_count`, null = 1). The frontend sends no `demand`; when it is missing, `store_demand` rows are derived from the parts (part time range, every day, `required`).
- Deletes: the Hibernate FKs have no ON DELETE action, so the services clean up first. Deleting a part sets `shifts.part_id` to null and deletes its `worker_availability` rows; deleting a staff member deletes their shifts and leaves.
- `POST /api/schedules` with `prefillFixed` fills staff fixed hours and part-timer fixed parts (same as the frontend `fixedShifts`). `DELETE /api/schedules/{id}` deletes a schedule, confirmed or not. Schedule and template lists are empty (not 404) before the store exists.

Times: end times beyond 24:00 are sent as `26:00` (02:00 next day), see `util/TimeCodec`. The DB stores clock time (`02:00`); an end time that is earlier than or equal to the start means the next day. Part `tone` has no column, so it is derived from the start-time order. Schedule logic (`service/Minutes`, `dto/TimeText`) uses the same rule: shift ends past midnight are sent as `26:00`, stored as `02:00`, and handled as minutes >= 1440. `ShiftValidator` checks overlaps across days, and night premium counts the hours after midnight.

The comments in `application.properties` are Korean and appear mojibake'd (`?`) in the file; preserve the file's encoding when editing it.
