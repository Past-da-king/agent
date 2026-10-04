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
        schema(listOf("title"), "title" to str("The goal, in the user's words"), "why" to str("Why it matters / the outcome wanted"), "tasks" to strList("Tasks that get it done, in order")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val gid = dao.insertGoal(GoalRow(title = input.optString("title"), why = input.optString("why")))
        val arr = input.optJSONArray("tasks"); val ids = mutableListOf<Long>()
        if (arr != null) for (i in 0 until arr.length()) ids += dao.insertTask(TaskRow(goalId = gid, title = arr.getString(i)))
        ctx.activity("New goal: ${input.optString("title")} · ${ids.size} tasks", JSONObject().put("tool", "tasks"))
        return "Goal #$gid created with tasks ${ids.joinToString { "#$it" }}"
    }
}

class TaskAddTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_add", "Add a to-do for the user (or for yourself to follow up). Optional goal and due date.",
        schema(listOf("title"), "title" to str("The task"), "notes" to str("Details"), "goal_id" to int("Goal it belongs to"), "due" to str("Due, ISO like 2026-10-06T14:00 or 2026-10-06")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val id = dao.insertTask(TaskRow(title = input.optString("title"), notes = input.optString("notes"),
            goalId = if (input.has("goal_id")) input.optLong("goal_id") else null, dueAt = parseWhen(input.optString("due"))))
        ctx.activity("Added task: ${input.optString("title")}", JSONObject().put("tool", "tasks"))
        return "Task #$id added"
    }
}

class TaskUpdateTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_update", "Change a task's status (todo, doing, done, blocked), title or notes. Mark tasks done as you finish them.",
        schema(listOf("id"), "id" to int("Task id"), "status" to enumOf("New status", "todo", "doing", "done", "blocked"), "title" to str("New title"), "notes" to str("New notes")))
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val t = dao.task(input.getLong("id")) ?: return "No such task"
        val updated = t.copy(status = input.optString("status").ifBlank { t.status }, title = input.optString("title").ifBlank { t.title },
            notes = input.optString("notes").ifBlank { t.notes }, updatedAt = System.currentTimeMillis())
        dao.updateTask(updated)
        // A goal is achieved when every one of its tasks is done.
        t.goalId?.let { gid ->
            val all = dao.tasksFor(gid)
            if (all.isNotEmpty() && all.all { it.status == "done" }) dao.goal(gid)?.let { dao.updateGoal(it.copy(status = "achieved")) }
        }
        ctx.activity(if (updated.status == "done") "Done: ${t.title}" else "Task ${t.title} → ${updated.status}", JSONObject().put("tool", "tasks"))
        return "Task #${t.id} is ${updated.status}"
    }
}

class TaskListTool(private val dao: TaskDao) : Tool {
    override val spec = ToolSpec("task_list", "List open goals and tasks.", schema())
    override suspend fun run(input: JSONObject, ctx: ToolContext): String {
        val goals = dao.openGoals(); val tasks = dao.openTasks()
        if (goals.isEmpty() && tasks.isEmpty()) return "No open goals or tasks."
        return buildString {
            goals.forEach { g -> appendLine("Goal #${g.id}: ${g.title}"); tasks.filter { it.goalId == g.id }.forEach { appendLine("  #${it.id} [${it.status}] ${it.title}") } }
            tasks.filter { t -> t.goalId == null || goals.none { it.id == t.goalId } }.forEach { appendLine("#${it.id} [${it.status}] ${it.title}") }
        }
    }
}
