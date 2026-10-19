// One turn of the user's agent through Google Antigravity CLI on their own Antigravity account.
// The binary is downloaded on demand (runtime pack "antigravity") and run from filesDir/runtime/bin.
// Every run gets its own HOME so concurrent helpers can each point agy at their own phone MCP endpoint.
import { createInterface } from "node:readline";
import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const rl = createInterface({ input: process.stdin });
const { prompt, system, resume, model, images = [], role = "main" } = JSON.parse(await new Promise((r) => rl.once("line", r)));
rl.close();

const home = process.env.HOME;
const runHome = join(home, `agy-${process.pid}`);
const src = join(home, ".gemini");
const dst = join(runHome, ".gemini");
const runToken = join(dst, "antigravity-cli", "antigravity-oauth-token");
const sharedToken = join(src, "antigravity-cli", "antigravity-oauth-token");

try {
  mkdirSync(join(dst, "antigravity-cli"), { recursive: true });
  mkdirSync(join(dst, "config"), { recursive: true });
  for (const f of ["installation_id", "google_accounts.json", "oauth_creds.json"]) {
    try { cpSync(join(src, f), join(dst, f)); } catch {}
  }
  for (const f of ["antigravity-oauth-token", "installation_id", "settings.json", "jetski_state.pbtxt"]) {
    try { cpSync(join(src, "antigravity-cli", f), join(dst, "antigravity-cli", f)); } catch {}
  }

  // The phone's tools are an MCP server on localhost. The CLI has read both paths across versions.
  const mcp = JSON.stringify({ mcpServers: { phone: {
    serverUrl: process.env.PHONE_MCP_URL,
    headers: { Authorization: `Bearer ${process.env.PHONE_MCP_TOKEN}` },
    disabled: false,
    timeoutSeconds: 15,
  } } }, null, 2);
  writeFileSync(join(dst, "config", "mcp_config.json"), mcp);
  writeFileSync(join(dst, "antigravity-cli", "mcp_config.json"), mcp);
} catch (e) {
  out({ type: "error", message: `Could not prepare the Antigravity runtime: ${e?.message || e}` });
  process.exit(1);
}

const text = `${system}\n\n---\n\n${prompt}` +
  "\n\nYou are running inside the user's Agent app on their phone. Use the `phone` MCP server tools for the user's memory, tasks, browser, connected apps, files and questions. Do not use shell commands to do what a phone tool can do." +
  (images.length ? `\n\nThe user attached these files: ${images.join(", ")}` : "");

const args = ["-p", text, "--output-format", "stream-json", "--dangerously-skip-permissions", "--print-timeout", "20m"];
if (model) args.push("--model", model);
if (resume) args.push("--conversation", resume);

const child = spawn(process.env.ANTIGRAVITY_BIN || "antigravity", args, {
  cwd: runHome,
  env: { ...process.env, HOME: runHome },
  stdio: ["ignore", "pipe", "inherit"],
});

let said = "";
let finished = false;
const lines = createInterface({ input: child.stdout });
lines.on("line", (line) => {
  let e;
  try { e = JSON.parse(line); } catch { return; }
  if (e.event === "init") {
    out({ type: "session", id: e.conversation_id || "" });
  } else if (e.event === "step_update") {
    const s = e.step_update || {};
    if (s.step_type === "tool" && s.state === "ACTIVE") {
      out({ type: "tool", name: s.tool_name || "tool", detail: JSON.stringify(s.tool_info?.parameters || {}).slice(0, 300) });
    } else if (s.step_type === "agent_response" && s.state === "ACTIVE" && s.text_delta) {
      said += s.text_delta;
    }
  } else if (e.event === "result") {
    const r = e.result || {};
    finished = true;
    const answer = said || r.response || "";
    if (answer) out({ type: "text", text: answer });
    if (r.status && r.status !== "SUCCESS") out({ type: "error", message: r.error || "Antigravity failed" });
    out({ type: "done", ok: r.status === "SUCCESS", usage: r.usage });
  }
});

function cleanup() {
  try { if (existsSync(runToken)) cpSync(runToken, sharedToken); } catch {}
  try { rmSync(runHome, { recursive: true, force: true }); } catch {}
}

child.on("error", (e) => { out({ type: "error", message: String(e?.message || e) }); cleanup(); });
child.on("exit", (code) => {
  if (!finished && code !== 0) out({ type: "error", message: `Antigravity exited ${code}` });
  cleanup();
});
for (const sig of ["SIGTERM", "SIGINT"]) process.on(sig, () => { try { child.kill("SIGKILL"); } catch {} cleanup(); process.exit(0); });
