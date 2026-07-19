# Use Cursor gateway with VS Code Copilot

The gateway exposes an OpenAI Chat Completions API on localhost and proxies to the Cursor ACP service (`open_ai_cursor_api`) via Playwright browser automation. VS Code Copilot connects via **BYOK Custom Endpoint**.

API contract: [VSCODE_COPILOT_API_SPEC.md](VSCODE_COPILOT_API_SPEC.md)

## Two-machine setup

| Machine | Runs | Role |
|---------|------|------|
| **Host PC** | `open_ai_cursor_api` on port 8081 | Cursor LLM proxy only (ask mode + temp sandbox; no project file edits) |
| **Remote PC** | `open_ai_api` on port 18080 + VS Code Copilot | Playwright → host UI; **Agent tools edit remote workspace** |

**Host PC:**

```bash
cd open_ai_cursor_api
PORT=8081 node --env-file=.env dist/index.js
```

**Remote PC** — set `app.upstream.page-url` to `http://<host-ip>:8081/` in `application.yml`, then:

```bash
cd open_ai_api
./mvnw spring-boot:run
```

## 1. Verify gateway

```bash
curl http://127.0.0.1:18080/health
curl http://127.0.0.1:18080/v1/models
```

Expected: Cursor models (`composer-2.5`, `auto`, etc.) when upstream is reachable.

## 2. Configure VS Code Copilot

1. Open **Chat** → **Manage Language Models**
2. **Add Models** → **Custom Endpoint**
3. Merge [`chatLanguageModels.example.json`](chatLanguageModels.example.json) into `chatLanguageModels.json`
4. Use gateway URL: `http://127.0.0.1:18080/v1/chat/completions`
5. Use Cursor model ids (`composer-2.5`, `auto`, …) — not `dummy-*` for production

**Common failures:**

- `url` pointing at `http://127.0.0.1:8081/...` — that is the Cursor API host. Use the **gateway** at `18080`.
- Playwright not ready — ensure host Cursor API UI is reachable at `app.upstream.page-url`.

## 3. How it works

1. Copilot → `POST /v1/chat/completions` on gateway (18080)
2. If Agent mode sends `tools[]`, gateway forces `cursor.mode=ask` (no host cwd)
3. Playwright in-page `fetch` to host `/ui/run` (robot-only opaque UI; large bodies OK up to ~100MB)
4. Cursor returns OpenAI-shaped `tool_calls` (or plain text)
5. **VS Code executes tools on the remote workspace**
6. If Copilot sends `stream: true`, gateway adapts JSON to SSE

Host Cursor API never creates/edits project files (temp sandbox only).

**Context clear**

- VS Code **New Chat** (no assistant/tool turns) does not resume ACP (`agentId` cleared for that fingerprint). Full ACP wipe: `POST /v1/cursor/sessions/reset`
- Switching continued chats on the same model resumes each by fingerprint (`first user` + first assistant when present)
- Idle timeout (default 30 min) clears gateway session maps
- Manual hard reset: `POST http://127.0.0.1:18080/v1/cursor/sessions/reset`

**Context usage**

- Completions include estimated OpenAI `usage` (`prompt_tokens` / `completion_tokens` / `total_tokens`, chars÷4)
- When Copilot requests `stream: true`, the gateway SSE finish path also emits a usage chunk

## 4. Configuration

| Key | Description |
|-----|-------------|
| `app.upstream.page-url` | Host Cursor API UI (browser-reachable) |
| `app.upstream.session-resume` | Resume within one Copilot chat fingerprint |
| `app.upstream.session-idle-ms` | Auto-clear idle sessions |

## 5. Offline smoke tests

Set `app.upstream.enabled: false` and use `dummy-*` models for protocol tests without Cursor.

```bash
curl http://127.0.0.1:18080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"dummy-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
```
