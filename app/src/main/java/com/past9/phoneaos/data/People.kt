package com.past9.phoneaos.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * People: the personal CRM (GitHub issue #13). Nothing readable is kept in these two tables.
 *
 * [PersonRow.sealed] is one AES-256-GCM blob holding everything about the person (name, note, important dates,
 * gift ideas), sealed with a key that never leaves the Android Keystore. Only people/PeopleRepository.kt opens it,
 * and there is no second, plain copy anywhere: the numbers beside it (when the person was last spoken to, when the
 * row changed) are timestamps, so the list can be sorted and "who is going quiet" answered without opening a row.
 */
@Entity(tableName = "people")
data class PersonRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Sealed JSON: name, note, important dates, gift ideas. Never plain text. */
    val sealed: String,
    /** When the person was last spoken to (ms). A number, not text. */
    val lastLogAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** One entry in a person's timeline (a call, a message, a note). */
@Entity(tableName = "person_logs")
data class PersonLogRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    /** Sealed JSON: the kind of contact and what was said. */
    val sealed: String,
    /** When it happened (ms). Orders the timeline without opening the row. */
    val at: Long = System.currentTimeMillis(),
)

@Dao
interface PeopleDao {
    @Query("SELECT * FROM people ORDER BY COALESCE(lastLogAt, 0) ASC, updatedAt DESC") fun all(): Flow<List<PersonRow>>
    @Query("SELECT * FROM people ORDER BY COALESCE(lastLogAt, 0) ASC, updatedAt DESC") suspend fun list(): List<PersonRow>
    @Query("SELECT * FROM people WHERE id = :id") suspend fun person(id: Long): PersonRow?
    @Insert suspend fun insertPerson(p: PersonRow): Long
    @Update suspend fun updatePerson(p: PersonRow)
    @Query("DELETE FROM people WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE people SET lastLogAt = :at, updatedAt = :at WHERE id = :id AND (lastLogAt IS NULL OR lastLogAt < :at)") suspend fun touch(id: Long, at: Long)

    @Query("SELECT * FROM person_logs WHERE personId = :personId ORDER BY at DESC, id DESC") suspend fun logs(personId: Long): List<PersonLogRow>
    @Query("SELECT * FROM person_logs WHERE personId = :personId ORDER BY at DESC, id DESC LIMIT 1") suspend fun lastLog(personId: Long): PersonLogRow?
    @Insert suspend fun insertLog(l: PersonLogRow): Long
    @Query("DELETE FROM person_logs WHERE personId = :personId") suspend fun deleteLogs(personId: Long)
    @Query("DELETE FROM person_logs WHERE id = :id") suspend fun deleteLog(id: Long)
}
