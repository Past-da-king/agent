// One turn of the user's agent through OpenAI's Codex SDK on their ChatGPT plan.
// Sign-in: `codex login` run in-app leaves $HOME/.codex/auth.json (or CODEX_AUTH_JSON is pasted in).
// The phone's tools are an MCP server Codex reaches over localhost with a bearer token.
import { Codex } from "@openai/codex-sdk";
import { createInterface } from "node:readline";
import { mkdirSync, writeFileSync } from "node:fs";

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const rl = createInterface({ input: process.stdin });
const { prompt, system, resume, model, images = [], role = "main" } = JSON.parse(await new Promise((r) => rl.once("line", r)));
const isMain = role === "main";
rl.close();

const home = process.env.HOME;
mkdirSync(`${home}/.codex`, { recursive: true });
if (process.env.CODEX_AUTH_JSON) writeFileSync(`${home}/.codex/auth.json`, process.env.CODEX_AUTH_JSON, { mode: 0o600 });
// The main agent and its helpers run at the same time, each with its own MCP endpoint, so the phone's
// server is passed per run (--config) instead of through the shared config.toml.
writeFileSync(`${home}/.codex/config.toml`, "");

try {
  const codex = new Codex({ codexPathOverride: process.env.CODEX_BIN,
    config: { mcp_servers: { phone: { url: process.env.PHONE_MCP_URL, bearer_token_env_var: "PHONE_MCP_TOKEN" } } } });
  // Android's app sandbox is the boundary here; Codex's own Linux sandbox (landlock/seccomp) isn't available.
  // The main agent only orchestrates through the phone's tools: no shell writes, no network, no web search.
  const opts = isMain
    ? { workingDirectory: home, skipGitRepoCheck: true, sandboxMode: "read-only", networkAccessEnabled: false, webSearchEnabled: false, approvalPolicy: "never", model: model || undefined }
    : { workingDirectory: home, skipGitRepoCheck: true, sandboxMode: "danger-full-access", approvalPolicy: "never", model: model || undefined };
  const thread = resume ? codex.resumeThread(resume, opts) : codex.startThread(opts);
  // Photos the user sent go to Codex as local images alongside the text.
  const text = `${system}\n\n---\n\n${prompt}`;
  const input = images.length ? [{ type: "text", text }, ...images.map((path) => ({ type: "local_image", path }))] : text;
  const { events } = await thread.runStreamed(input);
  for await (const e of events) {
    if (e.type === "thread.started") out({ type: "session", id: e.thread_id });
    else if (e.type === "item.completed" && e.item.type === "agent_message") out({ type: "text", text: e.item.text });
    else if (e.type === "item.started" && e.item.type === "mcp_tool_call") out({ type: "tool", name: e.item.server === "phone" ? e.item.tool : `mcp__${e.item.server}__${e.item.tool}` });
    else if (e.type === "item.started" && e.item.type === "web_search") out({ type: "tool", name: "WebSearch", detail: e.item.query || "" });
    else if (e.type === "item.started" && e.item.type === "command_execution") out({ type: "tool", name: "Running a command", detail: e.item.command || "" });
    else if (e.type === "turn.completed") out({ type: "done", ok: true, usage: e.usage });
    else if (e.type === "turn.failed" || e.type === "error") out({ type: "error", message: e.error?.message || e.message || "Codex failed" });
  }
} catch (e) {
  out({ type: "error", message: String(e?.message || e) });
  process.exitCode = 1;
}
