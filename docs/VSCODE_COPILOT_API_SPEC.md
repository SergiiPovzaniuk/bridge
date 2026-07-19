# VS Code Copilot HTTP API Specification

Contract for this gateway when used as a VS Code Copilot **BYOK Custom Endpoint** with `apiType: chat-completions`.

This gateway proxies to [`open_ai_cursor_api`](../../open_ai_cursor_api/openapi/openapi.yaml) (Cursor ACP). Setup: [VSCODE_COPILOT_SETUP.md](VSCODE_COPILOT_SETUP.md)

## 1. Base URL and authentication

| Item | Value |
|------|--------|
| Base URL | `http://127.0.0.1:18080` |
| Copilot model `url` | **Full** chat path: `http://127.0.0.1:18080/v1/chat/completions` |
| Wire format | JSON (request/response), UTF-8 |
| Auth (gateway) | Disabled — Bearer token accepted/ignored if present |

## 2. Endpoint matrix

| Method | Path | Used by Copilot | Purpose |
|--------|------|-----------------|---------|
| `POST` | `/v1/chat/completions` | Yes | Completions, streaming, agent turns |
| `POST` | `/v1/cursor/sessions/reset` | No | Clear ACP session resume (`?model=` optional) |
| `GET` | `/v1/models` | Optional | List Cursor models (proxied from upstream) |
| `GET` | `/v1/models/{id}` | Optional | Get one model |
| `GET` | `/health` | No | Ops / smoke test |

## 3. `POST /v1/chat/completions`

### Request fields

| Field | Type | Required | Notes |
|-------|------|----------|--------|
| `model` | string | Yes | Cursor model id (`composer-2.5`, `auto`, …) or `dummy-*` for offline tests |
| `messages` | array | Yes | Conversation history |
| `tools` | array | No | Forwarded to Cursor agent prompt |
| `tool_choice` | string \| object | No | Forwarded to Cursor |
| `stream` | boolean | No | Gateway adapts upstream JSON to SSE when `true` |
| `temperature` | number | No | Accepted, ignored by upstream |
| `max_tokens` | number | No | Accepted, ignored by upstream |
| `cursor` | object | No | Cursor-specific options (see below) |

#### `cursor` object

| Field | Type | Notes |
|-------|------|--------|
| `cwd` | string | Optional; not injected for Agent tool loops. Host is inference-only |
| `agentId` | string | Resume ACP session. Auto-injected from prior response when `session-resume` enabled |
| `mode` | string | `ask` \| `plan` \| `agent`. Forced to `ask` when `tools[]` is present |

#### Message object

| Field | Type | Roles | Notes |
|-------|------|-------|--------|
| `role` | string | all | `system` \| `user` \| `assistant` \| `developer` \| `tool` |
| `content` | string \| array \| null | | String, multimodal parts, or null when `tool_calls` set |
| `tool_calls` | array | assistant | OpenAI tool call objects |
| `tool_call_id` | string | tool | Links result to prior `tool_calls[].id` |
| `name` | string | tool (optional) | Function name |

### Example — Agent tool loop (files on remote VS Code)

```json
{
  "model": "composer-2.5",
  "messages": [
    { "role": "user", "content": "Create hello.txt in the workspace" }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "create_file",
        "parameters": {
          "type": "object",
          "properties": {
            "filePath": { "type": "string" },
            "content": { "type": "string" }
          },
          "required": ["filePath", "content"]
        }
      }
    }
  ]
}
```

Expected: `finish_reason: "tool_calls"`. VS Code runs `create_file` on the **remote** workspace.

### Example — chat with Cursor

```json
{
  "model": "composer-2.5",
  "messages": [
    { "role": "user", "content": "Summarize this repository" }
  ]
}
```

### Example — resume session

```json
{
  "model": "composer-2.5",
  "messages": [
    { "role": "user", "content": "Also update the changelog" }
  ],
  "cursor": {
    "agentId": "session-abc123"
  }
}
```

## 4. Response contract

### Non-streaming

`Content-Type: application/json`

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1784141900,
  "model": "composer-2.5",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  },
  "cursor": {
    "agentId": "session-abc123",
    "runId": "run-xyz",
    "status": "finished"
  }
}
```

Tool-call variant:

```json
{
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_1",
            "type": "function",
            "function": {
              "name": "create_file",
              "arguments": "{\"filePath\":\"/remote/ws/hello.txt\",\"content\":\"hi\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ]
}
```

Response headers: `x-cursor-agent-id`, `x-cursor-run-id` (when `cursor` block present).

### Streaming

When client sends `stream: true`, gateway returns `text/event-stream` adapted from the upstream JSON completion. Upstream `/ui/run` is always non-streaming.

## 5. Routing

| Model | Provider |
|-------|----------|
| `dummy-*` | Local dummy provider (offline smoke tests) |
| All other models | Cursor ACP via Playwright → `/ui/run` (ask mode when `tools[]` present) |

Host Cursor API is **LLM-only** (temp sandbox cwd). File/terminal actions run in **remote VS Code** via Copilot tools. `cursor.mode=ask` is forced for tool loops. New Chat / idle TTL / `POST /v1/cursor/sessions/reset` clear ACP resume.

## 6. Status codes and errors

| Code | When |
|------|------|
| `200` | Successful completion or stream |
| `400` | Invalid JSON body |
| `404` | Unknown model id |
| `502` | Cursor ACP / Playwright failure |
| `503` | Playwright session not ready |

Error body (matches Cursor API):

```json
{
  "error": {
    "message": "human readable detail",
    "type": "invalid_request_error",
    "code": "optional",
    "retryable": false
  }
}
```

`type` values: `invalid_request_error`, `cursor_agent_error`, `rate_limit_error`, `server_error`.

## 7. Copilot configuration mapping

| `chatLanguageModels.json` | HTTP / runtime |
|---------------------------|----------------|
| `apiType: "chat-completions"` | Uses this contract |
| `models[].url` | Gateway `POST` target |
| `models[].id` | Sent as request `model` (use Cursor model ids) |
| `models[].toolCalling: true` | Required for Agent mode in picker |
| `apiKey` | Sent as `Authorization: Bearer ...` (ignored by gateway) |
