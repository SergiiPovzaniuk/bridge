"""Live acceptance client. Uses a subscription and only disposable local fixtures."""
import argparse
import base64
from io import BytesIO
import json
from pathlib import Path
import secrets
from tempfile import TemporaryDirectory

from dotenv import dotenv_values
import httpx
from PIL import Image


MODEL = "codex/gpt-6.2-astra"
PATH = "/v1/chat/completions"


def image_part(data, mime="png"):
    encoded = base64.b64encode(data).decode("ascii")
    return {"type": "image_url", "image_url": {"url": f"data:image/{mime};base64,{encoded}"}}


def tool(name, description):
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False}}}


def turn(client, messages, tools=()):
    body = {"model": MODEL, "messages": messages, "stream": True, "max_completion_tokens": 32768}
    if tools:
        body["tools"] = tools
    calls = {}
    text = []
    done = False
    finish = None
    with client.stream("POST", PATH, json=body) as response:
        response.raise_for_status()
        for line in response.iter_lines():
            if line == "data: [DONE]":
                done = True
            elif line.startswith("data: "):
                chunk = json.loads(line[6:])
                if "error" in chunk:
                    raise RuntimeError(chunk["error"].get("code", "stream_error"))
                for choice in chunk["choices"]:
                    finish = choice["finish_reason"] or finish
                    delta = choice["delta"]
                    text.append(delta.get("content") or "")
                    for call in delta.get("tool_calls", []):
                        index = call["index"]
                        if index not in calls:
                            calls[index] = {"id": call["id"], "type": "function", "function": {"name": "", "arguments": ""}}
                        item = calls[index]
                        assert item["id"] and item["id"] == call.get("id", item["id"])
                        for key in ("name", "arguments"):
                            item["function"][key] += call.get("function", {}).get(key, "")
    assert done and finish == ("tool_calls" if calls else "stop")
    msg = {"role": "assistant", "content": "".join(text) or None}
    if calls:
        msg["tool_calls"] = [calls[i] for i in range(len(calls))]
    messages.append(msg)
    return msg


def expect_call(message, name):
    call, = message["tool_calls"]
    assert call["function"]["name"] == name, f"Expected {name}, received {call['function']['name']}"
    assert json.loads(call["function"]["arguments"]) == {}
    return call["id"]


def test_images(client):
    for mime in ("png", "jpeg"):
        colors = secrets.SystemRandom().sample(["red", "blue", "yellow", "green"], 2)
        img = Image.new("RGB", (320, 160), colors[0])
        img.paste(colors[1], (160, 0, 320, 160))
        buf = BytesIO()
        img.save(buf, format=mime)
        parts = [{"type": "text", "text": "Name the two solid colors from left to right, only two words."},
                 image_part(buf.getvalue(), mime)]
        answer = turn(client, [{"role": "user", "content": parts}])["content"].lower()
        assert 0 <= answer.find(colors[0]) < answer.find(colors[1]), "Incorrect color order"
        print(f"PASS {mime.upper()} ordered colors: {', '.join(colors)}", flush=True)


def test_workspaces(client):
    tools = [tool("read_marker", "Read marker.txt from the current client workspace")]
    with TemporaryDirectory() as tmp:
        for workspace in ("first", "second"):
            folder = Path(tmp, workspace)
            folder.mkdir()
            messages = [{"role": "user", "content": "Call read_marker exactly once. Reply with the returned marker."}]
            cid = expect_call(turn(client, messages, tools), "read_marker")
            nonce = secrets.token_hex(8)
            marker = folder / "marker.txt"
            marker.write_text(nonce, encoding="utf-8")
            messages.append({"role": "tool", "tool_call_id": cid, "content": marker.read_text(encoding="utf-8")})
            assert nonce in turn(client, messages, tools)["content"]
            print(f"PASS header-free streamed tool result in {workspace} client directory", flush=True)


def test_browser(client, executable):
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        browser = pw.chromium.launch(executable_path=executable, headless=False)
        page = browser.new_page(viewport={"width": 620, "height": 240})
        before, after = secrets.SystemRandom().sample(["red", "blue", "green", "yellow"], 2)
        page.set_content(f"""<body style="background:{before}"><button style="font:24px sans-serif"
            onclick="document.body.style.background='{after}';this.disabled=true">Change</button></body>""")
        messages = []
        for name, description, expected in [
                ("fixture_screenshot", "Return a screenshot of the test page", before),
                ("fixture_click", "Click Change once and return a screenshot of the updated page", after)]:
            tools = [tool(name, description)]
            messages.append({"role": "user", "content": f"Call {name} exactly once. From the returned screenshot, "
                             "name the dominant page background color. Reply with only the color."})
            cid = expect_call(turn(client, messages, tools), name)
            if name == "fixture_click":
                page.get_by_role("button", name="Change").click()
                assert page.locator("body").evaluate("(body) => body.style.background") == after
            messages.append({"role": "tool", "tool_call_id": cid, "content": [
                {"type": "text", "text": "Screenshot of the current browser page"}, image_part(page.screenshot())]})
            answer = turn(client, messages, tools)
            assert expected in (answer["content"] or "").lower(), f"{name}: expected {expected}, received {answer['content']!r}"
        browser.close()
        print("PASS client browser screenshots before and after one opt-in fixture click", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--allow-test-click", action="store_true", help="Enable one click in a new fixture-only browser")
    args = parser.parse_args()
    env = dotenv_values(args.env_file)
    with httpx.Client(base_url=args.base_url, headers={"Authorization": "Bearer " + env["BEARER_TOKEN"]}, timeout=310) as client:
        models = client.get("/v1/models")
        models.raise_for_status()
        assert MODEL in [m["id"] for m in models.json()["data"]]
        for data, status in [(b"invalid", 400), (bytes(2 * 1024 * 1024 + 1), 413)]:
            result = client.post(PATH, json={"model": MODEL, "stream": True,
                                 "messages": [{"role": "user", "content": [image_part(data)]}]})
            assert result.status_code == status and result.json()["error"]["code"] in ("invalid_image", "image_too_large")
        print("PASS catalog and JSON 400/413 before streaming", flush=True)
        assert "TEXT_OK" in turn(client, [{"role": "user", "content": "Reply with exactly TEXT_OK"}])["content"]
        print("PASS text streaming with output hint 32768", flush=True)
        test_images(client)
        test_workspaces(client)
        if args.allow_test_click:
            test_browser(client, env.get("BROWSER_EXECUTABLE_PATH"))


if __name__ == "__main__":
    main()
