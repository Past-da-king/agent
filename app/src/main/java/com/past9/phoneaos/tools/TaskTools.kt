package com.past9.phoneaos.tools

import com.past9.phoneaos.agent.Tool
import com.past9.phoneaos.agent.ToolContext
import com.past9.phoneaos.agent.ToolSpec
import com.past9.phoneaos.agent.enumOf
import com.past9.phoneaos.agent.int
import com.past9.phoneaos.agent.schema
import com.past9.phoneaos.agent.str
import com.past9.phoneaos.agent.strList
import com.past9.phoneaos.data.GoalRow
import com.past9.phoneaos.data.TaskDao
import com.past9.phoneaos.data.TaskRow
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

fun parseWhen(s: String): Long? {
    if (s.isBlank()) return null
    val z = ZoneId.systemDefault()
    return runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T')).atZone(z).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(s.trim()).atTime(9, 0).atZone(z).toInstant().toEpochMilli() }.getOrNull()
}

class GoalCreateTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("goal_create", "Start a goal for something bigger the user wants (e.g. 'Plan the Durban trip') and break it into tasks. The user sees goals and tasks in the Tasks tab.",
        schema(listOf("title"), "title" to str("The goal, in the user's words"), "why" to str("Why it matters / the outcome wanted"), "tasks" to strList("Steps that get it done, in order. Your own steps as plain text; steps only the user can do start with 'You:'")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val gid = dao.insertGoal(GoalRow(title = input.optString("title"), why = input.optString("why")))
        val arr = input.optJSONArray("tasks"); val ids = mutableListOf<Long>()
        // Steps under a goal are the agent's own work unless the user must do them (prefix "You:").
        if (arr != null) for (i in 0 until arr.length()) {
            val t = arr.getString(i); val mine = t.startsWith("You:", true)
            ids += dao.insertTask(TaskRow(goalId = gid, title = if (mine) t.substringAfter(':').trim() else t, owner = if (mine) "user" else "agent"))
        }
        ctx.activity("New goal: ${input.optString("title")} · ${ids.size} tasks", JSONObject().put("tool", "tasks"))
        return "Goal #$gid created with tasks ${ids.joinToString { "#$it" }}"
    }
}

class TaskAddTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_add", "Add a task. owner 'user' puts it on the user's own to-do list (things THEY must do, or reminders they asked for); owner 'agent' is a step you will do yourself. Optional goal and due date.",
        schema(listOf("title", "owner"), "title" to str("The task"), "owner" to enumOf("Whose task", "user", "agent"), "notes" to str("Details"), "goal_id" to int("Goal it belongs to"), "due" to str("Due, ISO like 2026-10-06T14:00 or 2026-10-06")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = dao.insertTask(TaskRow(title = input.optString("title"), notes = input.optString("notes"),
            goalId = if (input.has("goal_id")) input.optLong("goal_id") else null, dueAt = parseWhen(input.optString("due")),
            owner = if (input.optString("owner") == "agent") "agent" else "user"))
        ctx.activity((if (input.optString("owner") == "agent") "Planned: " else "Added to your to-dos: ") + input.optString("title"), JSONObject().put("tool", "tasks"))
        return "Task #$id added"
    }
}

class TaskUpdateTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_update", "Change a task's status (todo, doing, done, blocked), title, notes or due date. Mark your steps doing/done as you go. When blocked, say exactly what would unblock it in 'blocker'. A task that no longer applies is removed with task_delete, not given another status.",
        schema(listOf("id"), "id" to int("Task id"), "status" to enumOf("New status", *STATUSES.toTypedArray()), "title" to str("New title"), "notes" to str("New notes"), "blocker" to str("What is needed to unblock, from whom"),
            "due" to str("New due date, ISO like 2026-10-06T14:00 or 2026-10-06; 'none' clears it")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val t = dao.task(input.getLong("id")) ?: return "No such task"
        val status = input.optString("status")
        // Anything else (cancelled, deleted...) would leave the task open on the user's list.
        if (status.isNotBlank() && status !in STATUSES) return "Not changed: status is one of ${STATUSES.joinToString()}. To remove the task, use task_delete."
        val due = input.optString("due").trim()
        val dueAt = when { due.isBlank() -> t.dueAt; due.equals("none", true) -> null; else -> parseWhen(due) ?: return "Not changed: due must be ISO like 2026-10-06T14:00 or 2026-10-06, or 'none'." }
        val updated = t.copy(status = status.ifBlank { t.status }, title = input.optString("title").ifBlank { t.title },
            notes = input.optString("notes").ifBlank { t.notes }, dueAt = dueAt, updatedAt = System.currentTimeMillis(),
            blocker = if (input.optString("status") == "blocked") input.optString("blocker").ifBlank { t.blocker } else if (input.has("status")) "" else t.blocker)
        dao.updateTask(updated)
        // A goal is achieved when every one of its tasks is done.
        t.goalId?.let { gid ->
            val all = dao.tasksFor(gid)
            if (all.isNotEmpty() && all.all { it.status == "done" }) dao.goal(gid)?.let { dao.updateGoal(it.copy(status = "achieved")) }
        }
        ctx.activity(if (updated.status == "done") "Done: ${t.title}" else "Task ${t.title} → ${updated.status}", JSONObject().put("tool", "tasks"))
        return "Task #${t.id} is ${updated.status}" + (updated.dueAt?.let { ", due ${dueText(it)}" } ?: "")
    }

    companion object { val STATUSES = listOf("todo", "doing", "done", "blocked") }
}

class TaskDeleteTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_delete", "Remove a task for good: it no longer applies, it's a duplicate, or the user asked. A finished task is marked done with task_update instead.",
        schema(listOf("id"), "id" to int("Task id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val t = dao.task(input.getLong("id")) ?: return "No such task"
        dao.deleteTask(t.id)
        // Its goal may now have only done tasks left: that's achieved.
        t.goalId?.let { gid ->
            val rest = dao.tasksFor(gid)
            if (rest.isNotEmpty() && rest.all { it.status == "done" }) dao.goal(gid)?.let { dao.updateGoal(it.copy(status = "achieved")) }
        }
        ctx.activity("Removed from your to-dos: ${t.title}", JSONObject().put("tool", "tasks"))
        return "Task #${t.id} deleted"
    }
}

class GoalUpdateTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("goal_update", "Change a goal's title or why, or its status: 'achieved' when the outcome is reached, 'open' to reopen it. A goal that no longer applies is removed with goal_delete.",
        schema(listOf("id"), "id" to int("Goal id"), "title" to str("New title"), "why" to str("New why"), "status" to enumOf("New status", "open", "achieved")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val g = dao.goal(input.getLong("id")) ?: return "No such goal"
        val status = input.optString("status")
        if (status.isNotBlank() && status !in listOf("open", "achieved")) return "Not changed: status is open or achieved. To remove the goal, use goal_delete."
        val updated = g.copy(title = input.optString("title").ifBlank { g.title }, why = input.optString("why").ifBlank { g.why }, status = status.ifBlank { g.status })
        dao.updateGoal(updated)
        ctx.activity("Goal ${updated.title} → ${updated.status}", JSONObject().put("tool", "tasks"))
        return "Goal #${g.id} is ${updated.status}"
    }
}

class GoalDeleteTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("goal_delete", "Remove a goal and all its tasks for good: it no longer applies, or the user asked.", schema(listOf("id"), "id" to int("Goal id")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val g = dao.goal(input.getLong("id")) ?: return "No such goal"
        val tasks = dao.tasksFor(g.id)
        tasks.forEach { dao.deleteTask(it.id) }
        dao.deleteGoal(g.id)
        ctx.activity("Removed goal: ${g.title}", JSONObject().put("tool", "tasks"))
        return "Goal #${g.id} deleted with its ${tasks.size} task(s)"
    }
}

/** A due date the way the agent reads and writes it: 2026-10-16, or 2026-10-16T14:00 when it has a time. */
private fun dueText(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().let {
    if (it.hour == 9 && it.minute == 0) it.toLocalDate().toString() else it.toString().take(16)
}

class TaskListTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_list", "List open goals and tasks.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val goals = dao.openGoals(); val tasks = dao.openTasks()
        if (goals.isEmpty() && tasks.isEmpty()) return "No open goals or tasks."
        return buildString {
            val now = System.currentTimeMillis()
            fun line(t: TaskRow) = "#${t.id} [${t.status}${if (t.owner == "user") ", user's" else ""}] ${t.title}" +
                (t.dueAt?.let { " (due ${dueText(it)}${if (it < now) ", overdue" else ""})" } ?: "") + (if (t.blocker.isNotBlank()) " (blocked: ${t.blocker})" else "")
            goals.forEach { g -> appendLine("Goal #${g.id}: ${g.title}"); tasks.filter { it.goalId == g.id }.forEach { appendLine("  " + line(it)) } }
            tasks.filter { t -> t.goalId == null || goals.none { it.id == t.goalId } }.forEach { appendLine(line(it)) }
        }
    }
}
