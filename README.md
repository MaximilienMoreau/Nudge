# Nudge: Email Open Tracker

> Get alerted the moment someone opens your email — no reply needed.

Nudge is a **browser extension** (Chrome, Edge) and a **tracking pixel** that notifies you in real-time when a recipient opens an email you sent, without waiting for a reply. It also scores engagement and generates AI-powered follow-ups.

---

## Architecture

```
nudge/
├── backend/          Spring Boot 3 (Java 17) — REST API + WebSocket
├── frontend/         Vanilla HTML/CSS/JS — Dashboard UI
├── extension/        Browser Extension (Manifest V3, Chrome & Edge) — Gmail, Outlook, Proton Mail, Infomaniak, Yahoo
└── database/         PostgreSQL schema / init scripts
```

**Two ways to track an email:**
- **Extension** (recommended): install on Chrome or Edge → compose → the pixel is injected automatically on Send
- **Pixel manuel**: create a tracking via the dashboard → copy the pixel → paste it into any email client

---

## Prerequisites

| Tool       | Version       |
|------------|---------------|
| Java       | 17+           |
| Maven      | 3.8+          |
| PostgreSQL | 14+           |
| Chrome or Edge | Latest    |
| OpenAI Key | (optional)    |

---

## Quick Start

### 1. Database

```bash
psql -U postgres -c "CREATE DATABASE nudge;"
```

The `dev` profile connects to `localhost:5432/nudge` with username `postgres` and password `password`.
If your local PostgreSQL uses different credentials, override them before running:

```bash
DB_PASSWORD=yourpassword mvn spring-boot:run
# or
DB_USERNAME=youruser DB_PASSWORD=yourpassword mvn spring-boot:run
```

Hibernate creates the schema automatically on first start (`JPA_DDL_AUTO=update`) — no migration scripts to run.

### 2. Backend

```bash
cd backend

# Optional — AI follow-ups require this; fallback text is used without it
export OPENAI_API_KEY=sk-...

mvn spring-boot:run
```

The `dev` profile is active by default (`mvn spring-boot:run`). It supplies all required secrets
(`JWT_SECRET`, `ENCRYPTION_KEY`) and sets `JPA_DDL_AUTO=update` so Hibernate creates the schema
automatically — no manual configuration needed for local development.

The API and the frontend both start at `http://localhost:8080`.

#### Key environment variables

| Variable         | Default           | Description                       |
|------------------|-------------------|-----------------------------------|
| `OPENAI_API_KEY` | *(empty)*         | OpenAI key for AI follow-ups      |
| DB URL           | `localhost/nudge` | See `application.properties`      |
| JWT secret       | Base64 string     | **Change before going to production** |

### 3. Frontend

The dashboard is served directly by Spring Boot at `http://localhost:8080` — no separate server
needed. Open that URL in your browser after starting the backend.

If you prefer a dedicated static server (e.g. for hot-reload during UI development), run it on
any port and access via `http://localhost:<port>`. Cross-origin requests to the backend are
supported via CORS.

```bash
cd frontend

# Option A: served by the backend (recommended — no cross-origin setup needed)
# Just open http://localhost:8080 after starting the backend.

# Option B: standalone static server
npx serve .              # defaults to port 3000
# or
python3 -m http.server 3000
```

### 4. Browser Extension (Chrome or Edge)

The extension uses Manifest V3 and runs on any Chromium-based browser — no code changes needed between Chrome and Edge.

**Chrome:**
1. Go to `chrome://extensions/`
2. Enable **Developer mode** (top right)
3. Click **Load unpacked** → select the `/extension` folder
4. The Nudge icon appears in your toolbar — click it to sign in

**Edge:**
1. Go to `edge://extensions/`
2. Enable **Developer mode** (left sidebar)
3. Click **Load unpacked** → select the `/extension` folder
4. The Nudge icon appears in your toolbar — click it to sign in

> To publish on the Edge Add-ons store, submit the same `/extension` folder without modification — Edge accepts Chrome Manifest V3 extensions natively.

---

## API Reference

Authentication uses an **httpOnly cookie** (`nudge_jwt`) set on login. All protected endpoints read the cookie automatically — no `Authorization` header needed from the browser. API clients (e.g. the Chrome extension) may alternatively send `Authorization: Bearer <token>`.

### Authentication (public)

| Method | Path                   | Description                                      |
|--------|------------------------|--------------------------------------------------|
| POST   | `/api/auth/register`   | Create a new account                             |
| POST   | `/api/auth/login`      | Exchange credentials for a JWT                   |
| PUT    | `/api/auth/password`   | Change password (requires JWT) — returns new JWT |
| POST   | `/api/auth/logout`     | Revoke current token server-side (requires JWT)  |
| DELETE | `/api/auth/account`    | Permanently delete the account (requires JWT)    |

**DELETE `/api/auth/account` body:**
```json
{ "password": "current-password" }
```

> Password confirmation is required to prevent accidental or CSRF-triggered deletion. All tracked emails and events are removed via `ON DELETE CASCADE`.

**POST `/api/auth/register` and `/api/auth/login` body:**
```json
{ "email": "you@example.com", "password": "secret" }
```

**Response (`AuthResponse`):**
```json
{
  "token": "eyJ...",
  "email": "you@example.com",
  "userId": 1,
  "createdAt": "2027-01-01T12:00:00"
}
```

---

### Emails (requires JWT)

| Method | Path                         | Description                                    |
|--------|------------------------------|------------------------------------------------|
| GET    | `/api/emails`                | List active tracked emails (paginated)         |
| POST   | `/api/emails`                | Register a new email for tracking              |
| GET    | `/api/emails/{id}`           | Get a single email with full stats             |
| DELETE | `/api/emails/{id}`           | Soft-delete (archive) an email                 |
| GET    | `/api/emails/archived`       | List archived emails                           |
| POST   | `/api/emails/{id}/restore`   | Restore an archived email                      |
| DELETE | `/api/emails/{id}/permanent` | Permanently delete an email and all its events |
| POST   | `/api/emails/{id}/schedule`  | Schedule a follow-up reminder                  |

**GET `/api/emails` query params:** `?page=0&size=50`

**POST `/api/emails` body:**
```json
{
  "subject": "Follow up on our meeting",
  "recipientEmails": ["john@company.com", "jane@company.com"],
  "content": "Hi John, just wanted to follow up..."
}
```

> `recipientEmails` (array) is preferred. `recipientEmail` (single string) is also accepted for backwards compatibility — when both are present, `recipientEmails` takes precedence.

**Response includes:**
```json
{
  "subject": "Product demo",
  "recipientEmails": ["alice@co.com", "bob@co.com"],
  "content": "Hi, here is the link..."
}
```

**POST `/api/emails/{id}/schedule` body:**
```json
{ "scheduledAt": "2027-04-20T09:00:00" }
```

**Email response (`EmailDTO`):**
```json
{
  "id": 42,
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "trackingPixelUrl": "http://localhost:8080/track/open/{uuid}",
  "clickTrackingBaseUrl": "http://localhost:8080/track/click/{uuid}",
  "leadScore": 75,
  "status": "Opened Multiple Times",
  "openCount": 3,
  "clickCount": 1,
  "createdAt": "2027-01-01T12:00:00",
  "lastOpenedAt": "2027-01-02T09:00:00",
  "lastClickedAt": "2027-01-02T09:01:00"
}
```

---

### Tracking (public — called automatically by email clients)

| Method | Path                              | Description                                    |
|--------|-----------------------------------|------------------------------------------------|
| GET    | `/track/open/{trackingId}`        | Returns 1×1 GIF and logs an OPEN event         |
| GET    | `/track/click/{trackingId}?url=`  | Logs a CLICK event and 302-redirects to `url`  |

> **`url` must use `http` or `https`.** Any other scheme (`javascript:`, `data:`, `file:`, etc.) or a missing `url` parameter returns `400 Bad Request`. Always URL-encode the destination before appending it.

Embed in emails:
```html
<!-- Tracking pixel (invisible) -->
<img src="http://localhost:8080/track/open/{trackingId}" width="1" height="1" style="display:none" alt=""/>

<!-- Tracked link -->
<a href="http://localhost:8080/track/click/{trackingId}?url=https%3A%2F%2Fyour-link.com">Click here</a>
```

---

### AI (requires JWT)

| Method | Path                  | Description                                         |
|--------|-----------------------|-----------------------------------------------------|
| POST   | `/api/ai/followup`    | Generate an AI follow-up email                      |
| POST   | `/api/ai/send-time`   | Suggest the best day and hour to send based on history |

**POST `/api/ai/followup` body:**
```json
{
  "emailId": 42,
  "daysSinceSent": 3
}
```

> `engagementScore` and `openCount` are intentionally not accepted from the client — the server recomputes them from the database to prevent tampering.

### WebSocket

Connect to `ws://localhost:8080/ws` using SockJS + STOMP.

The server authenticates via the `nudge_jwt` httpOnly cookie sent automatically by the browser on the SockJS handshake — no explicit token parameter is needed.

Subscribe to: `/user/queue/notifications`

**Notification payload (EMAIL_OPENED / EMAIL_CLICKED):**
```json
{
  "type": "EMAIL_OPENED",
  "emailId": 42,
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "openCount": 2,
  "leadScore": 65,
  "timestamp": "2027-01-15T10:30:00"
}
```

---

## Lead Scoring

The Reply Probability Score (0–100) is computed from genuine opens only — bot/proxy pre-fetches (Apple MPP, Google Image Proxy, MS Exchange Safe Links, etc.) are detected and excluded automatically.

| Signal              | Points                                      |
|---------------------|---------------------------------------------|
| Opens volume        | 15 per open, max 40                         |
| Recency             | Continuous exponential decay: `40 × e^(−λt)`, half-life = 6 h |
| Frequency (> 5×)    | 20                                          |
| Frequency (> 3×)    | 15                                          |
| Frequency (> 1×)    | 10                                          |
| Click (1 click)     | 10                                          |
| Click (≥ 2 clicks)  | 20                                          |

Recency examples: just opened → 40 pts · 6 h ago → 20 pts · 12 h ago → 10 pts · 48 h+ → 0 pts.

Scores ≥ 70 are flagged as **Hot Leads** 🔥.

---

## Extension Usage (Chrome & Edge)

1. Sign in via the popup with your Nudge account credentials
2. Open Gmail, Outlook, Proton Mail, Infomaniak, or Yahoo Mail and compose a new email
3. A **"📨 Nudge: ON"** button appears next to the Send button
4. Click Send — Nudge automatically registers the email and injects the tracking pixel
5. **The moment the recipient opens your email**, you get an instant notification — no reply needed

### Without the extension (any email client)

1. Go to the dashboard → **Track Email**
2. Fill in the subject, recipient, and body
3. Copy the generated pixel HTML: `<img src="..." width="1" height="1" style="display:none"/>`
4. Paste it into your email before sending
5. You'll still receive real-time open notifications on the dashboard

---

## Production Checklist

- [ ] Change `jwt.secret` in `application.properties` to a strong random key
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (not `update`)
- [ ] Configure a real database with proper credentials
- [ ] Use HTTPS — tracking pixels won't load over HTTP in many email clients
- [ ] Update `app.base.url` to your production domain
- [ ] Update `API_BASE` in `frontend/js/config.js` to point to your production backend URL
- [ ] Set `OPENAI_API_KEY` environment variable
- [ ] Configure CORS `allowedOriginPatterns` to your specific frontend domain
- [ ] Replace in-memory rate limiter with Redis + Bucket4j for multi-instance deployments

---

## Tech Stack

| Layer       | Technology                          |
|-------------|-------------------------------------|
| Backend     | Java 17, Spring Boot 3.2            |
| Database    | PostgreSQL + Spring Data JPA        |
| Auth        | JWT (jjwt 0.11.5) + BCrypt          |
| Real-time   | WebSocket + STOMP + SockJS          |
| AI          | OpenAI `gpt-4o-mini` via REST       |
| Frontend    | HTML5 + CSS3 + Vanilla JS           |
| Extension   | Browser Extension Manifest V3 (Chrome & Edge) |
