package com.past9.phoneaos.wakeword

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the person chose in Settings > Wake word. */
data class WakeWordSettings(
    /** Off until the person switches it on. */
    val enabled: Boolean = false,
    /** Their own phrase, or empty for "Hey <the agent's name>". */
    val customPhrase: String = "",
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val onlyCharging: Boolean = false,
    val pauseOnSaver: Boolean = true,
    val pauseLowBattery: Boolean = false,
    /** Paused from the notification. Stays paused until Resume. */
    val userPaused: Boolean = false,
    /** Spellings learned from the person's voice, and the phrase they were learned for (a new phrase forgets them). */
    val learned: List<List<String>> = emptyList(),
    val learnedFor: String = "",
) {
    /** The learned spellings, if they belong to [phrase]. */
    fun learnedFor(phrase: String): List<List<String>> = if (learnedFor == key(phrase)) learned else emptyList()

    companion object { fun key(phrase: String): String = phrase.trim().lowercase() }
}

/** Keeps the wake word settings (their own small file, nothing secret in it). */
class WakeWordStore(context: Context) {
    internal val app: Context = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("wake_word", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<WakeWordSettings> = _state

    private fun read() = WakeWordSettings(
        enabled = prefs.getBoolean("enabled", false),
        customPhrase = prefs.getString("phrase", "").orEmpty(),
        sensitivity = Sensitivity.of(prefs.getString("sensitivity", null)),
        onlyCharging = prefs.getBoolean("onlyCharging", false),
        pauseOnSaver = prefs.getBoolean("pauseOnSaver", true),
        pauseLowBattery = prefs.getBoolean("pauseLowBattery", false),
        userPaused = prefs.getBoolean("userPaused", false),
        learned = Learned.decode(prefs.getString("learned", "").orEmpty()),
        learnedFor = prefs.getString("learnedFor", "").orEmpty(),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) { prefs.edit().apply(block).apply(); _state.value = read() }

    /** Switching it on also clears a pause left over from before. */
    fun setEnabled(on: Boolean) = edit { putBoolean("enabled", on); putBoolean("userPaused", false) }
    fun setPhrase(phrase: String) = edit { putString("phrase", phrase.trim()) }
    fun setSensitivity(s: Sensitivity) = edit { putString("sensitivity", s.name) }
    fun setOnlyCharging(on: Boolean) = edit { putBoolean("onlyCharging", on) }
    fun setPauseOnSaver(on: Boolean) = edit { putBoolean("pauseOnSaver", on) }
    fun setPauseLowBattery(on: Boolean) = edit { putBoolean("pauseLowBattery", on) }
    fun setUserPaused(on: Boolean) = edit { putBoolean("userPaused", on) }
    fun setLearned(phrase: String, spellings: List<List<String>>) = edit {
        putString("learned", Learned.encode(spellings)); putString("learnedFor", if (spellings.isEmpty()) "" else WakeWordSettings.key(phrase))
    }

    companion object {
        @Volatile private var one: WakeWordStore? = null
        /** One store per application (a test run makes a new application for every test). */
        fun get(context: Context): WakeWordStore {
            val app = context.applicationContext
            return one?.takeIf { it.app === app } ?: synchronized(this) { one?.takeIf { it.app === app } ?: WakeWordStore(app).also { one = it } }
        }
    }
}
