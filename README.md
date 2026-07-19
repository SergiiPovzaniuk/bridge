# OpenAI-Compatible Cursor Gateway

Spring Boot (Java 21) gateway for VS Code Copilot. Proxies OpenAI Chat Completions to the Cursor ACP service (`open_ai_cursor_api`) via Playwright browser automation.

## Architecture

```
Remote PC (VS Code + open_ai_api)          Host PC (Cursor API proxy only)
┌─────────────────────────────┐            ┌──────────────────────────────┐
│ VS Code Copilot             │            │ open_ai_cursor_api :8081     │
│ open_ai_api :18080          │ Playwright │ ACP ask mode + temp sandbox  │
│ Tools edit remote workspace │ ─────────► │ never writes open_ai_cursor_api│
└─────────────────────────────┘            └──────────────────────────────┘
```

- **Agent mode** (`tools[]`): Cursor returns OpenAI `tool_calls`; **VS Code executes them on the remote PC**.
- **Ask / Plan**: text answers; host uses a temp sandbox cwd (not the API project).
- **Context clear**: New Chat (no assistant/tool history) clears ACP resume; idle TTL 30m; `POST /v1/cursor/sessions/reset`.

## Run

**Host PC** (Cursor API):

```bash
cd open_ai_cursor_api
PORT=8081 node --env-file=.env dist/index.js
```

**Remote PC** (gateway):

Default upstream host is `https://46.174.75.130/`. Chromium lives under `src/main/resources/ms-playwright/` (tracked in the repo); the app never downloads browsers (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`). If that folder is missing, fetch once on a networked machine:

```powershell
cd open_ai_api
.\scripts\fetch-playwright-browsers.ps1
```

```bash
cd open_ai_api
set TRANSPORT_KEY=dev-shared-transport-key
./mvnw spring-boot:run
```

Listens on `http://127.0.0.1:18080`. Prefer IntelliJ / `./mvnw spring-boot:run` with exploded resources. Fat JAR alone cannot run Chromium from inside the archive — set `UPSTREAM_BROWSERS_PATH` to the `ms-playwright` folder.

Env overrides: `UPSTREAM_BASE_URL`, `UPSTREAM_PAGE_URL` (defaults `https://46.174.75.130`), `UPSTREAM_HEADLESS`, `UPSTREAM_BROWSERS_PATH`, `TRANSPORT_KEY`. Local smoke: `UPSTREAM_BASE_URL=http://127.0.0.1:8094` and matching `UPSTREAM_PAGE_URL`. Concurrent chats return **429**. Streaming clients receive SSE `: keepalive` heartbeats while ACP runs.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Local liveness + `playwrightReady` / `upstreamReachable` |
| GET | `/v1/models` | List models (from Cursor upstream when enabled) |
| GET | `/v1/models/{id}` | Get model |
| POST | `/v1/chat/completions` | Chat completions via Cursor ACP. `stream: true` adapted to SSE for Copilot. |
| POST | `/v1/cursor/sessions/reset` | Clear ACP session resume (optional `?model=`) |

## Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `app.upstream.enabled` | `true` | Proxy to Cursor API |
| `app.upstream.page-url` / `UPSTREAM_PAGE_URL` | `https://46.174.75.130/` | Browser UI for Playwright |
| `app.upstream.base-url` / `UPSTREAM_BASE_URL` | `https://46.174.75.130` | Upstream HTTPS base |
| `app.upstream.headless` / `UPSTREAM_HEADLESS` | `false` | Show Chromium window (set true for unattended) |
| `app.upstream.browsers-path` | empty | Optional absolute Playwright browsers cache; default is bundled `src/main/resources/ms-playwright` (no runtime download) |
| `app.upstream.cursor-cwd` | empty | Unused (host is sandboxed) |
| `app.upstream.session-resume` | `true` | Resume within one Copilot chat fingerprint |
| `app.upstream.response-timeout-ms` | `600000` | Upstream/ACP wait timeout (10 min) |
| `app.upstream.session-idle-ms` | `1800000` | Auto-clear idle sessions (30 min) |
| `app.upstream.transport-key` / `TRANSPORT_KEY` | required | Shared AES-GCM secret with cursor API (chat body seal) |
| `app.llm.enabled` | `false` | Optional LLM fallback (off by default) |

Chat bodies to/from the Cursor upstream (`/ui/run`) are AES-256-GCM sealed (`enc1:` wire format). Set the same `TRANSPORT_KEY` on both machines.

Models starting with `dummy-` use the local dummy provider for offline smoke tests.

## VS Code Copilot (BYOK)

Step-by-step: [`docs/VSCODE_COPILOT_SETUP.md`](docs/VSCODE_COPILOT_SETUP.md)

HTTP contract: [`docs/VSCODE_COPILOT_API_SPEC.md`](docs/VSCODE_COPILOT_API_SPEC.md)

Template: [`docs/chatLanguageModels.example.json`](docs/chatLanguageModels.example.json)

## Smoke test

```bash
curl http://127.0.0.1:18080/health
curl http://127.0.0.1:18080/v1/models
curl -X POST http://127.0.0.1:18080/v1/cursor/sessions/reset
curl http://127.0.0.1:18080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"dummy-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
```
