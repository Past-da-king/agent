package com.past9.phoneaos.data

import org.json.JSONObject

/**
 * The kinds of turn whose model the user can choose separately (GitHub issue #64). One turn is one provider and one
 * model from start to finish, so there is no route for a single tool step: a step runs on its turn's model.
 * Helpers and named agents pick their own model from the roster and are not routed.
 */
enum class ModelRoute {
    /** The user's conversation with the agent, including side threads and instructions sent by other apps. */
    CHAT,
    /** Routines, and the wake-ups the agent books. */
    ROUTINE,
    /** What runs when a watcher fires or has to be repaired. */
    WATCHER,
}

/** An account (a provider the user saved a key for) and one of its models. */
data class RouteChoice(val provider: Provider, val model: String) {
    fun toJson(): String = JSONObject().put("provider", provider.name).put("model", model).toString()

    companion object {
        /** Null for anything that is not a usable choice: a provider this build does not know, or no model. */
        fun fromJson(raw: String?): RouteChoice? = runCatching {
            val o = JSONObject(raw ?: return null)
            val p = Provider.valueOf(o.getString("provider"))
            val m = o.getString("model").trim()
            if (m.isEmpty()) null else RouteChoice(p, m)
        }.getOrNull()
    }
}
