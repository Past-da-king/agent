package com.past9.phoneaos.triggers

/**
 * Task reminders, the pure part. A task on the user's list with a due time gets an alarm-clock alarm at that
 * moment; the notification is plain code (no AI call). A moment the phone missed (off, app killed before the
 * alarm existed) is said once when the app next runs, if it is under a day old.
 */
object ReminderPlan {
    const val MISSED_WINDOW = 24 * 3_600_000L

    data class Due(val id: Long, val title: String, val dueAt: Long)
    data class Plan(val arm: Map<Long, Long>, val cancel: Set<Long>, val missed: List<Due>)

    fun key(id: Long, dueAt: Long) = "$id@$dueAt"

    /**
     * @param open the user's open tasks with a due time
     * @param armed what is armed now (task id -> moment)
     * @param said reminders already shown (keys from [key])
     * @param force re-arm everything (after a reboot, alarms are gone)
     */
    fun plan(open: List<Due>, armed: Map<Long, Long>, said: Set<String>, now: Long, force: Boolean = false): Plan {
        val future = open.filter { it.dueAt > now }.associate { it.id to it.dueAt }
        val arm = future.filter { (id, at) -> force || armed[id] != at }
        val cancel = armed.keys.filter { it !in future }.toSet()
        val missed = open.filter { it.dueAt <= now && now - it.dueAt < MISSED_WINDOW && key(it.id, it.dueAt) !in said }
        return Plan(arm, cancel, missed)
    }
}
