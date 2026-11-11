package com.past9.phoneaos.automation

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android intents, system-wide: the agent talks to ANY app on this phone the way apps talk to each other. Open a deep
 * link, share text into an app, start a navigation, fire a broadcast another app listens for, start a service.
 * MacroDroid and Tasker are presets on top of this (MacroDroid.kt), not special cases.
 *
 * Everything stays on the phone: an intent is a message between two apps on the same device.
 *
 * [IntentSpec] and [IntentRules] decide and describe; [IntentSender] is the only place that touches Android.
 */
data class IntentSpec(
    val kind: String,
    val action: String = "",
    val pkg: String = "",
    /** "package/class" or "package/.Class". */
    val component: String = "",
    val data: String = "",
    val mimeType: String = "",
    val categories: List<String> = emptyList(),
    /** Key to a typed value: String, Boolean, Int, Long, Float, Double, Uri, Array<String>, IntArray, LongArray, ArrayList<String>. */
    val extras: Map<String, Any> = emptyMap(),
    val flags: List<String> = emptyList(),
) {
    /** The app this is aimed at, when it names one: the package, or the component's package. */
    val targetPackage: String get() = pkg.ifBlank { component.substringBefore('/', "") }
}

object IntentRules {
    const val BROADCAST = "broadcast"
    const val ACTIVITY = "activity"
    const val SERVICE = "service"
    val KINDS = listOf(BROADCAST, ACTIVITY, SERVICE)

    /** Flag names the agent may use, so it never has to know the numbers. */
    val FLAGS: Map<String, Int> = linkedMapOf(
        "new_task" to Intent.FLAG_ACTIVITY_NEW_TASK,
        "clear_top" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
        "clear_task" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
        "single_top" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
        "no_history" to Intent.FLAG_ACTIVITY_NO_HISTORY,
        "exclude_from_recents" to Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        "grant_read" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
        "grant_write" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        "include_stopped" to Intent.FLAG_INCLUDE_STOPPED_PACKAGES,
        "foreground" to Intent.FLAG_RECEIVER_FOREGROUND,
    )

    /**
     * Actions that only put a screen in front of the user and leave the doing to them: view a link or a place, open an
     * app, the dialler with a number filled in, a search, a settings page. These need no approval.
     */
    private val OPENS = setOf(
        Intent.ACTION_VIEW, Intent.ACTION_MAIN, Intent.ACTION_DIAL, Intent.ACTION_SEARCH, Intent.ACTION_WEB_SEARCH,
        "android.intent.action.SHOW_ALARMS", "android.intent.action.SHOW_TIMERS",
    )

    fun opensOnly(spec: IntentSpec): Boolean =
        spec.kind == ACTIVITY && (spec.action in OPENS || spec.action.startsWith("android.settings.") || (spec.action.isBlank() && spec.component.isNotBlank()))

    // ---- reading what the agent sent ------------------------------------------------------------------------------

    class Parsed(val spec: IntentSpec?, val error: String?)

    fun parse(input: JSONObject): Parsed {
        val kind = input.optString("kind").trim().lowercase()
        if (kind !in KINDS) return Parsed(null, "kind must be one of broadcast, activity, service.")
        val extras = LinkedHashMap<String, Any>()
        input.optJSONObject("extras")?.let { e ->
            for (k in e.keys()) {
                val v = runCatching { extraValue(e.opt(k)) }.getOrElse { return Parsed(null, "extra \"$k\": ${it.message}") }
                    ?: return Parsed(null, "extra \"$k\" has no value.")
                extras[k] = v
            }
        }
        val spec = IntentSpec(
            kind = kind,
            action = input.optString("action").trim(),
            pkg = input.optString("package").trim(),
            component = input.optString("component").trim(),
            data = input.optString("data").trim(),
            mimeType = input.optString("mimeType").trim(),
            categories = strings(input.opt("category")) + strings(input.opt("categories")),
            extras = extras,
            flags = strings(input.opt("flags")),
        )
        return problem(spec)?.let { Parsed(null, it) } ?: Parsed(spec, null)
    }

    private fun strings(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }.filter { it.isNotEmpty() }
        is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** Why this intent cannot be sent as written, or null. */
    fun problem(spec: IntentSpec): String? = when {
        spec.kind !in KINDS -> "kind must be one of broadcast, activity, service."
        spec.action.isBlank() && spec.component.isBlank() -> "Give an action, or a component (package/class)."
        spec.component.isNotBlank() && '/' !in spec.component -> "component must be package/class, e.g. com.example.app/.MainActivity."
        spec.kind == SERVICE && spec.targetPackage.isBlank() -> "A service needs a package or a component: Android does not start a service without naming the app."
        spec.action.any { it.isWhitespace() } -> "The action has a space or a line break in it."
        spec.flags.any { it.lowercase() !in FLAGS && it.toIntOrNull() == null } ->
            "Unknown flag \"${spec.flags.first { it.lowercase() !in FLAGS && it.toIntOrNull() == null }}\". Known: ${FLAGS.keys.joinToString(", ")}."
        spec.extras.size > 40 -> "Too many extras (at most 40)."
        spec.extras.values.any { it is String && it.length > 100_000 } -> "An extra is too long."
        else -> null
    }

    /**
     * One extra, typed. A plain JSON value keeps its own type (text, true/false, a whole number, a decimal, a list of
     * text or of whole numbers). {"type": "long", "value": 5} forces one: string, int, long, float, double, boolean,
     * uri, string[], int[], long[], string_list.
     */
    fun extraValue(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> if (v.has("type")) typed(v.optString("type").trim().lowercase(), v.opt("value")) else v.toString()
        is String -> v
        is Boolean -> v
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is Number -> if (v is Double || v is Float || v is java.math.BigDecimal) v.toDouble() else v.toLong()
        is JSONArray -> {
            val items = (0 until v.length()).map { v.opt(it) }
            when {
                items.all { it is Int || (it is Long && it in Int.MIN_VALUE..Int.MAX_VALUE) } && items.isNotEmpty() -> IntArray(items.size) { (items[it] as Number).toInt() }
                items.all { it is Int || it is Long } && items.isNotEmpty() -> LongArray(items.size) { (items[it] as Number).toLong() }
                else -> Array(items.size) { items[it]?.toString().orEmpty() }
            }
        }
        else -> v.toString()
    }

    private fun typed(type: String, v: Any?): Any {
        fun text() = v?.takeIf { it != JSONObject.NULL }?.toString() ?: error("no value")
        fun list() = (v as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it)?.toString().orEmpty() } } ?: error("value must be a list")
        return when (type) {
            "string", "text" -> text()
            "int", "integer" -> text().trim().toIntOrNull() ?: error("not a whole number")
            "long" -> text().trim().toLongOrNull() ?: error("not a whole number")
            "float" -> text().trim().toFloatOrNull() ?: error("not a number")
            "double" -> text().trim().toDoubleOrNull() ?: error("not a number")
            "boolean", "bool" -> when (text().trim().lowercase()) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> error("not true or false") }
            "uri" -> Uri.parse(text().trim())
            "string[]", "string_array" -> list().toTypedArray()
            "int[]", "int_array" -> list().map { it.trim().toIntOrNull() ?: error("not whole numbers") }.toIntArray()
            "long[]", "long_array" -> list().map { it.trim().toLongOrNull() ?: error("not whole numbers") }.toLongArray()
            "string_list" -> ArrayList(list())
            else -> error("unknown type \"$type\"")
        }
    }

    // ---- building the real thing ----------------------------------------------------------------------------------

    fun build(spec: IntentSpec): Intent = Intent().apply {
        if (spec.action.isNotBlank()) action = spec.action
        val uri = spec.data.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        when {
            uri != null && spec.mimeType.isNotBlank() -> setDataAndType(uri, spec.mimeType)
            uri != null -> data = uri
            spec.mimeType.isNotBlank() -> type = spec.mimeType
        }
        if (spec.pkg.isNotBlank()) setPackage(spec.pkg)
        if (spec.component.isNotBlank()) {
            val p = spec.component.substringBefore('/')
            val c = spec.component.substringAfter('/')
            component = ComponentName(p, if (c.startsWith(".")) p + c else c)
        }
        spec.categories.forEach { addCategory(it) }
        spec.extras.forEach { (k, v) -> put(this, k, v) }
        spec.flags.forEach { f -> addFlags(FLAGS[f.lowercase()] ?: f.toInt()) }
        if (spec.kind == ACTIVITY) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun put(i: Intent, k: String, v: Any) {
        when (v) {
            is String -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            is Int -> i.putExtra(k, v)
            is Long -> i.putExtra(k, v)
            is Float -> i.putExtra(k, v)
            is Double -> i.putExtra(k, v)
            is Uri -> i.putExtra(k, v)
            is IntArray -> i.putExtra(k, v)
            is LongArray -> i.putExtra(k, v)
            is ArrayList<*> -> i.putStringArrayListExtra(k, ArrayList(v.map { it.toString() }))
            is Array<*> -> i.putExtra(k, Array(v.size) { v[it].toString() })
            else -> i.putExtra(k, v.toString())
        }
    }

    // ---- the allowlist and the question ---------------------------------------------------------------------------

    /** One allowed pair: an action and an app. "*" (or nothing) for the app means any app. */
    data class Pair(val action: String, val pkg: String)

    /** The allowlist as typed: one pair per line, "action | package". Blank lines and lines starting with # are skipped. */
    fun parseAllowlist(text: String?): List<Pair> = text.orEmpty().lines().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split('|', limit = 2).map { it.trim() }
            Pair(parts[0], parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "*")
        }.filter { it.action.isNotEmpty() }.distinct()

    /** The line the user adds to stop being asked about this one. */
    fun pairLine(spec: IntentSpec): String = "${spec.action.ifBlank { spec.component }} | ${spec.targetPackage.ifBlank { "*" }}"

    fun allowed(spec: IntentSpec, allowlist: List<Pair>): Boolean {
        val key = spec.action.ifBlank { spec.component }
        return allowlist.any { it.action == key && (it.pkg == "*" || it.pkg.equals(spec.targetPackage, ignoreCase = true)) }
    }

    /** Exactly what will be sent, for the approval card. */
    fun describe(spec: IntentSpec): String = buildString {
        append(when (spec.kind) { ACTIVITY -> "Open a screen"; SERVICE -> "Start a service"; else -> "Send a broadcast" })
        append(if (spec.targetPackage.isNotBlank()) " in ${spec.targetPackage}" else if (spec.kind == BROADCAST) " to any app listening" else "")
        if (spec.action.isNotBlank()) append("\nAction: ").append(spec.action)
        if (spec.component.isNotBlank()) append("\nComponent: ").append(spec.component)
        if (spec.data.isNotBlank()) append("\nData: ").append(spec.data)
        if (spec.mimeType.isNotBlank()) append("\nType: ").append(spec.mimeType)
        if (spec.categories.isNotEmpty()) append("\nCategory: ").append(spec.categories.joinToString(", "))
        spec.extras.forEach { (k, v) -> append("\n").append(k).append(": ").append(show(v).take(300)) }
        append("\nTo stop being asked, add this line in Settings > Automation > Allowed app intents:\n").append(pairLine(spec))
    }

    fun show(v: Any): String = when (v) {
        is IntArray -> v.joinToString(", ", "[", "]")
        is LongArray -> v.joinToString(", ", "[", "]")
        is Array<*> -> v.joinToString(", ", "[", "]")
        else -> v.toString()
    }

    sealed interface Gate {
        data object Go : Gate
        data class Stop(val reason: String) : Gate
    }

    /**
     * The safety rule. An intent that only opens a screen goes. One whose action and app are on the user's allowlist
     * goes. Anything else can change, send or spend, so the user says yes first; no answer and a decline both stop it.
     */
    suspend fun gate(spec: IntentSpec, allowlist: List<Pair>, title: String = "", ask: suspend (String) -> String?): Gate {
        if (opensOnly(spec) || allowed(spec, allowlist)) return Gate.Go
        val what = title.ifBlank {
            when (spec.kind) { ACTIVITY -> "Open ${spec.targetPackage.ifBlank { "an app" }}"; SERVICE -> "Start a service in ${spec.targetPackage}"; else -> "Broadcast ${spec.action.substringAfterLast('.')} to ${spec.targetPackage.ifBlank { "any app" }}" }
        }
        return when (ask("APPROVAL|$what|${describe(spec)}")) {
            "Approve" -> Gate.Go
            null -> Gate.Stop("failed: needs the user's yes and nobody answered. Not sent. To run it without asking, they add \"${pairLine(spec)}\" to Settings > Automation > Allowed app intents.")
            else -> Gate.Stop("failed: the user declined. Not sent.")
        }
    }
}

/** Sends an [IntentSpec] on this phone. Answers "sent", "sent: with a note" or "failed: why". */
class IntentSender(private val context: Context, private val inFront: () -> Boolean = { com.past9.phoneaos.system.ChatOnScreen.resumed }) {
    fun send(spec: IntentSpec): String = runCatching {
        val i = IntentRules.build(spec)
        when (spec.kind) {
            IntentRules.BROADCAST -> { context.sendBroadcast(i); "sent" }
            IntentRules.SERVICE -> startService(i)
            else -> startActivity(spec, i)
        }
    }.getOrElse { "failed: ${it.message ?: it::class.java.simpleName}" }

    private fun startService(i: Intent): String {
        val started = try { context.startService(i) } catch (e: IllegalStateException) {
            // Android 8+ refuses a plain start while we are in the background; the foreground form is allowed.
            androidx.core.content.ContextCompat.startForegroundService(context, i); return "sent"
        }
        return if (started == null) "failed: no such service on this phone" else "sent"
    }

    private fun startActivity(spec: IntentSpec, i: Intent): String {
        if (spec.component.isBlank() && i.resolveActivity(context.packageManager) == null)
            return "failed: no app on this phone can open that" + (if (spec.pkg.isNotBlank()) " (is ${spec.pkg} installed, and does it accept this action and data?)" else "")
        // Android 10+ does not let an app in the background put a screen up, unless it may draw over other apps.
        if (inFront() || runCatching { android.provider.Settings.canDrawOverlays(context) }.getOrDefault(false)) {
            context.startActivity(i); return "sent"
        }
        val label = runCatching { i.resolveActivity(context.packageManager)?.packageName?.let { p -> context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() } }.getOrNull()
        val id = 7_300 + (spec.hashCode() and 0xff)
        val pi = PendingIntent.getActivity(context, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = runCatching { com.past9.phoneaos.system.Identity.statIcon(context, com.past9.phoneaos.App.graph(context).settings.state.value.mascot) }.getOrDefault(android.R.drawable.ic_menu_send)
        val n = NotificationCompat.Builder(context, com.past9.phoneaos.system.AndroidPhone.CH_MESSAGES).setSmallIcon(icon)
            .setContentTitle("Tap to open ${label ?: "it"}").setContentText(spec.data.ifBlank { spec.action })
            .setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
        return "sent: as a notification the user taps, because Android does not let an app open a screen from the background. " +
            "It opens at once when the Agent app is on screen, or when Agent is allowed to display over other apps."
    }
}

/** What Settings holds for intents out. */
interface IntentSettings {
    fun intentAllowlist(): List<IntentRules.Pair>
}

/** android_intent: talk to any app on this phone. */
class AndroidIntentTool(
    private val settings: IntentSettings,
    private val send: suspend (IntentSpec) -> String,
) : Tool {
    constructor(context: Context, settings: IntentSettings) : this(settings, { IntentSender(context).send(it) })

    override val spec = ToolSpec("android_intent",
        "Talk to ANY app on this phone with an Android intent: open a deep link or a place, start a navigation, share text or a file into an app, open a settings page, " +
            "fire a broadcast an app listens for, start a service. Use android_apps first to see which apps are installed and what they accept. " +
            "To play music or a video do NOT use this: use play_media (and media_control to pause, skip or see what is playing). " +
            "Intents that only open a screen (VIEW, MAIN, DIAL, SEARCH, settings pages) go at once; anything else asks the user first unless its action and app are on their allowlist. " +
            "Examples: {kind:'activity', action:'android.intent.action.VIEW', data:'google.navigation:q=Sandton+City', package:'com.google.android.apps.maps'}; " +
            "{kind:'activity', action:'android.intent.action.SEND', mimeType:'text/plain', package:'com.whatsapp', extras:{'android.intent.extra.TEXT':'hello'}}; " +
            "{kind:'broadcast', action:'com.example.DO_THING', extras:{count:3, on:true}}. Returns 'sent' or 'failed: why'. The other app cannot report back.",
        schema(listOf("kind"),
            "kind" to enumOf("How it is delivered", IntentRules.BROADCAST, IntentRules.ACTIVITY, IntentRules.SERVICE),
            "action" to str("The intent action, e.g. android.intent.action.VIEW"),
            "package" to str("Only this app, by package name, e.g. com.google.android.apps.maps"),
            "component" to str("One exact component, package/class, e.g. com.example.app/.ShareActivity"),
            "data" to str("The data URI: a link, geo:, tel:, mailto:, content:// or an app's own scheme"),
            "mimeType" to str("The MIME type, e.g. text/plain or image/*"),
            "category" to strList("Categories, e.g. android.intent.category.BROWSABLE"),
            "extras" to JSONObject().put("type", "object").put("description",
                "Extras by key. A plain value keeps its type (text, true/false, whole number, decimal, list). Force a type with {type, value}: string, int, long, float, double, boolean, uri, string[], int[], long[], string_list"),
            "flags" to strList("Flags by name: ${IntentRules.FLAGS.keys.joinToString(", ")}")))

    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val parsed = IntentRules.parse(input)
        val s = parsed.spec ?: return "failed: ${parsed.error}"
        when (val g = IntentRules.gate(s, settings.intentAllowlist()) { q -> ctx.ask(q, listOf("Approve", "Decline")) }) {
            is IntentRules.Gate.Stop -> return g.reason
            IntentRules.Gate.Go -> Unit
        }
        val out = send(s)
        runCatching { ctx.activity("Intent: ${s.action.substringAfterLast('.').ifBlank { s.component }}${if (s.targetPackage.isNotBlank()) " to ${s.targetPackage}" else ""} (${out.substringBefore(':')})",
            JSONObject().put("tool", "android_intent").put("action", s.action).put("package", s.targetPackage).put("result", out)) }
        return out
    }
}

/**
 * android_apps: what is installed and what it accepts. Read-only, so it never asks.
 *   no arguments, or query   the installed apps you can open, name and package
 *   package                  one app: how to open it, what it accepts from the common intents, its exported parts
 *   intent                   which apps would handle this intent
 */
class AndroidAppsTool(private val context: Context) : Tool {
    override val spec = ToolSpec("android_apps",
        "See what android_intent can call on this phone. With nothing, or a query: the installed apps (name and package). " +
            "With package: that app's launcher, which common intents it accepts (links, geo, share text or images, dial, mail and more) and its exported activities, receivers and services. " +
            "With intent {kind, action, data, mimeType, category}: the apps that would handle it. Read-only.",
        schema(emptyList(),
            "query" to str("Filter the app list by a word in the name or package"),
            "package" to str("Describe this one app"),
            "intent" to JSONObject().put("type", "object").put("description", "An intent as android_intent takes it: list the apps that would handle it")))

    private val pm get() = context.packageManager

    override suspend fun run(input: JSONObject, ctx: ToolContext): String = runCatching {
        input.optJSONObject("intent")?.let { return@runCatching handlers(it) }
        input.optString("package").trim().takeIf { it.isNotEmpty() }?.let { return@runCatching describe(it) }
        list(input.optString("query").trim())
    }.getOrElse { "failed: ${it.message ?: it::class.java.simpleName}" }

    private fun label(pkg: String): String = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun list(query: String): String {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName }.distinct()
            .map { label(it) to it }.filter { (n, p) -> query.isEmpty() || n.contains(query, true) || p.contains(query, true) }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return if (query.isEmpty()) "No apps found." else "No installed app matches \"$query\"."
        return "${apps.size} app${if (apps.size == 1) "" else "s"}:\n" + apps.take(300).joinToString("\n") { (n, p) -> "$n ($p)" } +
            "\nCall android_apps with package to see what one accepts."
    }

    private fun describe(pkg: String): String {
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        }.getOrNull() ?: return "failed: $pkg is not installed on this phone."
        val out = StringBuilder("${label(pkg)} ($pkg) ${info.versionName.orEmpty()}")
        pm.getLaunchIntentForPackage(pkg)?.component?.let { out.append("\nOpens with: {kind:'activity', component:'${it.flattenToShortString()}'}") }
        val accepts = AppProbes.ALL.mapNotNull { probe ->
            val i = IntentRules.build(probe.spec.copy(pkg = pkg))
            val hits = if (probe.spec.kind == IntentRules.BROADCAST) pm.queryBroadcastReceivers(i, 0).map { it.activityInfo.name }
                else pm.queryIntentActivities(i, 0).map { it.activityInfo.name }
            hits.takeIf { it.isNotEmpty() }?.let { "${probe.what}: ${probe.example}" }
        }
        if (accepts.isNotEmpty()) out.append("\nAccepts:\n").append(accepts.joinToString("\n") { "- $it" })
        fun short(n: String) = if (n.startsWith(pkg)) n.removePrefix(pkg) else n
        info.activities?.filter { it.exported }?.map { short(it.name) }?.takeIf { it.isNotEmpty() }?.let {
            out.append("\nExported activities (${it.size}): ").append(it.take(40).joinToString(", "))
        }
        info.receivers?.filter { it.exported }?.map { short(it.name) }?.takeIf { it.isNotEmpty() }?.let {
            out.append("\nExported receivers (${it.size}): ").append(it.take(30).joinToString(", "))
        }
        info.services?.filter { it.exported }?.map { short(it.name) }?.takeIf { it.isNotEmpty() }?.let {
            out.append("\nExported services (${it.size}): ").append(it.take(30).joinToString(", "))
        }
        out.append("\nAndroid does not publish an app's full list of actions. For one this does not show, check the app's own documentation, or ask with android_apps intent.")
        return out.toString()
    }

    private fun handlers(json: JSONObject): String {
        val parsed = IntentRules.parse(if (json.has("kind")) json else JSONObject(json.toString()).put("kind", IntentRules.ACTIVITY))
        val s = parsed.spec ?: return "failed: ${parsed.error}"
        val i = IntentRules.build(s)
        val hits = when (s.kind) {
            IntentRules.BROADCAST -> pm.queryBroadcastReceivers(i, 0).map { it.activityInfo.packageName to it.activityInfo.name }
            IntentRules.SERVICE -> pm.queryIntentServices(i, 0).map { it.serviceInfo.packageName to it.serviceInfo.name }
            else -> pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.activityInfo.name }
        }
        if (hits.isEmpty()) return "No app on this phone handles that" + (if (s.kind == IntentRules.BROADCAST) " through a receiver in its manifest. An app can still listen while it is running (MacroDroid and Tasker do), and Android cannot list those." else ".")
        return "${hits.size} handler${if (hits.size == 1) "" else "s"}:\n" + hits.take(60).joinToString("\n") { (p, c) -> "${label(p)}: $p/${if (c.startsWith(p)) c.removePrefix(p) else c}" }
    }
}

/** The common intents an app is tried against, so the agent learns what it accepts without guessing. */
object AppProbes {
    class Probe(val what: String, val example: String, val spec: IntentSpec)

    private fun view(what: String, data: String) = Probe(what, "{kind:'activity', action:'android.intent.action.VIEW', data:'$data'}",
        IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_VIEW, data = data))
    private fun share(what: String, mime: String) = Probe(what, "{kind:'activity', action:'android.intent.action.SEND', mimeType:'$mime', extras:{...}}",
        IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_SEND, mimeType = mime))

    val ALL: List<Probe> = listOf(
        view("Web links", "https://example.com"),
        view("Map places (geo:)", "geo:0,0?q=Sandton+City"),
        view("Turn-by-turn navigation", "google.navigation:q=Sandton+City"),
        view("Phone numbers (tel:)", "tel:0123456789"),
        view("SMS (sms:)", "sms:0123456789"),
        view("Email (mailto:)", "mailto:someone@example.com"),
        view("Play Store pages (market:)", "market://details?id=com.example"),
        share("Shared text", "text/plain"),
        share("Shared images", "image/*"),
        share("Shared video", "video/*"),
        share("Shared files", "application/pdf"),
        Probe("Dial a number", "{kind:'activity', action:'android.intent.action.DIAL', data:'tel:0123456789'}", IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_DIAL, data = "tel:0123456789")),
        Probe("Write to someone (SENDTO)", "{kind:'activity', action:'android.intent.action.SENDTO', data:'smsto:0123456789'}", IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_SENDTO, data = "smsto:0123456789")),
        Probe("Web search", "{kind:'activity', action:'android.intent.action.WEB_SEARCH', extras:{query:'...'}}", IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_WEB_SEARCH)),
        Probe("Selected text (PROCESS_TEXT)", "{kind:'activity', action:'android.intent.action.PROCESS_TEXT', mimeType:'text/plain', extras:{'android.intent.extra.PROCESS_TEXT':'...'}}",
            IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_PROCESS_TEXT, mimeType = "text/plain")),
        Probe("Set an alarm", "{kind:'activity', action:'android.intent.action.SET_ALARM', extras:{'android.intent.extra.alarm.HOUR':7, 'android.intent.extra.alarm.MINUTES':30}}",
            IntentSpec(IntentRules.ACTIVITY, "android.intent.action.SET_ALARM")),
        Probe("Set a timer", "{kind:'activity', action:'android.intent.action.SET_TIMER', extras:{'android.intent.extra.alarm.LENGTH':300}}",
            IntentSpec(IntentRules.ACTIVITY, "android.intent.action.SET_TIMER")),
        Probe("Add a calendar event", "{kind:'activity', action:'android.intent.action.INSERT', data:'content://com.android.calendar/events', extras:{title:'...'}}",
            IntentSpec(IntentRules.ACTIVITY, Intent.ACTION_INSERT, data = "content://com.android.calendar/events")),
        Probe("Play from a search", "{kind:'activity', action:'android.media.action.MEDIA_PLAY_FROM_SEARCH', extras:{query:'...'}}",
            IntentSpec(IntentRules.ACTIVITY, "android.media.action.MEDIA_PLAY_FROM_SEARCH")),
    )
}
