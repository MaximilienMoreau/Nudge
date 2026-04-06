# Nudge — AI-Powered Email Assistant

> "Never get ghosted again. Know exactly when and how to follow up."

Nudge helps you track email opens in real-time, score engagement, and generate AI-powered follow-ups.

---

## Architecture

```
nudge/
├── backend/          Spring Boot 3 (Java 17) — REST API + WebSocket
├── frontend/         Vanilla HTML/CSS/JS — Dashboard UI
├── extension/        Chrome Extension (Manifest V3) — Gmail integration
└── database/         PostgreSQL schema / init scripts
```

---

## Prerequisites

| Tool       | Version       |
|------------|---------------|
| Java       | 17+           |
| Maven      | 3.8+          |
| PostgreSQL | 14+           |
| Chrome     | Latest        |
| OpenAI Key | (optional)    |

---

## Quick Start

### 1. Database

```bash
# Start PostgreSQL and create the database
psql -U postgres -c "CREATE DATABASE nudge;"
```

### 2. Backend

```bash
cd backend

# Set your OpenAI key (optional — fallback works without it)
export OPENAI_API_KEY=sk-...

# Edit credentials if needed
nano src/main/resources/application.properties

# Run
mvn spring-boot:run
```

The API starts at `http://localhost:8080`.

#### Key environment variables

| Variable        | Default       | Description                       |
|-----------------|---------------|-----------------------------------|
| `OPENAI_API_KEY`| *(empty)*     | OpenAI key for AI follow-ups      |
| DB URL          | `localhost/nudge` | See application.properties   |
| JWT secret      | Base64 string | Change before going to production |

### 3. Frontend

```bash
cd frontend

# Option A: open directly in browser (simplest)
open index.html          # macOS
xdg-open index.html      # Linux

# Option B: serve with any static server
npx serve .
# or
python3 -m http.server 3000
```

Open `http://localhost:3000` (or the file directly).

### 4. Chrome Extension

1. Open Chrome → `chrome://extensions/`
2. Enable **Developer mode** (top right)
3. Click **Load unpacked**
4. Select the `/extension` folder
5. The Nudge icon appears in your toolbar
6. Click it and sign in with your Nudge account

---

## API Reference

### Authentication (public)

| Method | Path                  | Body                          | Returns      |
|--------|-----------------------|-------------------------------|--------------|
| POST   | `/api/auth/register`  | `{ email, password }`         | `AuthResponse` |
| POST   | `/api/auth/login`     | `{ email, password }`         | `AuthResponse` |

`AuthResponse`: `{ token, email, userId }`

### Emails (requires `Authorization: Bearer <token>`)

| Method | Path              | Description                          |
|--------|-------------------|--------------------------------------|
| GET    | `/api/emails`     | List all tracked emails for the user |
| POST   | `/api/emails`     | Register new email for tracking      |
| GET    | `/api/emails/{id}`| Get single email with stats          |

**POST `/api/emails` body:**
```json
{
  "subject": "Follow up on our meeting",
  "recipientEmail": "john@company.com",
  "content": "Hi John, just wanted to follow up..."
}
```

**Response includes:**
```json
{
  "trackingPixelUrl": "http://localhost:8080/track/open/{uuid}",
  "leadScore": 75,
  "status": "Opened Multiple Times",
  "openCount": 3
}
```

### Tracking (public — called by email clients)

| Method | Path                     | Description                            |
|--------|--------------------------|----------------------------------------|
| GET    | `/track/open/{trackingId}` | Returns 1×1 GIF, logs open event     |

### AI Follow-Up

| Method | Path              | Description                |
|--------|-------------------|----------------------------|
| POST   | `/api/ai/followup`| Generate AI follow-up text |

**Request body:**
```json
{
  "emailId": 42,
  "subject": "Partnership Proposal",
  "originalContent": "Hi...",
  "recipientEmail": "ceo@bigcorp.com",
  "engagementScore": 85,
  "openCount": 4,
  "daysSinceSent": 3
}
```

### WebSocket

Connect to `ws://localhost:8080/ws` using STOMP + SockJS.

Send JWT in CONNECT frame header: `Authorization: Bearer <token>`

Subscribe to: `/user/queue/notifications`

**Notification payload:**
```json
{
  "type": "EMAIL_OPENED",
  "emailId": 42,
  "subject": "Partnership Proposal",
  "recipientEmail": "ceo@bigcorp.com",
  "openCount": 2,
  "leadScore": 65,
  "timestamp": "2025-01-15T10:30:00"
}
```

---

## Lead Scoring

The Reply Probability Score (0–100) is computed from:

| Signal           | Points       |
|------------------|--------------|
| Opens volume     | 15 per open, max 40 |
| Recency (< 1h)   | 40           |
| Recency (< 1d)   | 30           |
| Recency (< 3d)   | 20           |
| Recency (< 7d)   | 10           |
| Frequency (> 5x) | 20           |
| Frequency (> 3x) | 15           |
| Frequency (> 1x) | 10           |

Scores ≥ 70 are flagged as **Hot Leads** 🔥.

---

## Chrome Extension Usage

1. Sign in via the popup using your Nudge account credentials
2. Open Gmail and compose a new email
3. A **"📨 Nudge: ON"** button appears next to the Send button
4. Click Send — Nudge automatically registers the email and injects the tracking pixel
5. When the recipient opens the email, you get an instant notification on your dashboard

---

## Production Checklist

- [ ] Change `jwt.secret` in `application.properties` to a strong random key
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (not `update`)
- [ ] Configure a real database with proper credentials
- [ ] Use HTTPS — tracking pixels won't load over HTTP in many email clients
- [ ] Update `app.base.url` to your production domain
- [ ] Set `OPENAI_API_KEY` environment variable
- [ ] Configure CORS `allowedOriginPatterns` to your specific frontend domain
- [ ] Add rate limiting on `/track/open/**` to prevent pixel spam

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
| Extension   | Chrome Extension Manifest V3        |
