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

### Ports / firewall (two machines)

| Where | Port | Bind | Purpose |
|-------|------|------|---------|
| **Host** (`open_ai_cursor_api`) | **8094** | `0.0.0.0` | Robot UI + `/ui/run` — **forward this port** to the host IP |
| **Remote** (`open_ai_api`) | **18080** | `127.0.0.1` | Copilot / local clients only — **do not** need WAN forward |

Browser UI (Playwright opens this): `http://46.174.75.130:8094/`  
HTTP API base (health/models): `http://46.174.75.130:8094`

Forward **TCP 8094** (host ← remote/router). Same `TRANSPORT_KEY` on both machines.

Playwright Chromium is **not** in the project and is **never downloaded**. On the remote PC place it at:

`C:\browser\ms-playwright\chromium-1148\chrome-win\`

(`app.upstream.browsers-path` default: `C:\browser\ms-playwright`)

```bash
cd open_ai_api
# TRANSPORT_KEY defaults to dev-shared-transport-key (must match host .env)
set TRANSPORT_KEY=dev-shared-transport-key
./mvnw spring-boot:run
```

If you see `Transport key is required when upstream is enabled`, set `TRANSPORT_KEY` (or rely on the yaml default above) and restart.

Gateway listens on `http://127.0.0.1:18080`.

Env overrides: `UPSTREAM_BASE_URL`, `UPSTREAM_PAGE_URL` (defaults `http://46.174.75.130:8094`), `UPSTREAM_HEADLESS`, `UPSTREAM_BROWSERS_PATH`, `TRANSPORT_KEY`. Concurrent chats return **429**. Streaming clients receive SSE `: keepalive` heartbeats while ACP runs.

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
| `app.upstream.page-url` / `UPSTREAM_PAGE_URL` | `http://46.174.75.130:8094/` | Robot web UI Playwright opens |
| `app.upstream.base-url` / `UPSTREAM_BASE_URL` | `http://46.174.75.130:8094` | Upstream HTTP base (health/models) |
| `app.upstream.headless` / `UPSTREAM_HEADLESS` | `false` | Show Chromium window (set true for unattended) |
| `app.upstream.browsers-path` / `UPSTREAM_BROWSERS_PATH` | `C:\browser\ms-playwright` | Playwright browsers root (must contain `chromium-1148\`); no runtime download |
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
