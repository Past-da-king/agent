package com.past9.phoneaos.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One row in the chat the user sees. `kind` decides how it renders. */
@Entity(tableName = "chat_items")
data class ChatItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** user | agent | activity | helper | question | notice */
    val kind: String,
    val text: String,
    /** Free JSON: tool name, status, options, chosen answer... */
    val meta: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

/** One provider-neutral message of the model's own transcript (JSON of agent.Msg). */
@Entity(tableName = "turns")
data class TurnRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val thread: String = "main",
    val json: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "memories")
data class MemoryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    /** fact | preference | person | decision | how-to | event */
    val kind: String = "fact",
    val topics: String = "",
    val pinned: Boolean = false,
    /** agent | user */
    val source: String = "agent",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "goals")
data class GoalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val why: String = "",
    /** open | achieved | dropped */
    val status: String = "open",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long? = null,
    val title: String,
    val notes: String = "",
    /** todo | doing | done | blocked */
    val status: String = "todo",
    val dueAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A workflow: WHEN (a time or an event) + WHAT (a prompt the agent runs). */
@Entity(tableName = "triggers")
data class TriggerRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** at | daily | interval | email */
    val kind: String,
    /** at: epoch millis; daily: "HH:mm"; interval: minutes; email: gmail search query */
    val spec: String,
    val prompt: String,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String = "",
    /** event triggers remember what they already saw (e.g. newest Gmail message id) */
    val cursor: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_items ORDER BY id") fun all(): Flow<List<ChatItem>>
    @Insert suspend fun insert(item: ChatItem): Long
    @Update suspend fun update(item: ChatItem)
    @Query("SELECT * FROM chat_items WHERE id = :id") suspend fun get(id: Long): ChatItem?
    @Query("DELETE FROM chat_items") suspend fun clear()

    @Query("SELECT * FROM turns WHERE thread = :thread ORDER BY id DESC LIMIT :limit") suspend fun recentTurns(thread: String, limit: Int): List<TurnRow>
    @Insert suspend fun insertTurn(t: TurnRow): Long
    @Query("DELETE FROM turns WHERE thread = :thread") suspend fun clearTurns(thread: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC") fun all(): Flow<List<MemoryRow>>
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC") suspend fun list(): List<MemoryRow>
    @Query("SELECT * FROM memories WHERE pinned = 1 ORDER BY updatedAt DESC") suspend fun pinned(): List<MemoryRow>
    @Query("SELECT * FROM memories WHERE id = :id") suspend fun get(id: Long): MemoryRow?
    @Insert suspend fun insert(m: MemoryRow): Long
    @Update suspend fun update(m: MemoryRow)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM goals ORDER BY status = 'open' DESC, createdAt DESC") fun goals(): Flow<List<GoalRow>>
    @Query("SELECT * FROM tasks ORDER BY status = 'done', createdAt DESC") fun tasks(): Flow<List<TaskRow>>
    @Query("SELECT * FROM goals WHERE status = 'open'") suspend fun openGoals(): List<GoalRow>
    @Query("SELECT * FROM tasks WHERE status != 'done' ORDER BY createdAt") suspend fun openTasks(): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE goalId = :goalId") suspend fun tasksFor(goalId: Long): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: Long): TaskRow?
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun goal(id: Long): GoalRow?
    @Insert suspend fun insertGoal(g: GoalRow): Long
    @Insert suspend fun insertTask(t: TaskRow): Long
    @Update suspend fun updateGoal(g: GoalRow)
    @Update suspend fun updateTask(t: TaskRow)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: Long)
    @Query("DELETE FROM goals WHERE id = :id") suspend fun deleteGoal(id: Long)
}

@Dao
interface TriggerDao {
    @Query("SELECT * FROM triggers ORDER BY createdAt DESC") fun all(): Flow<List<TriggerRow>>
    @Query("SELECT * FROM triggers") suspend fun list(): List<TriggerRow>
    @Query("SELECT * FROM triggers WHERE id = :id") suspend fun get(id: Long): TriggerRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: TriggerRow): Long
    @Query("DELETE FROM triggers WHERE id = :id") suspend fun delete(id: Long)
}

@Database(
    entities = [ChatItem::class, TurnRow::class, MemoryRow::class, GoalRow::class, TaskRow::class, TriggerRow::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun chat(): ChatDao
    abstract fun memory(): MemoryDao
    abstract fun tasks(): TaskDao
    abstract fun triggers(): TriggerDao

    companion object {
        fun open(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "agent.db").build()
        fun inMemory(context: Context): AppDb =
            Room.inMemoryDatabaseBuilder(context, AppDb::class.java).allowMainThreadQueries().build()
    }
}
