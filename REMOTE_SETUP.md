# Remote PC setup

For Copilot Agent mode with GPT-6.2 Astra, use the updated relay JAR and the [Copilot setup guide](COPILOT.md). Its Custom Endpoint uses the loopback Chat Completions URL and the local relay bearer key.

## Prerequisites

1. Obtain these values from the host-PC operator:
   - Host bridge URL, for example `https://info.iba-expert.uk/`
   - Host `RELAY_TOKEN`
2. Install Git with Git LFS, Java 21 or newer, VS Code with GitHub Copilot Chat or Continue, and Chrome or Edge.
3. Confirm the host is reachable:

```powershell
Invoke-RestMethod https://info.iba-expert.uk/healthz
```

The response must contain `"status": "ok"`.

## Install the client

1. Clone the remote-PC repository:

```powershell
git clone https://github.com/SergiiPovzaniuk/bridge.git
cd bridge
```

2. Verify Java:

```powershell
java -version
```

The major version must be 21 or newer.

3. Download the prebuilt client and copy it into the launcher's directory. Stop any running client before replacing its JAR.

```powershell
git lfs pull
New-Item -ItemType Directory -Force target | Out-Null
Copy-Item -Force .\dist\open-ai-api-0.0.1-SNAPSHOT.jar .\target\
```

The published JAR includes its Java dependencies. Chrome or Edge runs separately. To build the JAR from source, use a JDK and run `.\mvnw.cmd clean package`.

4. Create the local configuration:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set:

```dotenv
BRIDGE_URL=https://info.iba-expert.uk/
RELAY_TOKEN=the-exact-token-from-the-host
BEARER_TOKEN=choose-a-long-local-token
RELAY_HEADLESS=false
PLAYWRIGHT_BROWSERS_PATH=
BROWSER_EXECUTABLE_PATH=C:\Program Files\Google\Chrome\Application\chrome.exe
```

`BRIDGE_URL` and `RELAY_TOKEN` must match the host. The browser path must point to an already installed browser. No browser is downloaded automatically.

5. Start the client:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\run-client.ps1
```

Keep this terminal and the visible browser open.

6. Verify readiness in another PowerShell terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:18080/readyz
Invoke-RestMethod http://127.0.0.1:18080/v1/models -Headers @{ Authorization = 'Bearer choose-a-long-local-token' }
```

`readyz` must return `"ready": true`.

## Configure Continue 2.1.0

1. Create Continue's configuration directory:

```powershell
New-Item "$env:USERPROFILE\.continue" -ItemType Directory -Force
Copy-Item .\continue-config.yaml "$env:USERPROFILE\.continue\config.yaml"
notepad "$env:USERPROFILE\.continue\config.yaml"
```

2. In every model entry, replace:
   - `continue-local` with the `BEARER_TOKEN` from `.env`
   - `C:\path\to\remote\workspace` with the absolute path of the project opened in VS Code on the remote PC

Example:

```yaml
apiKey: choose-a-long-local-token
requestOptions:
  timeout: 300000
  headers:
    X-Continue-Workspace: 'C:\Users\restricted-user\Documents\MyProject'
    X-Continue-OS: windows
    X-Continue-Shell: powershell
```

Do not add a static `X-Conversation-Id`.

3. Restart VS Code.
4. Open the same project folder specified by `X-Continue-Workspace`.
5. Open Continue and select:
   - `:agent` for file edits and terminal commands
   - `:plan` for read-only planning
   - `:ask` for questions

All tools and file changes execute on the remote PC. Communication between PCs uses HTTP long polling only.

## Start it later

```powershell
cd C:\path\to\bridge
Set-ExecutionPolicy -Scope Process Bypass
.\run-client.ps1
```
