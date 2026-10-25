# AI Activity Log

**Tools:** Claude Code (Claude Opus 5.5) for implementation: inspecting the repo, planning, writing code and tests, running checks and browser testing. Claude (chat) for writing and refining the Claude Code prompt, repository setup, and step-by-step deployment guidance. Every task was approved and reviewed by me before and after it ran.

## Pre-task: build setup (requested before Task 1)
- **Goal:** fix `pom.xml` and get the project ready for the tasks.
- **Findings:** the generated pom already resolved and compiled; the empty `<url/>`, `<licenses>`, `<developers>`, `<scm>` tags are intentional Initializr overrides (see HELP.md) and were kept.
- **Changes:** `backend/pom.xml` – filled `<name>`/`<description>`; added test-scoped `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql` (versions managed by Boot 4.1.2 → Testcontainers 2.0.5; approved). Root `.gitignore` with `.DS_Store`.
- **Checks:** `./mvnw compile`, `test-compile`, `package -DskipTests` → all succeeded; `dependency:tree` showed Testcontainers 2.0.5.

## Task 1: Inspect repository and propose plan
- Read-only inspection; plan approved with one change: host slots must not overlap, using a **half-open** range `[start, end)` so back-to-back slots are allowed.
- Noted: `frontend/` does not exist yet (to be scaffolded in Task 4 after showing node version, command and Vite/React versions).

## Task 2: Foundation (local Postgres, env config, Flyway schema, entities)
- **Decisions**
  - Times stored as `TIMESTAMPTZ` and mapped to `java.time.Instant`; the frontend will send ISO instants with offset.
  - Separate `booking` table with `UNIQUE (slot_id)` → the database guarantees at most one booking per slot, even under concurrent requests (only one INSERT can commit; the loser gets a unique violation, to be mapped to HTTP 409 in Task 3).
  - Slot overlap prevented with `EXCLUDE USING gist (tstzrange(start_at, end_at, '[)') WITH &&)` – no extension needed; half-open range allows back-to-back slots.
  - Check constraints: duration 5–480 minutes; `end_at = start_at + duration`.
  - `ddl-auto=validate` (Flyway owns the schema), `open-in-view=false`.
  - All config via env vars with local defaults: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`, `PORT`.
  - Tests use a real PostgreSQL 17 via Testcontainers `@ServiceConnection`.
- **Files created/changed**
  - `docker-compose.yml`, `.env.example`, `.gitignore` (added `.env`)
  - `backend/.env.example`
  - `backend/src/main/resources/application.properties`
  - `backend/src/main/resources/db/migration/V1__init.sql`
  - `backend/src/main/java/com/angela/booking/slot/Slot.java`
  - `backend/src/main/java/com/angela/booking/booking/Booking.java`
  - `backend/src/test/java/com/angela/booking/TestcontainersConfiguration.java`
  - `backend/src/test/java/com/angela/booking/BookingApplicationTests.java`
- **Commands/tools:** `./mvnw test`, `POSTGRES_PORT=5433 docker compose up -d`, `java -jar` with `DB_URL=...:5433`, `docker exec ... psql`.
- **Checks and actual results**
  - `./mvnw test` → 1 test, 0 failures; Flyway applied V1 on Testcontainers Postgres; Hibernate schema validation passed. BUILD SUCCESS.
  - App started against docker-compose Postgres (port 5433): Flyway applied V1, Tomcat started.
  - SQL checks (in a rolled-back transaction): back-to-back slot accepted; overlapping slot rejected by `slot_no_overlap`; 1-minute duration rejected by `slot_duration_range`; mismatched end rejected by `slot_end_matches_duration`; second booking for the same slot rejected by `booking_slot_unique`. 0 rows remained afterwards.
- **Issues / assumptions**
  - Port 5432 on this machine is used by another project's container (`emt-db-1`). After Task 2 (approved), the local default port was switched to **5433** in `docker-compose.yml`, `.env.example`, `backend/.env.example` and `application.properties`.

## Task 3: Backend API, validation, error handling, conflict-safe booking, tests
- **Endpoints**
  - `POST /api/slots` `{startAt, durationMinutes}` → 201 (+ `Location`); 400 validation; 409 overlap.
  - `GET /api/slots` → upcoming available slots, sorted; `?includeBooked=true` → all upcoming slots with a `booked` flag (host view).
  - `POST /api/slots/{id}/booking` `{name, email}` → 201; 400 validation / bad id; 404 unknown slot; 409 already booked or already started.
- **Decisions**
  - Layered: controller → service → repository, with record DTOs; entities never leave the service layer.
  - Errors use RFC 9457 `ProblemDetail` (`title`, `status`, `detail`, `instance`, plus `errors[{field, message}]` for validation) via a `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, so framework errors (malformed JSON, bad path variables) use the same format. Unexpected errors return a generic 500 without internals.
  - Booking: a fast `existsBySlotId` check gives a friendly 409 in the normal case; the `UNIQUE(slot_id)` constraint is the real guarantee for races. `DataIntegrityViolationException` from the insert is translated to 409. Slot creation works the same way with the overlap query + exclusion constraint.
  - Validation rules: start time required and in the future; duration 5–480 minutes; name required, ≤100 chars, trimmed; email required, must contain `@` and a dot in the domain, ≤254 chars.
  - Booker name/email are **not** exposed by `GET /api/slots`, because the API has no authentication.
  - CORS restricted to `/api/**`, methods GET/POST, origins from `CORS_ALLOWED_ORIGINS`.
- **Files created/changed**
  - `common/`: `GlobalExceptionHandler`, `NotFoundException`, `ConflictException`, `CorsConfig`
  - `slot/`: `SlotRepository`, `SlotService`, `SlotController`, `CreateSlotRequest`, `SlotResponse`
  - `booking/`: `BookingRepository`, `BookingService`, `BookingController`, `BookingRequest`, `BookingResponse`
  - Tests: `IntegrationTest` (shared base: one context, one container, tables emptied before each test), `BookingApplicationTests`, `slot/SlotApiTests`, `booking/BookingApiTests`, `booking/BookingConcurrencyTests`
- **Commands/tools:** `./mvnw test`, `./mvnw test -Dtest=BookingConcurrencyTests` (×5), `java -jar` + `curl` against docker-compose Postgres, `psql` cleanup of the rows created by the manual check.
- **Checks and actual results**
  - `./mvnw test` → **21 tests, 0 failures, 0 errors** (SlotApiTests 11, BookingApiTests 7, BookingConcurrencyTests 2, BookingApplicationTests 1). BUILD SUCCESS.
  - Concurrency test (10 simultaneous HTTP bookings of one slot) → exactly one 201, the rest 409, 1 row in `booking`. In the logged run, 7 of the 9 losing requests passed the application check and were stopped by `booking_slot_unique`, demonstrating the race the constraint protects against. Re-run 5 times: all passed.
  - Separate test inserts two bookings directly through the repository (bypassing the service) → DB throws `DataIntegrityViolationException`.
  - Manual curl against the running app: create 201, overlap 409, invalid 400 with field errors, list 200, book 201, rebook 409, unknown slot 404, booked slot gone from list and shown as `booked: true` with `includeBooked`. CORS preflight: `http://localhost:5173` allowed, other origin 403.
- **Issues / assumptions**
  - Host endpoints are unauthenticated (out of scope; documented in the README).
  - No cancel/delete endpoints.
  - "Upcoming" means start time after now; a slot that has started is neither listed nor bookable.
  - The losing concurrent requests log a Hibernate WARN for the unique violation; this is expected noise.

## Task 3 follow-up: package-by-layer structure and leaner tests (requested)
- **Change:** moved from feature packages (`slot/`, `booking/`, `common/`) to layer packages under `com.angela.booking`: `config/`, `controller/`, `dto/`, `entity/`, `exception/`, `repository/`, `service/`. No behaviour changes; DTO `from(...)` factories made public, duration limits inlined in `CreateSlotRequest`.
- **Tests trimmed** from 21 cases in 5 files to **10 cases in 3 files**: `IntegrationTest` (base class, now also holds the Testcontainers config), `SlotApiTests` (4: create, validation, overlap/back-to-back, listing), `BookingApiTests` (6: book + removed from list, second booking 409, unknown slot 404, invalid input 400, 10-thread concurrency, DB-constraint-only). Removed `TestcontainersConfiguration`, `BookingApplicationTests` (context load is covered by every test) and `BookingConcurrencyTests` (merged).
- **Check:** `./mvnw clean test` → 10 tests, 0 failures, 0 errors. BUILD SUCCESS.

## Pre-Task 4: IntelliJ "jakarta" errors
- `./mvnw clean compile` succeeds with no errors; `dependency:tree` shows `jakarta.persistence-api` 3.2.0 and `jakarta.validation-api` 3.1.1 on the classpath. The red imports are an IntelliJ state issue (Maven project not reloaded after pom changes), not a pom problem. No files changed.

## Task 4: Frontend
- **Scaffold:** `npm create vite@9.3.1 frontend -- --template react-ts` (Node v20.20.2). Installed: react/react-dom 19.3.1, vite 8.3.5, @vitejs/plugin-react 6.1.2, typescript 6.0.3, oxlint 1.89.0.
- **npm issue:** plain `npm install` crashed inside npm 10.9.9 (`Cannot read properties of null (reading 'edgesOut')` while resolving @vitejs/plugin-react's optional peer dependencies). Installed with `--legacy-peer-deps` and added `frontend/.npmrc` (`legacy-peer-deps=true`) so `npm install` behaves the same locally and on Vercel. 0 vulnerabilities reported.
- **Decisions**
  - No router or UI library: a header with Book / Host buttons (`aria-current`) switches between two views.
  - `api.ts`: typed fetch client, base URL from `VITE_API_BASE_URL` (default `http://localhost:8080`), `ApiError` carrying status + server field errors; network failures produce a friendly message.
  - `useSlots` hook: loading / error / reload; sets state only in fetch callbacks (satisfies oxlint `set-state-in-effect`) and ignores responses after unmount.
  - Host enters date + start time in local time; converted to an ISO instant (`toISOString()`), time zone shown as a hint. All times displayed in the viewer's locale/time zone, grouped by day.
  - Book view: select a slot → name/email form; client validation mirrors the server; 409/404 shows the server message, clears the selection and refreshes the list; success shows a confirmation.
  - Accessibility: labelled inputs, `aria-invalid` + `aria-describedby` for errors, `role="alert"`/`"status"` messages, `aria-pressed` on slot buttons, focus moves to the name field when a slot is selected, visible focus ring. Responsive two-column layout ≥800px, single column below.
- **Files:** `frontend/` scaffold; app code in `src/api.ts`, `src/format.ts`, `src/useSlots.ts`, `src/App.tsx`, `src/BookingView.tsx`, `src/HostView.tsx`, `src/styles.css`, `src/main.tsx` (stylesheet import); `frontend/.env.example`, `frontend/.npmrc`; `index.html` title. Removed Vite demo files (`src/assets/`, `src/App.css`, `src/index.css`, `public/icons.svg`, template `README.md`). Note: reading `src/index.css` was denied in-session, so styles were written to the new `src/styles.css` and the demo `index.css` was deleted.
- **Checks and actual results**
  - `npm run build` (tsc + vite) → success. `npm run lint` (oxlint) → 0 warnings, 0 errors.
  - Vite dev server served the app (`<title>Book a Slot</title>`, `/src/main.tsx` 200).
  - API flow replayed with `Origin: http://localhost:5173` against the running backend + docker-compose Postgres: host create 201 with CORS header (local 10:00 → `09:00Z` conversion as the UI does it), visitor list shows the slot, two simultaneous bookings → one 201 / one 409, slot gone from the visitor list and `booked: true` in the host list. Test rows deleted afterwards.
  - **Not verified in this task:** the UI itself in a browser (Claude in Chrome extension was not connected). Covered in Task 5.

## IntelliJ Maven import error ("Unable to create injector ... ArtifactHandlerManager")
- **Cause (from `~/Library/Logs/JetBrains/IntelliJIdea2025.2/idea.log`):** IntelliJ 2025.2.5 imports the project with the Maven wrapper's distribution (Maven 3.10.0, `.mvn/wrapper/maven-wrapper.properties`). Its Maven importer only supports up to the Maven 3.9 line it bundles (3.9.9), so the importer's dependency-injection setup fails. The pom itself is valid; `./mvnw` from the terminal works.
- **Fix (IDE setting, no project change):** Settings → Build, Execution, Deployment → Build Tools → Maven → *Maven home path* = **Bundled (Maven 3)**, then Reload All Maven Projects. Alternative (needs approval, changes the build tool version): pin the wrapper to Maven 3.9.x.

## Task 5 (part 1): Browser testing via Claude in Chrome
- **Tested in Chrome (desktop ~1470px and 390px via iframe; Chrome would not resize below 960px):**
  - Book view empty state ("No available slots right now…").
  - Host: empty submit → date/time/duration errors; duration 2 → range error; today 00:05 → "Start time must be in the future."; tomorrow 10:00/60 → success message + slot listed; 10:30/30 → server 409 "This slot overlaps an existing slot." shown; back-to-back 11:00/30 accepted; 14:00/45 accepted.
  - Book: selecting a slot highlights it (`aria-pressed`) and moves focus to Name; empty submit → name/email errors; `ann@localhost` → email error; valid → "You're booked!" confirmation; slot gone from list afterwards.
  - Conflict in UI: selected 11:00, another visitor booked it via curl, then submitted → "Sorry, this slot has already been booked. Please choose another slot." and the list refreshed without it.
  - Host list shows Booked/Available badges correctly.
  - 390px: single column, no horizontal scroll (scrollWidth = viewport), form reachable after selecting a slot.
  - Backend stopped → "Could not reach the server…" with Try again; after restart, Try again reloads the list.
  - No console errors.
- **Fix made:** slot buttons now have an `aria-label` with day, time range and duration (previously the accessible name was only the time range, without the date). `npm run build` and `oxlint` pass after the change; verified the new accessible name in the page's accessibility tree.
- **Not tested:** real screen reader (VoiceOver), Safari, Firefox.
- Test rows deleted from the local database afterwards.

## Maven wrapper pinned to 3.9.9 (approved)
- `backend/.mvn/wrapper/maven-wrapper.properties`: distribution 3.10.0 → **3.9.9**, the version bundled with IntelliJ 2025.2.5, so IntelliJ's Maven import works with the wrapper. Latest 3.9.x is 3.9.16; 3.9.9 was chosen as the version known to be compatible with this IntelliJ.
- Checks: `./mvnw -v` → Apache Maven 3.9.9; `./mvnw clean test` → 10 tests, 0 failures.

## Task 6: README and handoff
- **Created:** `README.md` (features, stack, double-booking mechanism and trade-offs, structure, prerequisites, local run, env vars, database, API, tests, limitations, improvements, AI usage).
- **Checks run and actual results**
  - `docker compose up -d` → `booking-postgres` running, healthy, port 5433.
  - `PORT=8082 ./mvnw spring-boot:run` → `GET /api/slots` 200 (stopped afterwards).
  - `./mvnw clean test` → 10 tests, 0 failures.
  - `npm run lint` → no findings; `npm run build` → success.
  - `git status`: only source/config files untracked; `node_modules`, `target`, `dist` and `.env` files are ignored.

## Task 7: Deployment preparation
- **Created:** `backend/Dockerfile` (multi-stage: Temurin 21 JDK builds with the Maven wrapper, tests skipped because they need Docker; Temurin 21 JRE runtime, non-root `app` user, `-XX:MaxRAMPercentage=75` for small instances, listens on `$PORT`), `backend/.dockerignore`.
- **README:** deployment section (Neon/Supabase JDBC URL conversion, Render Docker service, Vercel project, CORS hookup, preview-URL and cold-start notes).
- **Decisions:** no `render.yaml`/`vercel.json` (dashboard setup is short and keeps the file count down; Vercel auto-detects Vite and the app has no client-side routes). Neon direct (non-pooled) connection recommended so Flyway runs on a normal session; Supabase via Session pooler because its direct host is IPv6-only.
- **Checks and actual results**
  - `docker build -t booking-backend backend` → success.
  - Container run like Render (`PORT=10000`, DB via `host.docker.internal:5433`, `CORS_ALLOWED_ORIGINS` set to an example production origin): started, Flyway "up to date", `GET /api/slots` 200, runs as user `app`; preflight from the configured origin 200 with `Access-Control-Allow-Origin`, from `http://localhost:5173` 403. Container removed afterwards.
  - Vercel simulation in a clean copy: `npm ci` → success, 0 vulnerabilities; production build with an example `VITE_API_BASE_URL` → success, URL present in the bundle, no `localhost:8080` fallback left.
  - Real deployment was done afterwards in Task 12.
- Note: the local database contained a slot (2026-10-27 20:20 UTC) not created by Claude Code; left untouched.

## Task 8: AI booking confirmation (Gemini)
- **Decisions (approved):** generated inside `POST /api/slots/{id}/booking` after the booking transaction commits (controller calls `BookingService` then `ConfirmationMessageService`), not a separate endpoint: one LLM call per booking (free quota can't be drained), no endpoint that could expose other bookings by sequential id.
- **Model:** checked ai.google.dev on 2026-10-25: `gemini-3.5-flash-lite` (stable, free tier, recommended for new projects) as default; configurable via `GEMINI_MODEL`. Classic `v1beta/models/{model}:generateContent` REST endpoint with the `x-goog-api-key` header.
- **Reliability:** 5 s connect/read timeout; template fallback when unconfigured, failing, empty or longer than 500 chars. Booking never fails because of the LLM.
- **Privacy:** only first name + slot date/time (worded in the visitor's time zone, sent optionally by the frontend; invalid/missing → UTC) go to Gemini; no surname or email. Free-tier data may be used by Google (documented).
- **Secrets:** `spring.config.import=optional:file:.env[.properties]` lets the key live in git-ignored `backend/.env` locally; real env vars win. `.dockerignore` keeps `.env` out of the image.
- **Bug found and fixed:** first start failed with "No qualifying bean of type RestClient$Builder": in Spring Boot 4 RestClient auto-configuration lives in `spring-boot-starter-restclient`, which was added to the pom. The tests had passed because they replaced `GeminiClient` with a mock, so the real bean was never created; they now use `@MockitoSpyBean` on the real bean (wiring is exercised) with `app.gemini.api-key=` forced empty so tests never call Gemini, even if `backend/.env` has a key.
- **Files:** `client/GeminiClient.java` (new), `service/ConfirmationMessageService.java` (new), `controller/BookingController.java`, `dto/BookingRequest.java` (+`timeZone`), `dto/BookingResponse.java` (+`durationMinutes`, `confirmationMessage`), `pom.xml` (+restclient starter), `application.properties`, `backend/.env.example`, tests (`IntegrationTest`, `BookingApiTests` +2), frontend `api.ts`, `BookingView.tsx`, `styles.css`, README.
- **Checks and actual results**
  - `./mvnw test` → 12 tests, 0 failures (new: LLM message returned and prompt contains first name + local time but no surname/email; LLM failure → 201 with exact fallback text and booking saved).
  - Backend started; booking without a key → 201 with template "Thanks, Ann! Your appointment on Wednesday 28 October 2026 at 08:00 is confirmed. See you then!" (Europe/Skopje); unknown zone `Mars/Base` → same in UTC with "(UTC)". Test rows deleted.
  - `npm run build` and `oxlint` → clean.
  - **Live check with my API key (2026-10-25):** first live booking returned the *fallback* message; the log showed the Gemini response could not be parsed. A new `GeminiClientTests` (realistic Gemini JSON through the real `RestClient`/Jackson setup via `MockRestServiceServer`, no network) reproduced it: `Cannot map null into type boolean` — Jackson 3 enables `FAIL_ON_NULL_FOR_PRIMITIVES` by default and Gemini omits `thought` on normal parts. Fixed by making it `Boolean`. Also: timeouts moved to Boot's `spring.http.clients.connect-timeout/read-timeout` (5s) so the client uses the auto-configured builder; the fallback warning now logs the root cause.
  - After the fix: `./mvnw test` → 13 tests, 0 failures. Live booking → 201 with a Gemini-generated confirmation and no fallback warning in the log. Test slot deleted. The key was never read or printed (only the file's existence and git-ignore status were checked).
  - Browser (Chrome): booked a test slot → confirmation screen showed the Gemini-generated text. Test slot deleted.

## Task 9: Google Calendar clash check
- **Decisions (approved):** service account + calendar shared as "See only free/busy" (no OAuth flow, suits a single host); **FreeBusy API** with the narrow `calendar.freebusy` scope (only busy ranges, never event details); **fail open** on Google errors (warning logged). Visitors don't see clashing slots; host list gets `calendarConflict: true` ("Calendar conflict" badge); booking a clashing slot → 409 "Sorry, this time is no longer available." Same half-open overlap rule as slots (FreeBusy `end` is exclusive).
- **Why not DB-level:** the calendar is external and can change any time, so this is an application-level, best-effort check (at list time and again at booking time); double-booking protection stays in the DB.
- **Transactions:** `SlotService.listUpcoming` and `BookingService.book` are no longer wrapped in one transaction, so no DB connection is held while waiting for Google; each repository call is transactional on its own and the `UNIQUE(slot_id)` constraint still decides races (concurrency test still passes).
- **Config:** `GOOGLE_CALENDAR_ID`, `GOOGLE_SERVICE_ACCOUNT_FILE` (path; on Render a Secret File). Disabled when either is empty; if set but unreadable, startup fails loudly. One FreeBusy call per list request covering all slots; global 5 s HTTP timeouts apply.
- **Files:** `pom.xml` (+`google-auth-library-oauth2-http` 1.54.0), `client/GoogleCalendarClient.java` (new), `service/CalendarAvailabilityService.java` (new), `service/SlotService.java`, `service/BookingService.java`, `dto/SlotResponse.java` (+`calendarConflict`), `application.properties`, `backend/.env.example`, tests (`IntegrationTest` spy + forced-empty config, `SlotApiTests` +2, `BookingApiTests` +1, `client/ExternalApiClientTests` = former `GeminiClientTests` + FreeBusy mapping test incl. `notFound` error), frontend `api.ts`, `HostView.tsx` (badge), `styles.css`, README.
- **Checks and actual results**
  - `./mvnw test` → **17 tests, 0 failures** (calendar: clash hidden for visitors / marked for host while a back-to-back slot stays visible; Google down → slots still listed; booking a clash → 409 and no row; FreeBusy request JSON + Bearer token + response parsing; `notFound` error raised).
  - `npm run build` + `oxlint` → clean.
  - Docker: first `docker build -q` attempt reported failure (output suppressed, cause not captured — likely transient download); the next two builds succeeded; container started and `GET /api/slots` → 200, no errors in logs.
  - Backend running without calendar config → `calendarConflict: false` everywhere (feature off), existing behaviour unchanged.
  - **Live check (2026-10-25)** against my real calendar (test event 26 Oct 14:30–15:30 CET): slot 14:00–15:00 → `calendarConflict: true` for host, hidden for visitors, booking → 409 with no row saved; slot 15:30–16:00 (starts when the event ends) → visible and free. No warnings in the log. Test slots deleted afterwards.
  - Known quirk: the `POST /api/slots` response itself always has `calendarConflict: false`; the host list (reloaded right after creating) shows the badge.

## Task 10: UI polish, centred layout, welcome section (requested)
- **Goal:** prettier UI, content centred, short welcome/instructions on the home (Book) page. Generic wording, CSS only (no new libraries or fonts).
- **Changes:** centred header with a pill-style Book/Host toggle; content column 960px; soft gradient background, rounded cards with light shadows, hover lift on slot buttons, pill buttons, dashed empty states, `prefers-reduced-motion` respected. Book page: "Book a time with me" intro + 3 numbered steps (stacked on phones, 3 across from 640px). Host page: "Manage your availability" heading + one hint line. Confirmation card centred.
- **Files:** `frontend/src/styles.css`, `BookingView.tsx`, `HostView.tsx`.
- **Checks:** `npm run build` OK; `npm run lint` (oxlint) clean. Chrome: desktop screenshots of Book and Host views; real booking end to end (slot → name/email → confirmation with Gemini message); Host list shows Booked/Available/Calendar conflict badges; 390px iframe → no horizontal scroll (scrollWidth = clientWidth = 388), steps stack; no console errors. Test slots #22/#23 (and their booking) deleted afterwards.
- **Not verified:** the keyboard focus ring in this pass (focus styles unchanged from Task 5).

## Task 11: Nav bar, equal-height cards, booking summary (requested)
- **Goal:** proper nav bar and smoother flow; side-by-side cards the same height (also when empty); show clearly which slot was booked.
- **Changes:** sticky top nav bar (calendar icon + "Book a Slot" left, Book/Host toggle right); `.columns` stretch so paired cards match height, empty states fill and centre in the card. Confirmation screen: check icon, the LLM message, then a labelled summary (Date, Time, Duration, Name, Email). After "Book another slot", a green "Your booking" bar stays above the slot list with "View details" to reopen the summary.
- **Decision:** the latest booking is kept in React state only (this tab, lost on reload) since there are no accounts; noted as a limitation. Added `durationMinutes` to the frontend `Booking` type (the API already returned it).
- **Files:** `frontend/src/App.tsx`, `BookingView.tsx`, `api.ts`, `styles.css`.
- **Checks:** `npm run build` OK; `npm run lint` clean. Chrome: equal-height cards with empty details card; real booking → confirmation with summary; "Book another slot" → "Your booking" bar; "View details" reopens the summary; Host view with the nav bar; 390px iframe → no horizontal scroll, nav bar fits on one line; no console errors. Test slot #27 and its booking deleted.
- **Not verified:** keyboard focus ring (styles unchanged).

## Task 12: Deployment (done by me, guided by Claude in chat)
- **Repository:** committed and pushed to GitHub (`angellce/booking-app`, public). Before pushing, checked that no `.env`, `.DS_Store` or key files are tracked and that no API keys or private keys appear in tracked files.
- **Database:** Neon PostgreSQL (AWS us-east-2), direct (non-pooled) connection. JDBC URL uses `sslmode=require`; Neon's `channel_binding` parameter was left out of the JDBC URL.
- **Backend:** Render web service (Docker, root directory `backend`, region Ohio to sit next to the database, free plan), health check path `/api/slots`. Env vars: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`. Deploy succeeded; Flyway created the schema on first start; `GET https://booking-backend-2h59.onrender.com/api/slots` returned `[]`.
- **Frontend:** Vercel (root directory `frontend`, Vite preset), `VITE_API_BASE_URL` set to the Render URL. `CORS_ALLOWED_ORIGINS` on Render then set to the Vercel production URL and redeployed.
- **Live checks (manual, in the browser):** created a slot in Host; booked it in Book and got a confirmation; the slot disappeared from Book and showed as Booked in Host; an overlapping slot was rejected with an error; checked the layout on my phone.
- **Live config:** Gemini and Google Calendar are not configured on the live deployment, so the live app uses the template confirmation message and no calendar check. Both features were verified locally (Tasks 8 and 9).

---

# Handoff summary

## Completed and verified
- **Live app:** https://booking-app-gold-gamma.vercel.app (frontend on Vercel, backend on Render, database on Neon). Core flow checked manually on the live site.
- Backend API (create/list/book), server-side validation, consistent JSON errors, CORS from env, Flyway schema with DB-level guarantees (`UNIQUE(slot_id)`, overlap exclusion constraint, check constraints).
- 17 backend tests against real PostgreSQL, including 10 concurrent bookings → exactly one succeeds, the AI confirmation and the calendar check.
- Extra 1 (AI): Gemini confirmation message verified locally (API and browser).
- Extra 2 (Calendar): implemented, tested with stubs and verified locally against my real calendar.
- React frontend (Book and Host views) built, linted and tested manually in Chrome, including the conflict case, validation, error/empty states and 390px layout.
- Local setup (docker-compose, env defaults, `.env.example` files), README, this log.
- Repository pushed to GitHub with the live link in the README.

## Exact commands
```bash
# from the repository root
docker compose up -d                          # PostgreSQL on port 5433
cd backend && ./mvnw spring-boot:run          # API on http://localhost:8080
cd frontend && npm install && npm run dev     # UI on http://localhost:5173

cd backend && ./mvnw test                     # backend tests (Docker required)
cd frontend && npm run lint && npm run build  # frontend checks
```

## Not verified
- Real screen reader (VoiceOver), Safari and Firefox.
- Gemini and Calendar on the live deployment (not configured there; verified locally only).
- No automated frontend tests.
