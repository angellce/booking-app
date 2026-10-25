# Book a Slot

A small scheduling app: a single host publishes appointment slots, and visitors book one with their name and email, with no accounts. A booked slot disappears from the available list, and the backend guarantees a slot can never be booked twice, even when requests arrive at the same moment.

- **Live app:** https://booking-app-gold-gamma.vercel.app
- **Author:** Angela Cvetkovska

## Features

- **Host view:** create a slot with a date, start time and duration (5–480 minutes); see upcoming slots marked *Available* or *Booked*.
- **Book view:** browse upcoming available slots grouped by day, pick one, enter name and email, get a confirmation.
- **AI confirmation message:** after booking, Gemini writes a short, friendly confirmation (optional; falls back to a template).
- **Google Calendar clash check:** slots that clash with events in the host's Google Calendar are hidden from visitors and marked *Calendar conflict* for the host (optional).
- Validation in the browser and on the server, with clear loading, empty, error and success states.
- Overlapping slots are rejected; back-to-back slots (e.g. 10:00–11:00 and 11:00–11:30) are allowed.
- Times are stored in UTC and shown in each user's own time zone.
- Responsive layout (two columns on wide screens, one on phones), labelled form fields, keyboard and screen-reader friendly.

## Tech stack

| Part | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite 8 (no UI or router library) |
| Backend | Spring Boot 4.1 (Web MVC, Data JPA, Validation), Java 21, Maven |
| Database | PostgreSQL 17, schema managed with Flyway |
| AI | Gemini API (Google AI Studio free tier), called with Spring's `RestClient` |
| Calendar | Google Calendar FreeBusy API, service-account auth (`google-auth-library-oauth2-http`) |
| Tests | JUnit 5, MockMvc, Testcontainers (real PostgreSQL) |

## How double bookings are prevented

Bookings live in their own `booking` table with a **`UNIQUE (slot_id)` constraint**, so PostgreSQL itself allows at most one booking per slot.

1. `BookingService` first checks whether the slot already has a booking, which gives a friendly `409 Conflict` in the normal case.
2. Two requests arriving at the same moment can both pass that check. Both then try to `INSERT` a booking; PostgreSQL lets exactly one commit and the other fails with a unique-constraint violation, which the service turns into `409 Conflict`.

Because the database enforces the rule, it holds across threads and across multiple backend instances. A plain "check, then insert" in application code has a race window; the constraint closes it.

**Trade-off:** the code relies on catching a database exception instead of a clean boolean result. The alternative, an atomic `UPDATE slot SET booked = true WHERE id = ? AND booked = false`, returns a clean "rows affected" count but keeps booking data on the slot row. A separate table with a unique key keeps the model simple and is the standard pattern.

Slot overlaps are enforced the same way, with an exclusion constraint on a half-open time range:

```sql
EXCLUDE USING gist (tstzrange(start_at, end_at, '[)') WITH &&)
```

`[)` means the end time is not part of the slot, so back-to-back slots don't overlap.

A test fires 10 simultaneous HTTP booking requests at one slot and checks that exactly one gets `201` and the rest get `409`.

## AI confirmation message

When a booking succeeds, the booking endpoint asks Gemini for a 1–2 sentence confirmation and returns it as `confirmationMessage`.

- **The booking never depends on the LLM.** The booking is saved and committed first. The Gemini call has a 5-second timeout. If Gemini is not configured, slow or failing, a fixed template is returned instead (*"Thanks, Ann! Your appointment on … is confirmed. See you then!"*).
- **Minimal data:** only the visitor's first name and the slot's date and time are sent, never the surname or email. The frontend sends the visitor's time zone so the time is worded in their local time.
- **Why inside the booking request, not a separate endpoint:** there's exactly one LLM call per booking, so the free quota can't be drained by repeated calls, and no extra public endpoint can expose other people's bookings.
- **Setup:** create a free API key at [Google AI Studio](https://aistudio.google.com/apikey) and set `GEMINI_API_KEY`. Locally, put it in `backend/.env`, which is git-ignored and loaded automatically (see `backend/.env.example`). The default model is `gemini-3.5-flash-lite` and can be changed with `GEMINI_MODEL`.
- **Note:** on Gemini's free tier, Google may use prompts and responses to improve its products. That's acceptable here because only a first name and a time are sent.

## Google Calendar clash check

If the host connects a Google Calendar, slots that overlap a calendar event are treated as unavailable:

- **Visitors** don't see them. **The host** sees them marked *Calendar conflict*. **Booking** one returns `409` ("Sorry, this time is no longer available"), in case an event was added after the visitor loaded the list.
- **How:** one call to Google's **FreeBusy API** per request covers all listed slots. It returns only busy time ranges, never event titles, attendees or descriptions. The overlap rule is the same half-open `[start, end)` rule as for slots, so an event ending at 10:00 doesn't block a 10:00 slot.
- **Auth:** a Google Cloud **service account**. The host shares their calendar with the service account's email as *"See only free/busy (hide details)"*, and the backend requests only the `calendar.freebusy` scope. This needs no login screen or token storage, which suits a single host.
- **Fails open:** if Google can't be reached or reports an error (e.g. the calendar isn't shared), slots are shown as normal and a warning is logged. The app keeps working, with a small risk of booking during a meeting.
- **Not a database guarantee:** unlike double bookings, a calendar clash is checked in application code, because the calendar lives at Google and can change at any moment. It's best effort, checked when listing and again when booking.
- **Disabled** unless both `GOOGLE_CALENDAR_ID` and `GOOGLE_SERVICE_ACCOUNT_FILE` are set. If they're set but the key file can't be read, the backend refuses to start, so a misconfiguration is noticed immediately.

**Setup**
1. In [Google Cloud Console](https://console.cloud.google.com/), create a project and enable the **Google Calendar API**.
2. Under *IAM & Admin → Service accounts*, create a service account, then *Keys → Add key → JSON*. Store the file **outside the repository**.
3. In Google Calendar, open the calendar's *Settings and sharing → Share with specific people*, add the service account's email, and choose **See only free/busy (hide details)**.
4. Set `GOOGLE_CALENDAR_ID` (shown under *Integrate calendar*; for the main calendar it's your Gmail address) and `GOOGLE_SERVICE_ACCOUNT_FILE` (path to the JSON key), e.g. in `backend/.env`.

## Project structure

```
booking-app/
├── docker-compose.yml        Local PostgreSQL
├── backend/                  Spring Boot API (Dockerfile for Render)
│   └── src/main/java/com/angela/booking/
│       ├── config/           CORS
│       ├── controller/       REST endpoints
│       ├── dto/              Request/response records
│       ├── entity/           JPA entities (Slot, Booking)
│       ├── exception/        Error handling (consistent JSON errors)
│       ├── repository/       Spring Data repositories
│       └── service/          Business rules
│   └── src/main/resources/db/migration/   Flyway SQL migrations
└── frontend/                 React app
    └── src/                  api.ts, views, hooks, styles
```

## Prerequisites

- **Java 21+** (no Maven install needed; the project uses the Maven wrapper `./mvnw`)
- **Node.js 20.19+ or 22.12+** and npm
- **Docker** (for the local database and for the backend tests)

## Running locally

**1. Start PostgreSQL** (from the repository root):

```bash
docker compose up -d
```

This starts PostgreSQL 17 on **port 5433** (database, user and password: `booking`). Port 5433 avoids clashing with another PostgreSQL on 5432. To use a different port, set `POSTGRES_PORT` and `DB_URL`.

**2. Start the backend** (in `backend/`):

```bash
./mvnw spring-boot:run
```

The API runs on http://localhost:8080. Flyway creates the tables on first start.

**3. Start the frontend** (in `frontend/`):

```bash
npm install
npm run dev
```

Open http://localhost:5173.

To stop the database, run `docker compose down`. Add `-v` to also delete its data.

## Environment variables

All variables have local defaults, so none are needed for local development. Examples are in `.env.example`, `backend/.env.example` and `frontend/.env.example`. Never commit real credentials.

**Backend**

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/booking` | JDBC URL (e.g. Neon/Supabase: `jdbc:postgresql://<host>:5432/<db>?sslmode=require`) |
| `DB_USERNAME` | `booking` | Database user |
| `DB_PASSWORD` | `booking` | Database password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated frontend origins allowed to call the API |
| `PORT` | `8080` | HTTP port |
| `GEMINI_API_KEY` | *(empty: AI disabled)* | Google AI Studio API key for AI confirmation messages |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | Gemini model ID |
| `GOOGLE_CALENDAR_ID` | *(empty: calendar check disabled)* | ID of the host's Google Calendar |
| `GOOGLE_SERVICE_ACCOUNT_FILE` | *(empty)* | Path to the service account JSON key |

Locally, backend variables can also be placed in `backend/.env` (git-ignored), which Spring Boot loads at startup. Real environment variables take precedence.

**Frontend** (read at build time by Vite)

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Backend base URL, no trailing slash |

**Docker Compose** (optional, root `.env`): `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` (default `5433`).

## Database

The schema is in `backend/src/main/resources/db/migration/V1__init.sql` and is applied automatically by Flyway at startup. Hibernate only validates that the entities match it (`ddl-auto=validate`).

- `slot`: `start_at`, `end_at` (`timestamptz`), `duration_minutes` (check: 5–480), `end_at` must equal `start_at + duration`, no overlaps (exclusion constraint).
- `booking`: `slot_id` (unique, foreign key), `name`, `email`, `created_at`.

## API

All errors use one JSON shape ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details): `title`, `status`, `detail`, `instance`, plus `errors: [{field, message}]` for validation failures.

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `POST /api/slots` | `{"startAt": "2030-01-15T09:00:00Z", "durationMinutes": 30}` | `201` slot | `400` invalid or in the past, `409` overlap |
| `GET /api/slots` | | `200` upcoming available slots | |
| `GET /api/slots?includeBooked=true` | | `200` all upcoming slots with `booked` flag | |
| `POST /api/slots/{id}/booking` | `{"name": "Ann", "email": "ann@example.com", "timeZone": "Europe/Skopje"}` (`timeZone` optional) | `201` booking with `confirmationMessage` | `400` invalid, `404` unknown slot, `409` already booked or started |

## Tests and checks

**Backend** (Docker must be running; Testcontainers starts a throwaway PostgreSQL):

```bash
cd backend
./mvnw test
```

The tests cover slot creation, validation (past start, duration limits, name/email), overlap and back-to-back slots, the available and host lists, booking (`201`, `404`, `409`), 10 concurrent bookings of one slot (exactly one succeeds), the database constraint on its own, and the AI confirmation (generated message, fallback when Gemini fails, no email sent to the LLM), the calendar check (clashing slots hidden or marked, booking a clash returns `409`, fail-open when Google is down), and the real JSON mapping of both Google clients against realistic responses. Google is stubbed in tests, so no keys or network access are needed.

**Frontend:**

```bash
cd frontend
npm run lint
npm run build
```

There are no automated frontend tests. The UI was tested manually in Chrome (see `AI_LOG.md`).

## Deployment

Database on **Neon** (or Supabase), backend on **Render** (Docker), frontend on **Vercel**. Deploy in this order, because each step needs a URL from the previous one.

**1. Database (Neon)**
1. Create a project and copy the connection details. Use the **direct** connection (host without `-pooler`), so Flyway migrations run on a normal session.
2. Convert the connection string into a JDBC URL and keep the user and password separate. For example, `postgresql://user:pass@ep-xyz.eu-central-1.aws.neon.tech/neondb?sslmode=require` becomes:
   - `DB_URL=jdbc:postgresql://ep-xyz.eu-central-1.aws.neon.tech/neondb?sslmode=require`
   - `DB_USERNAME=user`
   - `DB_PASSWORD=pass`

   *Supabase instead:* use the **Session pooler** connection, because the direct host is IPv6-only. The JDBC URL is `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`, with user `postgres.<project-ref>`.

**2. Backend (Render)**
1. **New → Web Service**, then connect the GitHub repository.
2. **Root Directory:** `backend`. **Language:** Docker. Render builds `backend/Dockerfile`.
3. **Environment variables:** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` from step 1, and `CORS_ALLOWED_ORIGINS=http://localhost:5173` for now. Optionally add `GEMINI_API_KEY` for AI messages. For the calendar check, upload the service account key as a **Secret File** (e.g. `google-sa.json`, available at `/etc/secrets/google-sa.json`) and set `GOOGLE_SERVICE_ACCOUNT_FILE=/etc/secrets/google-sa.json` and `GOOGLE_CALENDAR_ID`. Render sets `PORT` itself.
4. **Health check path:** `/api/slots`.
5. Deploy. Flyway creates the tables on first start. Check that `https://<service>.onrender.com/api/slots` returns `[]`.

**3. Frontend (Vercel)**
1. **Add New → Project**, then import the repository.
2. **Root Directory:** `frontend`. Vercel detects Vite, so the build command is `npm run build` and the output is `dist`.
3. **Environment variable:** `VITE_API_BASE_URL=https://<service>.onrender.com`, with no trailing slash. It is baked in at build time, so changing it requires a redeploy.
4. Deploy.

**4. Connect them**
1. On Render, set `CORS_ALLOWED_ORIGINS` to the Vercel production URL, e.g. `https://<project>.vercel.app`. Several origins can be separated by commas. Saving triggers a redeploy.
2. Open the Vercel URL, create a slot in **Host**, then book it in **Book**.

Notes:
- Vercel preview deployments have different URLs, which are blocked by CORS unless you add them.
- On Render's free plan the backend sleeps after about 15 minutes without traffic, so the first request can take around a minute while it wakes up. The UI shows "Loading slots…" in the meantime.
- To check the image locally: `docker build -t booking-backend backend`, then run it with the same environment variables.

## Known limitations

- **No authentication:** anyone who opens the app can use the Host view. The public API never returns booker names or emails.
- No cancelling, editing or deleting of slots or bookings.
- No email confirmation is sent; the AI confirmation message is only shown on screen.
- The visitor's "Your booking" summary is kept only in the open browser tab; it's gone after a reload (there are no visitor accounts to look bookings up).
- The AI message adds about 1–2 seconds to a booking request when Gemini is configured.
- The calendar check calls Google on every slot list request (no caching), and it's best effort: an event created *after* a booking doesn't cancel that booking.
- Only upcoming slots are shown; a slot that has started can no longer be booked.
- No automated frontend tests.
- On Render's free plan the first request after a period of inactivity is slow (cold start).
- `frontend/.npmrc` sets `legacy-peer-deps=true` to work around an npm 10 crash (`Cannot read properties of null (reading 'edgesOut')`) when resolving `@vitejs/plugin-react`'s optional peer dependencies.

## What could be improved with more time

- Host login, and a host view of who booked each slot.
- Cancel and reschedule flows, with confirmation emails.
- Recurring availability (e.g. "every weekday 9–12 in 30-minute slots").
- Frontend tests (React Testing Library, plus a Playwright end-to-end test).
- Pagination or a date range filter for long slot lists.
- Rate limiting on the public booking endpoint.

## AI tools used

This project was built with **Claude Code** (Anthropic, Claude Opus 5.5) as a pair-programming partner. It inspected the skeletons, proposed the plan, wrote code and tests, ran the checks and drove manual browser testing. Every task was approved and reviewed step by step. The full log of decisions, commands and test results is in [`AI_LOG.md`](AI_LOG.md).
