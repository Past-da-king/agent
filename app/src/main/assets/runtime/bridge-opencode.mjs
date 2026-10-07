// One turn of the user's agent through OpenCode on their OpenCode plan (Zen/Go key).
// Starts a local OpenCode server, points it at the phone's MCP tools, sends the prompt.
import { createOpencode } from "@opencode-ai/sdk";
import { createInterface } from "node:readline";

const out = (o) => process.stdout.write(JSON.stringify(o) + "\n");
const rl = createInterface({ input: process.stdin });
const { prompt, system, resume, model, role = "main" } = JSON.parse(await new Promise((r) => rl.once("line", r)));
const isMain = role === "main";
rl.close();
process.env.PATH = `${process.env.PATH}`;

try {
  const { client, server } = await createOpencode({
    hostname: "127.0.0.1", port: 0,
    config: {
      model: model || "opencode/big-pickle",
      provider: { opencode: { options: { apiKey: process.env.OPENCODE_API_KEY } } },
      mcp: { phone: { type: "remote", url: process.env.PHONE_MCP_URL, headers: { Authorization: `Bearer ${process.env.PHONE_MCP_TOKEN}` } } },
      // The main agent only orchestrates through the phone's tools; helpers may also fetch the web.
      tools: isMain ? { bash: false, edit: false, write: false, patch: false, webfetch: false, task: false, read: false, grep: false, glob: false, list: false }
                    : { bash: false, edit: false, write: false, patch: false, task: false },
    },
  });
  const session = resume ? { id: resume } : (await client.session.create({ body: { title: "phone" } })).data;
  out({ type: "session", id: session.id });
  const res = await client.session.prompt({ path: { id: session.id }, body: { system, parts: [{ type: "text", text: prompt }] } });
  for (const p of res.data?.parts ?? []) {
    if (p.type === "text" && p.text?.trim()) out({ type: "text", text: p.text });
    else if (p.type === "tool") out({ type: "tool", name: String(p.tool).replace(/^phone_/, "") });
  }
  out({ type: "done", ok: true });
  server.close();
} catch (e) {
  out({ type: "error", message: String(e?.message || e) });
  process.exitCode = 1;
}
