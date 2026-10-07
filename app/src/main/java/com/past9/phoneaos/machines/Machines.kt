package com.past9.phoneaos.machines

import com.past9.phoneaos.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** How the agent signs in to a machine. */
enum class MachineAuth(val label: String) { PASSWORD("Password"), KEY("Private key"), NEW_KEY("New key") }

/**
 * A computer or server the user has credentials for (a VPS, a university server, a PC at home).
 * The agent runs commands there and hands it the heavy work. Secrets live in encrypted storage, not here.
 */
data class Machine(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: MachineAuth = MachineAuth.PASSWORD,
    /** Reach it through another machine first (a gateway or bastion), by id. */
    val via: String? = null,
    /** Run commands that change things without asking each time. Off by default. */
    val trusted: Boolean = false,
    /** SHA256 fingerprint of the host key, pinned on first connect. A change refuses the connection. */
    val hostKey: String? = null,
    /** What the last test found, e.g. "Linux 6.8 · 16 cores". */
    val about: String = "",
) {
    val address get() = "$user@$host" + if (port != 22) ":$port" else ""

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port).put("user", user)
        .put("auth", auth.name).put("via", via ?: JSONObject.NULL).put("trusted", trusted).put("hostKey", hostKey ?: JSONObject.NULL).put("about", about)

    companion object {
        fun fromJson(o: JSONObject) = Machine(
            o.getString("id"), o.optString("name"), o.optString("host"), o.optInt("port", 22), o.optString("user"),
            runCatching { MachineAuth.valueOf(o.optString("auth")) }.getOrDefault(MachineAuth.PASSWORD),
            o.optString("via").takeIf { it.isNotBlank() && it != "null" }, o.optBoolean("trusted"),
            o.optString("hostKey").takeIf { it.isNotBlank() && it != "null" }, o.optString("about"))
    }
}

/** The user's machines. Passwords, private keys and passphrases go to SettingsStore's encrypted secrets. */
class MachineStore(private val settings: SettingsStore) {
    private val _machines = MutableStateFlow(load())
    val machines: StateFlow<List<Machine>> = _machines

    private fun load(): List<Machine> = runCatching {
        val a = JSONArray(settings.extra("machines") ?: "[]"); (0 until a.length()).map { Machine.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Machine>) { settings.setExtra("machines", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()); _machines.value = list }

    fun get(id: String) = _machines.value.firstOrNull { it.id == id }
    /** By id or (case-insensitive) name, the way the agent refers to them. */
    fun find(ref: String): Machine? = _machines.value.firstOrNull { it.id == ref } ?: _machines.value.firstOrNull { it.name.equals(ref.trim(), true) }

    fun save(m: Machine) = persist(_machines.value.filter { it.id != m.id } + m)
    fun update(id: String, f: (Machine) -> Machine) { get(id)?.let { save(f(it)) } }
    fun remove(id: String) {
        listOf("pw", "key", "pass").forEach { settings.setSecret("machine_${it}_$id", null) }
        persist(_machines.value.filter { it.id != id }.map { if (it.via == id) it.copy(via = null) else it })
    }

    fun password(id: String) = settings.secret("machine_pw_$id")
    fun privateKey(id: String) = settings.secret("machine_key_$id")
    fun passphrase(id: String) = settings.secret("machine_pass_$id")
    fun setCredentials(id: String, password: String?, privateKey: String?, passphrase: String?) {
        settings.setSecret("machine_pw_$id", password); settings.setSecret("machine_key_$id", privateKey); settings.setSecret("machine_pass_$id", passphrase)
    }
    /** The public half of a key the app generated, to paste into the machine's ~/.ssh/authorized_keys. */
    fun publicKey(id: String) = settings.extra("machine_pub_$id")
    fun setPublicKey(id: String, pub: String?) = settings.setExtra("machine_pub_$id", pub)
}
