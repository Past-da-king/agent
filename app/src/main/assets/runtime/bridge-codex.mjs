// One turn of the user's agent through OpenAI's Codex SDK on their ChatGPT plan.
// Sign-in: CODEX_AUTH_JSON (the user's ~/.codex/auth.json) is written to $HOME/.codex.
// The phone's tools are an MCP server Codex reaches over localhost with a bearer token.
import { Codex } from "@openai/codex-sdk";
import { createInterface } from "node:readline";
import { mkdirSync, writeFileSync } from "node:fs";

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const rl = createInterface({ input: process.stdin });
const { prompt, system, resume, model } = JSON.parse(await new Promise((r) => rl.once("line", r)));
rl.close();

const home = process.env.HOME;
mkdirSync(`${home}/.codex`, { recursive: true });
writeFileSync(`${home}/.codex/auth.json`, process.env.CODEX_AUTH_JSON, { mode: 0o600 });
writeFileSync(`${home}/.codex/config.toml`, [
  `[mcp_servers.phone]`, `url = "${process.env.PHONE_MCP_URL}"`, `bearer_token_env_var = "PHONE_MCP_TOKEN"`, ``,
].join("\n"));

try {
  const codex = new Codex({ codexPathOverride: process.env.CODEX_BIN });
  const opts = { workingDirectory: home, skipGitRepoCheck: true, sandboxMode: "read-only", model: model || undefined };
  const thread = resume ? codex.resumeThread(resume, opts) : codex.startThread(opts);
  const { events } = await thread.runStreamed(`${system}\n\n---\n\n${prompt}`);
  for await (const e of events) {
    if (e.type === "thread.started") out({ type: "session", id: e.thread_id });
    else if (e.type === "item.completed" && e.item.type === "agent_message") out({ type: "text", text: e.item.text });
    else if (e.type === "item.started" && e.item.type === "mcp_tool_call") out({ type: "tool", name: e.item.tool });
    else if (e.type === "turn.completed") out({ type: "done", ok: true, usage: e.usage });
    else if (e.type === "turn.failed" || e.type === "error") out({ type: "error", message: e.error?.message || e.message || "Codex failed" });
  }
} catch (e) {
  out({ type: "error", message: String(e?.message || e) });
  process.exitCode = 1;
}
