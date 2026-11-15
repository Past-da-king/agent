package com.past9.phoneaos.agent

import com.past9.phoneaos.data.AgentSettings
import com.past9.phoneaos.data.ModelRoute
import com.past9.phoneaos.data.PowerMode
import com.past9.phoneaos.data.Provider

/** A routed turn that cannot start or that the provider refused. Its message names the route and is shown to the user as it is. */
class RouteFailure(message: String) : Exception(message)

/**
 * Which provider and model a kind of turn runs on (GitHub issue #64). A pure lookup over what the user saved:
 * no route saved means the default power source, untouched. A route that cannot be used is never quietly swapped for
 * the default: that turn fails, with a line naming the route.
 */
object ModelRouting {
    sealed interface Pick {
        /** No route for this kind of turn (or the agent is not on an API key): do what the app always did. */
        data object Default : Pick

        /** Run the turn on this account and model. */
        data class Routed(
            val route: ModelRoute, val provider: Provider, val model: String,
            val key: String, val baseUrl: String, val allowHttp: Boolean,
        ) : Pick {
            /** "OpenAI · gpt-5": what an error says the turn ran on. */
            val label: String get() = "${provider.label} · $model"
        }

        /** The route is saved but cannot be used. [reason] is a sentence fragment. */
        data class Broken(val route: ModelRoute, val reason: String) : Pick {
            val message: String get() = "The ${name(route)} route can't run: $reason. Open Settings > Model routing to fix it, or reset it to the default."
        }
    }

    /** The route's name as the user sees it in Settings. */
    fun name(route: ModelRoute): String = when (route) {
        ModelRoute.CHAT -> "Chat"
        ModelRoute.ROUTINE -> "Routines"
        ModelRoute.WATCHER -> "Watchers"
    }

    /**
     * Routes pick between the user's API accounts, so they only apply while the agent runs on an API key; on a
     * subscription the turn goes through the subscription's own engine as before.
     *
     * @param apiKey the saved key for a provider, null or blank when there is none (the account is gone).
     */
    fun resolve(route: ModelRoute, settings: AgentSettings, apiKey: (Provider) -> String?): Pick {
        if (settings.mode != PowerMode.API_KEY) return Pick.Default
        val choice = settings.routes[route] ?: return Pick.Default
        val p = choice.provider
        val key = apiKey(p)?.takeIf { it.isNotBlank() } ?: return Pick.Broken(route, "there is no API key saved for ${p.label}")
        if (choice.model.isBlank()) return Pick.Broken(route, "no model is chosen")
        // The custom (self-hosted) address is kept once, for the main provider. Any other provider uses its own address.
        val mine = p == settings.provider
        val base = (if (mine) settings.baseUrl else "").ifBlank { p.baseUrl }.trim().trimEnd('/')
        val http = mine && settings.allowHttp
        HttpPolicy.blockReason(base, http)?.let { return Pick.Broken(route, "the server address for ${p.label} is not usable ($it)") }
        return Pick.Routed(route, p, choice.model, key, base, http)
    }

    /** What the user reads when the provider refused a routed turn: the route and its model, then the provider's reason. */
    fun failed(r: Pick.Routed, reason: String): RouteFailure = RouteFailure("The ${name(r.route)} route (${r.label}) failed: $reason")
}
