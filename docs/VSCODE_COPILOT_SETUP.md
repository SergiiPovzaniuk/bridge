# Use Cursor gateway with VS Code Copilot

The gateway exposes an OpenAI Chat Completions API on localhost and proxies to the Cursor ACP service (`open_ai_cursor_api`) via Playwright browser automation. Connect through the **GitHub Copilot LLM Gateway** extension when the regular Copilot custom-endpoint flow is blocked.

API contract: [VSCODE_COPILOT_API_SPEC.md](VSCODE_COPILOT_API_SPEC.md)

## Two-machine setup

| Machine | Runs | Role |
|---------|------|------|
| **Host PC** | `open_ai_cursor_api` on **TCP 8094** (`http://46.174.75.130:8094/`) | Cursor LLM proxy; forward **8094** from remote |
| **Remote PC** | `open_ai_api` on **127.0.0.1:18080** + VS Code Copilot | Playwright → host UI; Chromium at `C:\browser\ms-playwright` |

**Host PC:**

```bash
cd open_ai_cursor_api
PORT=8081 node --env-file=.env dist/index.js
```

**Remote PC** — defaults: `page-url=http://46.174.75.130:8094/`, `base-url=http://46.174.75.130:8094`. Place Chromium at `C:\browser\ms-playwright\chromium-1148\chrome-win\`. Then:

```bash
cd open_ai_api
set TRANSPORT_KEY=dev-shared-transport-key
./mvnw spring-boot:run
```

## 1. Verify gateway

```bash
curl http://127.0.0.1:18080/health
curl http://127.0.0.1:18080/v1/models
```

Expected: Cursor models (`composer-2.5`, `auto`, etc.) when upstream is reachable.

## 2. Configure GitHub Copilot LLM Gateway

1. Install **GitHub Copilot LLM Gateway** (`AndrewButson.github-copilot-llm-gateway`) in VS Code.
2. Open **Settings** and search for `Copilot LLM Gateway`.
3. Set **Server URL** to `http://127.0.0.1:18080`.
   Do not add `/v1`, `/v1/models`, or `/v1/chat/completions`. The gateway accepts both `/models` and `/v1/models` (and the matching chat paths).
4. Set **Request Timeout** to `600000` milliseconds. Cursor ACP requests can take several minutes.
5. Leave **API Key** empty, or use `local`; the gateway accepts and ignores Bearer authentication.
6. In Chat, open the model selector → **Manage Models** → **LLM Gateway**, enable the desired model, then select it.

The gateway includes `max_model_len` and `context_length` in `/v1/models` so the extension can budget context safely.

## 3. Native BYOK Custom Endpoint

1. Open **Chat** → **Manage Language Models**
2. **Add Models** → **Custom Endpoint**
3. Merge [`chatLanguageModels.example.json`](chatLanguageModels.example.json) into `chatLanguageModels.json`
4. Use gateway URL: `http://127.0.0.1:18080/v1/chat/completions`
5. Use Cursor model ids (`composer-2.5`, `auto`, …) — not `dummy-*` for production

**Common failures:**

- `url` pointing at `http://127.0.0.1:8081/...` — that is the Cursor API host. Use the **gateway** at `18080`.
- Playwright not ready — ensure host Cursor API UI is reachable at `app.upstream.page-url`.

## 4. How it works

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

## 5. Configuration

| Key | Description |
|-----|-------------|
| `app.upstream.page-url` | Robot UI URL (default `http://46.174.75.130:8094/`) |
| `app.upstream.session-resume` | Resume within one Copilot chat fingerprint |
| `app.upstream.session-idle-ms` | Auto-clear idle sessions |

## 6. Offline smoke tests

Set `app.upstream.enabled: false` and use `dummy-*` models for protocol tests without Cursor.

```bash
curl http://127.0.0.1:18080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"dummy-gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
```
