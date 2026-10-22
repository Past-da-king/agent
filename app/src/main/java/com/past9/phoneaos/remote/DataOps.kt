package com.past9.phoneaos.remote

import android.content.Context
import com.past9.phoneaos.App
import com.past9.phoneaos.data.MemoryRow
import com.past9.phoneaos.data.TaskRow
import com.past9.phoneaos.data.TriggerRow
import com.past9.phoneaos.triggers.Routines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Edits made on the phone's screens (tick a task, save a memory page, switch a routine). Unlinked they go to the
 * phone's own database exactly as before; linked they go to the computer, and its answer comes back into the copy.
 */
class DataOps(private val context: Context, private val remote: RemoteSession?, private val scope: CoroutineScope) {
    private val g get() = App.graph(context)

    fun updateTask(t: TaskRow) {
        val r = remote ?: run { scope.launch { g.db.tasks().updateTask(t) }; return }
        scope.launch { r.db.tasks().updateTask(t) }
        r.upsert("task", RemoteRows.taskInput(t, r.ids))
    }

    fun insertTask(t: TaskRow) {
        val r = remote ?: run { scope.launch { g.db.tasks().insertTask(t) }; return }
        r.upsert("task", RemoteRows.taskInput(t, r.ids)) { row -> r.db.tasks().let { d -> RemoteRows.task(row, r.ids).let { if (d.task(it.id) == null) d.insertTask(it) else d.updateTask(it) } } }
    }

    fun deleteTask(id: Long) {
        val r = remote ?: run { scope.launch { g.db.tasks().deleteTask(id) }; return }
        r.remove("task", id)
        scope.launch { r.db.tasks().deleteTask(id) }
    }

    fun saveMemory(m: MemoryRow) {
        val r = remote ?: run { scope.launch { if (m.id == 0L) g.db.memory().insert(m) else g.db.memory().update(m) }; return }
        if (m.id != 0L) scope.launch { r.db.memory().update(m) }
        r.upsert("memory", RemoteRows.memoryInput(m, r.ids)) { row ->
            val local = RemoteRows.memory(row, r.ids)
            if (r.db.memory().get(local.id) == null) r.db.memory().insert(local) else r.db.memory().update(local)
        }
    }

    fun deleteMemory(id: Long) {
        val r = remote ?: run { scope.launch { g.db.memory().delete(id) }; return }
        r.remove("memory", id)
        scope.launch { r.db.memory().delete(id) }
    }

    /** Save or switch a routine. On the phone it is scheduled here; linked, the computer runs it. */
    fun saveRoutine(t: TriggerRow) {
        val r = remote ?: run { scope.launch { val id = g.db.triggers().upsert(t); Routines.schedule(context, t.copy(id = id)) }; return }
        if (t.id != 0L) scope.launch { r.db.triggers().upsert(t) }
        r.upsert("trigger", RemoteRows.triggerInput(t, r.ids)) { row -> r.db.triggers().upsert(RemoteRows.trigger(row, r.ids)) }
    }

    fun runRoutine(id: Long) {
        val r = remote ?: run { Routines.runNow(context, id); return }
        val rid = r.ids.remote(id) ?: return
        scope.launch { runCatching { r.call("routines.runNow", listOf(rid)) } }
    }

    fun deleteRoutine(id: Long) {
        val r = remote ?: run { scope.launch { g.db.triggers().delete(id); Routines.cancel(context, id) }; return }
        r.remove("trigger", id)
        scope.launch { r.db.triggers().delete(id) }
    }
}
