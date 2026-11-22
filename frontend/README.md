# Frontend deployment

## Railway Docker deployment

The current Railway frontend configuration is `frontend/Dockerfile` plus
`frontend/nginx.conf`. Nginx serves the Vite build on port 80 and proxies `/api/*`
to `https://daily-ai-assistant-production.up.railway.app`. The backend hostname is
hard-coded there; update it if the Railway backend service uses a different domain.
Configure the Railway frontend service to expose container port 80. The backend
Dockerfile reads Railway's `PORT` value and exposes `/actuator/health` for health checks.

Keep `VITE_API_BASE_URL` unset for this deployment so browser requests use the
same-origin Nginx proxy. Set the backend variables:

- `AUTH_COOKIE_SECURE=true`
- `AUTH_COOKIE_SAME_SITE=lax`
- `CORS_ALLOWED_ORIGINS` including the exact Railway frontend origin (scheme and host)
- `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` mapped to the Railway PostgreSQL service

Nginx forwards the browser `Origin` header, so the frontend origin must be in the
backend CORS allowlist for credentialed API requests. The proxy sends the backend
host and `X-Forwarded-Proto: https`; the guest cookie also becomes Secure on HTTPS
requests. SameSite Lax is suitable because the browser talks only to the frontend
origin. Spring Boot runs Flyway migrations at startup before Hibernate validates
the resulting schema.

Railway project settings are not checked into this repository: there is no
`railway.toml` or `railway.json`. Configure the backend service root to `backend`
and the frontend service root to `frontend`, each using its Dockerfile. Set the
backend health check path to `/actuator/health` and the frontend target port to 80.

## Optional Vercel deployment

`vercel.json` is a separate, optional deployment path. Its API rewrite currently
contains a placeholder Render URL and must be replaced before using Vercel. This
file is not used by the Railway Docker/Nginx deployment above. For Vercel, leave
`VITE_API_BASE_URL` unset so requests go through that rewrite, and allow the Vercel
origin in backend `CORS_ALLOWED_ORIGINS`.

## Client routes

The app uses `HashRouter` (`src/main.jsx`), so client routes live after `#` and do
not require an SPA fallback rewrite.
