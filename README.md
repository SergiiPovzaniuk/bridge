# Java relay (thin, dumb byte relay)

Spring Boot app for the **remote PC**. Exposes a real OpenAI-compatible API to
VS Code Continue, and gets all model output from a headless Chromium tab that
holds a WebSocket connection to the Python host (`cursor_openai_bridge`) on
your main PC. This app has **zero protocol knowledge** — it forwards JSON
frames both ways and never touches the DOM.

```
VS Code Continue  --OpenAI SSE-->  open_ai_api :18080  --page.evaluate-->  Chromium (headless)
                                         ^                                      |
                                         |                                      | wss /relay
                                         +---------- exposeBinding push --------+
                                                                                 v
                                                                    cursor_openai_bridge :8787
                                                                    (your main PC, has CURSOR_API_KEY)
```

Continue's own agent loop executes `tool_calls` locally, so files mutate on
**this** (remote) machine natively, with Continue's normal diff/approval UI.

## 1. Install Chromium (Playwright) on the remote PC

```bash
cd open_ai_api
./mvnw -q -DskipTests package
# One-time browser install (downloads ~140MB Chromium only, not Firefox/WebKit):
mvn -q exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

If you hit `Timed out waiting for browsers to install` because the driver
tries to fetch all three engines, either retry (already-downloaded browsers
are skipped) or set `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` once Chromium is
present under `%LOCALAPPDATA%\ms-playwright` (Windows) / `~/.cache/ms-playwright`
(Linux/macOS) so startup never re-checks the other engines.

## 2. Run it

```bash
java -jar target\open-ai-api-0.0.1-SNAPSHOT.jar
```

No env vars needed — `bridge-url`/`relay-token`/`bearer-token`/`headless` are hardcoded in `application.yml`, and the jar sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` on itself at startup (via a small reflection call in `OpenAiApiApplication.main`, backed by an `Add-Opens` manifest entry baked into the jar by `maven-jar-plugin` — no `--add-opens` flag or launcher script required), so a Chromium already installed by step 1 is reused as-is with zero re-download checks. This is required on machines where only the jar itself can be launched directly (no shell scripts, no manual env vars). Override any hardcoded value per-run if you ever need to:

```bash
set BRIDGE_URL=http://<your-main-pc-ip>:8787/
set RELAY_TOKEN=<same value as the host's RELAY_TOKEN>
set BEARER_TOKEN=<token Continue will send>
java -jar target\open-ai-api-0.0.1-SNAPSHOT.jar
```

Or via `application.yml` / any Spring env source — see `app.relay.*` in
[`RelayProperties`](src/main/java/com/openaiapi/config/RelayProperties.java):

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `app.relay.bridge-url` | `BRIDGE_URL` | `http://127.0.0.1:8787/` | URL of the Python host's robot page |
| `app.relay.relay-token` | `RELAY_TOKEN` | empty | Shared secret for the `/relay` WebSocket (must match host `.env`) |
| `app.relay.bearer-token` | `BEARER_TOKEN` | empty | Token Continue must send as `Authorization: Bearer ...` |
| `app.relay.headless` | `RELAY_HEADLESS` | `true` | Run Chromium headless |
| `app.relay.browsers-path` | `PLAYWRIGHT_BROWSERS_PATH` | system default | Custom Playwright browser cache dir |
| `app.relay.response-timeout-ms` | — | `300000` | Max wait for a single relay round trip |

The relay listens on `127.0.0.1:18080` by default (`server.address` /
`server.port` in [`application.yml`](src/main/resources/application.yml)).
Bind `0.0.0.0` only if Continue runs on a different machine than this relay,
and put it behind TLS + a firewall allowlist in that case (see below).

## 3. Point VS Code Continue at it

`config.yaml` (new Continue config format):

```yaml
models:
  - name: Cursor Agent
    provider: openai
    model: composer-2.5:agent
    apiBase: http://127.0.0.1:18080/v1
    apiKey: <BEARER_TOKEN>
    roles: [chat, edit, apply]
  - name: Cursor Plan
    provider: openai
    model: composer-2.5:plan
    apiBase: http://127.0.0.1:18080/v1
    apiKey: <BEARER_TOKEN>
    roles: [chat]
  - name: Cursor Ask
    provider: openai
    model: composer-2.5:ask
    apiBase: http://127.0.0.1:18080/v1
    apiKey: <BEARER_TOKEN>
    roles: [chat]
```

Mode is chosen by the `:ask` / `:plan` / `:agent` model-id suffix, or the
tools Continue sends. `GET /v1/models` lists every Cursor model with all
three suffixes, so any of them can be picked in Continue's model dropdown.

**Autocomplete and embeddings are not served here** — the Cursor SDK is an
agent SDK, not a completion/embedding API. Configure separate `autocomplete`
and `embed` providers in the same `config.yaml` (e.g. a local Ollama model,
or another provider you already use).

## 4. Security (do this before exposing anything beyond localhost)

- Set both `RELAY_TOKEN` (host↔relay) and `BEARER_TOKEN` (Continue↔relay) to
  long random values — never leave them blank outside of local dev.
- Put TLS in front of both hops once they cross a network boundary: terminate
  `wss://` for the host's `/relay` endpoint and `https://` for the relay's
  `:18080`, e.g. with a reverse proxy (Caddy/nginx) or a self-signed cert.
- Firewall: only the Python host's port (`8787`) needs to be reachable from
  the remote PC; the Java relay's port (`18080`) only needs to be reachable
  from wherever Continue runs. Neither needs to be open to the public
  internet — use a VPN/tailnet if the two machines aren't on the same LAN.
- Set `ALLOWLIST_IPS` and `RATE_LIMIT_PER_MINUTE` on the Python host
  (`cursor_openai_bridge/.env`) if the relay isn't on a trusted network.

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/v1/models`, `/v1/models/{id}` | Relayed from the host's model cache |
| POST | `/v1/chat/completions` | Stream + non-stream, tool calls, all 3 modes |
| POST | `/v1/embeddings`, `/v1/completions` | `501 not_implemented` (see above) |

## Decommissioning the old stack

This replaces **both**:

- `open_ai_cursor_api` (Node/Hono, ACP-over-stdio, AES-sealed `/ui/run`) — no
  longer needed; the Python `cursor_openai_bridge` host replaces it entirely
  and talks to Cursor directly via `cursor-sdk`, no subprocess CLI involved.
- The old `data-testid`-driven robot page and DOM-polling `RelayBrowserSession`
  — replaced by `cursor_openai_bridge/static/robot.html`, a zero-DOM-state
  WebSocket relay pushed to Java via `page.exposeBinding`.

Once the new stack is verified end-to-end, stop `open_ai_cursor_api` and
remove its scheduled tasks/services; nothing in this repo calls it anymore.
