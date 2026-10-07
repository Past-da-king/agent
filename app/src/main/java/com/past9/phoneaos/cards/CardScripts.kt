package com.past9.phoneaos.cards

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.Watchers
import org.json.JSONObject

/**
 * Card scripts: Node scripts that refresh a card's data with no AI at all (prices, an API, counting emails
 * in a connected app). They run on the watcher engine and are scheduled as routines of kind "card"
 * (spec = minutes, prompt = the card id), which the Routines screen doesn't list as routines of their own.
 */
object CardScripts {
    const val KIND = "card"

    /**
     * Prepended to every card script: `apps(slug, args, account?)` runs a connected-app action through the
     * app's own Composio tools, read-only (anything that sends or changes something is refused because
     * nobody is there to approve it). Only present when the user allowed this card to read their apps.
     */
    private val preamble = """
globalThis.apps = async (slug, args = {}, account) => {
  const url = process.env.PHONE_MCP_URL, tok = process.env.PHONE_MCP_TOKEN;
  if (!url) throw new Error("This card isn't allowed to read your connected apps yet.");
  const rpc = async (method, params) => {
    const r = await fetch(url, { method: "POST", headers: { "content-type": "application/json", authorization: "Bearer " + tok }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) });
    const j = await r.json(); if (j.error) throw new Error(j.error.message); return j.result;
  };
  const names = (await rpc("tools/list", {})).tools.map((t) => t.name);
  const res = names.includes("apps_run")
    ? await rpc("tools/call", { name: "apps_run", arguments: { slug, arguments: args, ...(account ? { account } : {}) } })
    : await rpc("tools/call", { name: "COMPOSIO_MULTI_EXECUTE_TOOL", arguments: { tools: [{ tool_slug: slug, arguments: args }], ...(account ? { account } : {}) } });
  const text = (res.content || []).map((c) => c.text || "").join("\n");
  if (res.isError) throw new Error(text);
  try { return JSON.parse(text); } catch { return text; }
};
""".trimIndent()

    data class Result(val ok: Boolean, val data: String?, val log: String)

    /** Runs a card's script once (with an optional input from a search box) and returns its new data. */
    suspend fun run(context: Context, card: Card, input: String = "{}", script: String = card.script): Result {
        if (script.isBlank()) return Result(false, null, "This card has no script.")
        val g = App.graph(context)
        val env = mutableMapOf("CARD_DATA" to card.data, "CARD_INPUT" to input)
        if (card.appsAllowed) { env["PHONE_MCP_URL"] = g.runtime.mcp.url + "/card"; env["PHONE_MCP_TOKEN"] = g.settings.localToken() }
        val o = Watchers.execute(context, preamble + "\n" + script, "{}", env)
        val data = o.json?.opt("data")?.toString()
        return if (o.ok && data != null) Result(true, data, o.raw) else Result(false, null, o.raw.ifBlank { "The script's last line wasn't {\"data\": ...}" })
    }

    /** One scheduled refresh: new data, or the error kept on the card so it shows instead of silently going stale. */
    suspend fun tick(context: Context, t: TriggerRow): String {
        val store = App.graph(context).cards
        val card = store.get(t.prompt) ?: return "Card is gone."
        val r = run(context, card)
        store.update(card.id) { c -> if (r.ok) c.copy(data = r.data!!, headline = headlineFrom(r.data) ?: c.headline, updatedAt = System.currentTimeMillis(), error = "") else c.copy(error = r.log.takeLast(400)) }
        return if (r.ok) "Refreshed" else "Didn't refresh: " + r.log.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(120)
    }

    /** A script may return {"data": {..., "headline": "R 3,299 cheapest"}} to update the Home tile line. */
    fun headlineFrom(data: String?): String? = runCatching { JSONObject(data ?: return null).optString("headline").takeIf { it.isNotBlank() } }.getOrNull()

    /** Keep the schedule in step with the card: one "card" routine per card that has a script and an interval. */
    suspend fun schedule(context: Context, card: Card, onChange: suspend (TriggerRow?) -> Unit, onDelete: suspend (Long) -> Unit) {
        val dao = App.graph(context).db.triggers()
        val existing = dao.list().firstOrNull { it.kind == KIND && it.prompt == card.id }
        if (card.script.isBlank() || card.everyMinutes <= 0) { existing?.let { dao.delete(it.id); onDelete(it.id) }; return }
        val row = (existing ?: TriggerRow(name = "Card: ${card.title}", kind = KIND, spec = "", prompt = card.id))
            .copy(name = "Card: ${card.title}", spec = card.everyMinutes.coerceAtLeast(15).toString(), enabled = true)
        val id = dao.upsert(row)
        onChange(row.copy(id = id))
    }
}
