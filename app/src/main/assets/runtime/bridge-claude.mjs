// Runs ONE turn of the user's agent through the Claude Agent SDK, using the user's own Claude
// subscription (signed in with `claude auth login` in-app, or CLAUDE_CODE_OAUTH_TOKEN).
// The phone's tools arrive over a localhost MCP server.
// Input: one JSON line on stdin {prompt, system, resume}. Output: JSON lines on stdout.
import { query } from "@anthropic-ai/claude-agent-sdk";
import { createInterface } from "node:readline";

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const rl = createInterface({ input: process.stdin });
const line = await new Promise((res) => rl.once("line", res));
rl.close();
const { prompt, system, resume, model, images = [] } = JSON.parse(line);
import { readFileSync } from "node:fs";
// Photos and scanned pages the user sent: Claude sees them as images in the same message.
const userInput = images.length === 0 ? prompt : (async function* () {
  yield {
    type: "user", parent_tool_use_id: null, session_id: "",
    message: { role: "user", content: [
      ...images.map((p) => ({ type: "image", source: { type: "base64", media_type: p.endsWith(".png") ? "image/png" : "image/jpeg", data: readFileSync(p).toString("base64") } })),
      { type: "text", text: prompt },
    ] },
  };
})();

try {
  for await (const m of query({
    prompt: userInput,
    options: {
      systemPrompt: system,
      resume: resume || undefined,
      model: model || undefined,
      cwd: process.env.HOME,
      // The node running this bridge, by absolute path: never depend on PATH lookups on Android.
      executable: process.execPath,
      pathToClaudeCodeExecutable: new URL("./node_modules/@anthropic-ai/claude-agent-sdk/cli.js", import.meta.url).pathname,
      env: { ...process.env },
      permissionMode: "bypassPermissions",
      // Productivity agent, not a coding agent: the phone's tools plus web search/fetch only.
      allowedTools: ["mcp__phone", "WebSearch", "WebFetch"],
      disallowedTools: ["Bash", "Edit", "Write", "NotebookEdit", "KillShell"],
      mcpServers: { phone: { type: "http", url: process.env.PHONE_MCP_URL, headers: { Authorization: `Bearer ${process.env.PHONE_MCP_TOKEN}` } } },
      maxTurns: 30,
    },
  })) {
    if (m.type === "system" && m.subtype === "init") out({ type: "session", id: m.session_id });
    else if (m.type === "assistant") {
      for (const b of m.message.content) {
        if (b.type === "text" && b.text.trim()) out({ type: "text", text: b.text });
        else if (b.type === "tool_use") {
          // A short, human hint of what the tool is doing (the query, the URL, the file).
          const i = b.input || {};
          const detail = i.query || i.url || i.q || i.prompt || i.description || i.path || i.file_path || i.title || i.subject || "";
          out({ type: "tool", name: b.name.replace(/^mcp__phone__/, ""), detail: String(detail).slice(0, 120) });
        }
      }
    } else if (m.type === "result") out({ type: "done", ok: m.subtype === "success", session: m.session_id, usage: m.usage, cost: m.total_cost_usd });
  }
} catch (e) {
  out({ type: "error", message: String(e?.message || e) });
  process.exitCode = 1;
}
