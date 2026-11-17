# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=1.9,<2", "playwright>=1.49,<2"]
# ///
"""Client-only MCP acceptance fixture. Run with uv run --script on the IDE PC."""
import argparse
from contextlib import asynccontextmanager

from mcp.server.fastmcp import FastMCP, Image
from mcp.types import ToolAnnotations
from playwright.async_api import async_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-test-click", action="store_true")
    parser.add_argument("--chrome", help="Path to a local Chrome or Edge executable")
    args = parser.parse_args()
    page = None

    @asynccontextmanager
    async def lifespan(server):
        nonlocal page
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=False, executable_path=args.chrome,
                                               channel=None if args.chrome else "chrome")
            try:
                page = await browser.new_page(viewport={"width": 620, "height": 240})
                await page.set_content("""<title>Copilot client acceptance test</title>
                    <body style="background:red"><button style="font:24px sans-serif" onclick="
                    document.body.style.background='blue';this.disabled=true">Change</button></body>""")
                yield {}
            finally:
                await browser.close()

    server = FastMCP("Client browser fixture", lifespan=lifespan, log_level="WARNING")

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, openWorldHint=False))
    async def fixture_screenshot() -> Image:
        """Return a PNG screenshot of the local test page."""
        return Image(data=await page.screenshot(), format="png")

    if args.allow_test_click:
        @server.tool(annotations=ToolAnnotations(readOnlyHint=False, openWorldHint=False))
        async def fixture_click() -> Image:
            """Click the test page's Change button once and return the new PNG screenshot."""
            button = page.get_by_role("button", name="Change")
            if not await button.is_enabled():
                raise ValueError("This fixture has already been clicked")
            await button.click()
            return Image(data=await page.screenshot(), format="png")

    server.run()


if __name__ == "__main__":
    main()
