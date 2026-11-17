# Use GPT-6.2 Astra in VS Code Copilot

## On the remote PC

1. Follow [Remote PC setup](REMOTE_SETUP.md#install-the-client) to build and start the relay. Keep the visible browser open. Confirm that `Invoke-RestMethod http://127.0.0.1:18080/readyz` reports `ready: true`.
2. In VS Code, open **Chat: Manage Language Models → Add Models → Custom Endpoint**. Enter the **local relay’s `BEARER_TOKEN`** in VS Code’s API Key prompt. VS Code stores it in secret storage. Keep the generated credential reference when editing the group. [VS Code language models](https://code.visualstudio.com/docs/agent-customization/language-models)
3. To change an existing credential, click the gear at the right of the **Custom Endpoint** group row and choose **Update API Key**. In **Open in Language Models (JSON)**, add the object from [copilot-model.json](copilot-model.json) to the group’s `models` array. Omit `requestHeaders`. Open the project folder, start a new **Agent** chat and select **Bridge GPT-6.2 Astra**.

```json
{
  "id": "codex/gpt-6.2-astra",
  "name": "Bridge GPT-6.2 Astra",
  "url": "http://127.0.0.1:18080/v1/chat/completions",
  "apiType": "chat-completions",
  "toolCalling": true,
  "vision": true,
  "contextWindow": 1000000,
  "maxOutputTokens": 32768,
  "modelOptions": { "temperature": null, "top_p": null }
}
```

The two token counts reproduce the client's configuration. A short streamed request with `max_completion_tokens: 32768` passed. The bridge accepts output hints and Codex controls generation. Neither the one-million-token context ceiling nor a 32,768-token generation has been measured.

## Images and client tools

The tested subscription route accepts inline PNG and JPEG data URLs, including images in tool results. The bridge validates format, dimensions and size. The combined limit is 2 MiB of decoded image files across the submitted history, with 16 million pixels per image. An invalid image returns HTTP 400 and an oversized image returns 413. Use a smaller screenshot or a fresh chat when a history of screenshots exceeds the budget.

Copilot 0.74.0's installed Chat Completions serializer encodes an image-bearing result as a `tool` message with `tool_call_id` and an array of `text` and/or `image_url` parts. The bridge checks the pending call ID and conversation. For an image result it replays the completed exchange in a fresh Codex turn, with the pixels attached as native image inputs. Text results resume the existing turn. File reads, edits and browser actions are dispatched to the IDE. Continue context headers are optional for this model. A host restart expires outstanding calls, so start a new chat after an upgrade.

## Optional browser tools on the remote PC

For the included acceptance fixture, install Chrome and uv on the IDE PC. Copy `scripts/copilot_browser_fixture.py` to the test workspace's `.vscode` folder. Merge the [example server entry](mcp.example.json) into `.vscode/mcp.json`. Run **MCP: List Servers → fixtureBrowser → Start Server**. The fixture opens its own visible browser and exposes one screenshot tool. It can interact only with the test page it created.

```json
{
  "servers": {
    "fixtureBrowser": {
      "type": "stdio",
      "command": "uv",
      "args": ["run", "--script", "${workspaceFolder}/.vscode/copilot_browser_fixture.py"]
    }
  }
}
```

To enable the one-click test, append `"--allow-test-click"` to `args` and restart the server. In the Agent tool picker enable the fixture tools. Keep manual permissions and approve individual calls. For general browser automation, a separate client-side server can use the same bridge. [Microsoft Playwright MCP](https://github.com/microsoft/playwright-mcp)

## Acceptance checks in Copilot

Verified on the bridge operator's PC using VS Code 1.146.0, GitHub Copilot Chat 0.74.0 and the public browser relay. Agent correctly identified a pasted two-color PNG, read different markers from two workspaces and created a matching receipt file. The client MCP fixture returned a red screenshot, then a blue screenshot after one explicitly requested click. The test used manual IDE permissions. Deployment on another physical PC and maximum token capacities still need independent verification.

- **Attachment**. In Paint, make an image with two differently colored halves. Attach the PNG to chat and ask “Which colors are on the left and right?” Check both against the original.
- **Active workspace**. Create two disposable folders. Put a different random value in `marker.txt` in each. Open the first folder, start an Agent chat, enable only its file-read tool and ask “Read marker.txt with the workspace tool and quote the value.” Repeat in a fresh chat in the other folder with the same model settings. Check the tool card and the value each time.
- **Browser**. Ask “Use fixture_screenshot and tell me the background color.” Expect red. Then ask “Use fixture_click once and tell me the new background color from the screenshot.” Approve the click and expect blue with the Change button disabled. To check cancellation, restart the fixture, decline the click in Copilot, and verify the page stays red.

To repeat the API acceptance suite, run the command below from this repository on the remote PC. It uses synthetic test data and subscription requests. The optional flag enables one click on a disposable test page. The harness exercises the API independently of the Copilot UI.

```powershell
uv run --no-project --with playwright --with pillow --with httpx --with python-dotenv python scripts/smoke_copilot.py --env-file .env --allow-test-click
```
