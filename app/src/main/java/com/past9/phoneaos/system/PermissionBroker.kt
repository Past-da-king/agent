package com.past9.phoneaos.system

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets the agent ask for an Android permission at the moment it needs one. The open activity
 * shows the system prompt; if the app is in the background the user gets a notification first.
 */
object PermissionBroker {
    class Request(val permissions: Array<String>, val result: CompletableDeferred<Boolean> = CompletableDeferred())
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending

    fun granted(context: Context, perms: Array<String>) = perms.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun request(context: Context, perms: Array<String>, onBackground: () -> Unit): Boolean {
        if (granted(context, perms)) return true
        val r = Request(perms); _pending.value = r
        onBackground()
        return try { withTimeoutOrNull(180_000) { r.result.await() } ?: false } finally { if (_pending.value === r) _pending.value = null }
    }

    fun answer(r: Request, ok: Boolean) { r.result.complete(ok); if (_pending.value === r) _pending.value = null }
}
