# Daily AI Updates & Learning Assistant

A mobile-first app for freshers and students: real AI news collected from RSS feeds, a
beginner-friendly five-level learning roadmap, AI tool discovery, a daily learning plan,
bookmarks and reading history. The backend is Spring Boot 3 with PostgreSQL; the client is
React (JavaScript/CSS/Axios) packaged for Android with Capacitor.

The app runs completely without a paid AI service. When an OpenAI-compatible endpoint is
configured, article explanations are upgraded by the model; otherwise a truthful offline
explanation is generated only from the feed text.

## Architecture

```
React + Vite + Axios (browser / Capacitor Android)
                │  JSON over HTTP
                ▼
Spring Boot 3 REST API  ──►  PostgreSQL (Flyway migrations)
      │        │
      │        └──► Optional OpenAI-compatible chat completions API
      └──► RSS feeds (OpenAI, DeepMind, Google AI, Hugging Face, TechCrunch, arXiv)
             fetched by Spring Scheduler every 3 hours (Asia/Kolkata)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for package layout and design decisions.

## Technology stack

| Layer    | Technology |
|----------|------------|
| Backend  | Java 17, Spring Boot 3.5, Spring Web, Spring Security, Spring Data JPA, Validation, Scheduler, Actuator |
| Database | PostgreSQL 16, Flyway migrations |
| RSS      | ROME 2.1 (`com.rometools:rome`) over the JDK HTTP client |
| Frontend | React 18, JavaScript, custom CSS, Axios, React Router (hash routing) |
| Build    | Maven, Vite, npm |
| Android  | Capacitor 8 |

## Folder structure

```
daily-ai-assistant/
├── backend/
│   ├── src/main/java/com/dailyai/assistant/
│   │   ├── config/        AppProperties, CORS
│   │   ├── controller/    REST endpoints
│   │   ├── dto/           API records
│   │   ├── entity/        JPA entities
│   │   ├── exception/     API error handling
│   │   ├── integration/   RSS fetch/parse, categorizer, optional AI client
│   │   ├── repository/    Spring Data repositories
│   │   ├── scheduler/     @Scheduled refresh + startup loader
│   │   └── service/       business logic
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── db/migration/  V1 schema, V2–V5 seed data
│   └── src/test/java/     unit, MVC and repository tests
├── frontend/
│   ├── src/api/           Axios client and endpoint functions
│   ├── src/components/    layout, cards, states, icons
│   ├── src/pages/         home, discover, article, learn, topic, bookmarks, profile
│   ├── src/hooks/         data loading hook
│   └── src/styles.css     custom mobile-first CSS with light/dark themes
├── docker-compose.yml
├── .env.example
└── docs: README, ARCHITECTURE, API_DOCUMENTATION, DEVELOPMENT_PROGRESS, SESSION_LOG
```

## Prerequisites

- Java 17+ and Maven 3.9+
- Node.js 20+ and npm
- PostgreSQL 16 (local or Docker)
- Optional: Docker Desktop, Android Studio + SDK for the APK

## 1. Database setup

### Option A: Docker (recommended)

```bash
cp .env.example .env          # set a strong DB_PASSWORD
docker compose up -d db
```

The database listens on `localhost:5432` with database/user `dailyai`.

### Option B: Existing PostgreSQL

```sql
CREATE DATABASE dailyai;
CREATE USER dailyai WITH PASSWORD 'your-password';
GRANT ALL PRIVILEGES ON DATABASE dailyai TO dailyai;
```

Flyway creates all tables and seed data on the first backend start. No manual SQL is needed.

## 2. Run the backend

```bash
cd backend
export DB_URL=jdbc:postgresql://localhost:5432/dailyai
export DB_USERNAME=dailyai
export DB_PASSWORD=your-password
mvn spring-boot:run
```

- API: http://localhost:8080
- Health: http://localhost:8080/actuator/health
- On an empty article table the app fetches all configured feeds once at startup.

Run the tests:

```bash
cd backend
mvn test
```

## 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Vite proxies `/api` to `http://localhost:8080`.
For a standalone production build set `VITE_API_BASE_URL`:

```bash
# frontend/.env.local
VITE_API_BASE_URL=http://localhost:8080
npm run build
npm run preview
```

## 4. Run everything with Docker Compose

```bash
cp .env.example .env          # set DB_PASSWORD and optionally ADMIN_API_KEY
docker compose up --build
```

- Frontend: http://localhost:3000
- API: http://localhost:8080

## Authentication and multi-user isolation

Visitors can browse public news and article explanations without an account. A persistent,
backend-managed HttpOnly `DAILYAI_GUEST` cookie scopes anonymous reading interests; it is
rotated after those interests are offered to a newly registered or logged-in account.
Registration and login establish a server-side session in an HttpOnly cookie
(`DAILYAI_SESSION`); no password or session token is stored in localStorage.
Passwords are hashed with BCrypt and are never returned by the API or written to logs. CSRF
tokens are required for cookie-authenticated mutations, and login attempts are throttled.

Every personal record is queried by the authenticated user id: profile, interests, bookmarks,
reading history, learning progress and daily-task progress. Article discovery remains readable
without an account, but reading history is recorded only for the signed-in reader.

Admin endpoints require `ROLE_ADMIN` or a valid `X-Admin-Key`; a normal user cannot refresh
feeds. The existing single-profile account keeps its id and data, but has no password until an
operator activates it with environment variables (never committed):

```bash
BOOTSTRAP_EMAIL=fresher@dailyai.local
BOOTSTRAP_PASSWORD=<a strong password, set only in .env or the host environment>
BOOTSTRAP_ROLE=ADMIN   # optional; USER is the default
```

The bootstrap runs once, never overwrites an existing password and never creates an account.
Session settings:

```bash
SESSION_TIMEOUT=8h
AUTH_COOKIE_SECURE=true        # required with HTTPS in production
AUTH_COOKIE_SAME_SITE=none     # only for a cross-site Capacitor deployment over HTTPS
```

For browser deployments on the same site, keep `AUTH_COOKIE_SAME_SITE=lax`. Android release
builds must use an HTTPS API with `AUTH_COOKIE_SECURE=true` and, because the WebView origin is
`https://localhost`, `AUTH_COOKIE_SAME_SITE=none`. The app's `MainActivity` enables WebView
cookie handling for that trusted API origin. Local HTTP emulator builds cannot use a
`SameSite=None` cookie; use an HTTPS tunnel or same-origin deployment to test Android login.

## RSS configuration

Feeds live in `backend/src/main/resources/application.yml` under `app.rss.feeds`. Each entry
has a name, URL and fallback category. The scheduler runs at minute 0 every third hour in
`Asia/Kolkata`; the timestamp is configurable with `APP_TIMEZONE`.

Manual refresh:

```bash
# Admin key (works without a browser session):
curl -X POST -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8080/api/admin/news/refresh
```

A signed-in account with `ROLE_ADMIN` can also refresh from the Discover screen. Normal users
cannot call admin endpoints.

Behaviour and limits:

- Articles are de-duplicated by the original URL (database unique constraint plus in-run set).
- A failing, slow or malformed feed is recorded in the refresh result and never stops other feeds.
- gzip/deflate responses are decompressed before parsing.
- Publication dates are never fabricated; a feed without a date stores `null`.
- Only the newest `max-items-per-feed` entries per feed are considered.
- The publisher is shown as the source; "Read more" opens the original URL.
- Please respect each publisher's terms and rate limits. Default scheduling is deliberately low frequency.

## Optional AI explanations

The AI layer is provider independent: it talks to any service that exposes an
OpenAI-compatible `POST /chat/completions` endpoint. Only the Spring Boot backend calls the
provider. API keys live in backend environment variables and are never sent to, or bundled
with, the frontend or the Android APK.

```bash
AI_ENABLED=true
AI_PROVIDER=groq                        # telemetry label only
AI_BASE_URL=https://api.groq.com/openai/v1
AI_API_KEY=...                          # never commit this
AI_MODEL=openai/gpt-oss-20b
```

Any OpenAI-compatible endpoint works (OpenAI, Groq, Gemini's compatibility layer, DeepSeek,
OpenRouter). See `docs/AI_PROVIDER_COMPARISON.md` for prices and trade-offs.

Optional resilience settings (defaults shown):

```bash
AI_TIMEOUT_MS=30000        # per request, connect + read
AI_MAX_ATTEMPTS=2          # total attempts for transient failures
AI_RETRY_DELAY_MS=1000     # base delay, doubled per attempt (capped at 10s)
AI_FAILURE_THRESHOLD=2     # consecutive failures before pausing
AI_COOLDOWN_MS=300000      # pause length after the threshold is reached
AI_MAX_CALLS_PER_DAY=100   # hard provider-request cap; 0 disables it
AI_RETRY_AFTER_MINUTES=360 # do not retry a failed article inside this window
```

Behaviour:

- With no key (or `AI_ENABLED=false`) every article gets the truthful offline explanation
  built only from the feed title and description; the app is fully functional.
- `429` and `5xx` responses are retried with bounded exponential backoff and an honoured
  `Retry-After` header. Timeouts and network errors are retried, then swallowed.
- After repeated failures the provider is skipped during the cooldown, so article pages keep
  loading quickly instead of waiting on a broken provider.
- `400`/`401`/`403` are treated as configuration errors and are not retried.
- The prompt forbids invented facts and requires `"Not available in the provided text."` for
  unknown details. `generatedBy` in the API reports `AI` or `BASIC`.
- AI text must include claim-level evidence. Each claim is stored only when its quote exists
  in the publisher title or description; unsupported claims are dropped. The BASIC fallback
  is used when no claim can be grounded.
- Provider, model, input/output tokens and latency are stored per explanation and logged
  without prompts or secrets. `AI_MAX_CALLS_PER_DAY` caps spending.
- Personalisation stays deterministic: the article explanation is cached globally, while the
  `LEARN_NOW` / `EXPLORE_LATER` / `SKIP` verdict is computed per authenticated user from their
  own interests, so one user's preferences never leak into another user's page.
- A response that is not usable JSON triggers one retry without `response_format`, then the
  offline explanation is kept.

Tests use a local mock provider that implements the OpenAI protocol, so no key is needed:

```bash
cd backend
mvn test -Dtest=AiClientTest,ExplanationServiceTest
```

Real-provider generation has not been verified in this environment because no `AI_API_KEY`
is available; the complete request/response path was verified end to end against the mock
provider (see `DEVELOPMENT_PROGRESS.md`).

## Android build (Capacitor)

The Android project lives in `frontend/android` with application ID `com.dailyai.assistant`,
app name `Daily AI Assistant`, minSdk 24 and targetSdk 36 (Capacitor 8).

Prerequisites:

- **JDK 21** for Capacitor 7/8 (JDK 17 for Capacitor 6). `npm run android:build` selects the
  JDK from the installed Capacitor major, checks `JAVA_HOME`, the project-local `.tools/jdk-*`,
  then `~/.jdks/*`, and passes it to Gradle only for this build. It does not change machine-wide
  Java settings. Capacitor 8's Android library compiles with Java source level 21.
- **Android SDK** with Platform 36, Build-Tools 36.0.0 and platform-tools for Capacitor 8
  (Platform 35 for Capacitor 7; Platform 34 for Capacitor 6). The script looks in
  `ANDROID_HOME`, `ANDROID_SDK_ROOT`, the project-local `.tools/android-sdk`, then the default
  per-user location. A project-local install used by this repository:

  ```bash
  # from the project root; download the official Android command-line tools first
  mkdir -p .tools/android-sdk/cmdline-tools
  curl -L -o /tmp/cmdline-tools.zip \
    https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip
  unzip -q /tmp/cmdline-tools.zip -d .tools/android-sdk/cmdline-tools
  mv .tools/android-sdk/cmdline-tools/cmdline-tools .tools/android-sdk/cmdline-tools/latest
  yes | .tools/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat \
    --sdk_root="$(cygpath -m "$PWD/.tools/android-sdk")" --licenses
  yes | .tools/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat \
    --sdk_root="$(cygpath -m "$PWD/.tools/android-sdk")" \
    "platform-tools" "platforms;android-36" "build-tools;36.0.0"
  ```

  (Use `sdkmanager` without `.bat` on macOS/Linux.)
- Capacitor 8 uses Android Gradle Plugin 8.13.0 and the wrapper downloads Gradle 8.14.3
  on the first build (internet required).

The API URL is compiled into the web assets, so set it before building:

```bash
cd frontend
cp .env.android.example .env.android
# edit VITE_API_BASE_URL: emulator http://10.0.2.2:8080 | phone http://<LAN-IP>:8080
# production https://api.your-domain.example
```

The Android build **fails on purpose** when `VITE_API_BASE_URL` is missing or points at
`localhost`/`127.0.0.1`, because neither works on a phone. There is no default production URL:
a release build needs a real deployed backend over HTTPS.

Build the debug APK:

```bash
cd frontend
npm run android:build
# APK: frontend/android/app/build/outputs/apk/debug/app-debug.apk
```

The script builds the web assets with `vite build --mode android`, runs `npx cap sync android`,
writes the gitignored `android/local.properties` with the detected SDK, and calls
`gradlew assembleDebug` with the matching JDK + SDK on `PATH`.

Install on a connected phone (USB debugging enabled):

```bash
# Windows (SDK in the project)
.tools/android-sdk/platform-tools/adb.exe install -r \
  frontend/android/app/build/outputs/apk/debug/app-debug.apk
.tools/android-sdk/platform-tools/adb.exe shell am start -n \
  com.dailyai.assistant/.MainActivity
# macOS/Linux, SDK on PATH
adb install -r frontend/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.dailyai.assistant/.MainActivity
```

Or copy the APK to the phone and open it (allow installing from unknown sources).

Notes for physical devices:

- The phone and computer must be on the same network; the backend must listen on all interfaces
  (Spring Boot's default `0.0.0.0:8080`, which was verified with the LAN URL).
- Windows Firewall may need to allow inbound TCP 8080 for the phone to reach the backend.
- Plain HTTP to a LAN IP requires cleartext traffic, enabled in
  `android/app/src/main/AndroidManifest.xml` (`android:usesCleartextTraffic="true"`).
- Authentication on Android requires HTTPS with `AUTH_COOKIE_SECURE=true` and
  `AUTH_COOKIE_SAME_SITE=none`; the WebView origin `https://localhost` is cross-site to the
  API. `MainActivity` enables cookie handling for that trusted origin. A plain-HTTP emulator
  build cannot send a `SameSite=None` cookie, so use an HTTPS tunnel or same-origin deployment
  for authenticated device testing. Release builds should remove cleartext traffic.
- The APK is not committed (`*.apk` is ignored). A debug APK was built in this repo at
  `frontend/android/app/build/outputs/apk/debug/app-debug.apk` (3,780,981 bytes, v1+v2 signed).

## API documentation

Full request/response reference: [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

| Group      | Endpoints |
|------------|-----------|
| News       | `GET /api/news`, `/latest`, `/search`, `/category/{slug}`, `/{id}` |
| Categories | `GET /api/categories` |
| Learning   | `GET /api/learning/roadmap`, `/topics`, `/topics/{id}`, `/progress`, `/daily-plan`; `POST /api/learning/progress`, `/daily-plan/{taskId}` |
| Tools      | `GET /api/tools`, `/tools/{id}`, `/tools/categories` |
| Bookmarks  | `GET/POST /api/bookmarks`, `DELETE /api/bookmarks/{id}`, `DELETE /api/bookmarks?itemType=&itemId=` |
| Dashboard  | `GET /api/dashboard` |
| Profile    | `GET/PUT /api/profile`, `GET /api/profile/history` |
| Admin      | `POST /api/admin/news/refresh`, `POST /api/admin/articles/{id}/explain`, `GET /api/admin/status` |

Public read-only endpoints: news, categories and AI tools. Cookie-scoped guest personalization
endpoints live under `/api/guest`; they do not expose guest records by ID. Registration, login,
logout, CSRF and current-user endpoints live under `/api/auth`. Dashboard, profile, bookmarks
and learning endpoints require a session and are always scoped to the authenticated owner.
Admin endpoints require `ROLE_ADMIN` or a valid `X-Admin-Key` header.

## Testing

```bash
cd backend && mvn test        # auth, isolation, migration and existing regression tests
cd frontend && npm run build  # production build check

# Browser E2E (needs the backend + `npm run preview` running, and Chrome installed)
cd frontend && npm run test:auth  # registration, login, logout, persistence, isolation
cd frontend && npm run test:e2e   # existing feature regression flows
```

Backend tests cover registration, duplicate/weak/invalid input, generic login failures, login
throttling, logout, CSRF, session restoration, admin authorization, cross-user bookmark and
progress attacks, reading-history ownership, Flyway migration on a preserved owner, RSS
parsing, gzip fetching, duplicate detection, categorizer fallback, article listing and sorting,
repository queries against H2, validation and existing 404 handling.

The authentication suite (`frontend/scripts/auth-e2e.mjs`) registers two independent users and
verifies redirect-to-login, generic credential errors, session persistence after reload, logout
invalidation, bookmark ownership, and that the second user sees neither the first user's
bookmarks nor profile. The regression suite (`frontend/scripts/e2e-smoke.mjs`) signs in first,
then drives navigation, dashboard data, bookmark add/remove, theme persistence, news search,
tool details, topic progress, daily task toggles, article explanations and profile updates.
Both fail on uncaught exceptions or unexpected console errors.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `Cannot reach the backend` in the UI | Check the API URL (`VITE_API_BASE_URL`) and that port 8080 is open. |
| Startup fails with `Schema-validation` errors | Flyway did not run; check DB credentials and `spring.flyway.enabled`. |
| `401` on admin refresh | Set/enter the same `X-Admin-Key` value as `ADMIN_API_KEY`. |
| Empty news list | Run a manual refresh and check the response `errors` array. |
| DeepMind feed failed | It is gzip-compressed; the client now decompresses it automatically. |
| Android app shows network error | Use `10.0.2.2` (emulator) or LAN IP (phone), allow cleartext HTTP, restart backend after changing CORS. |
