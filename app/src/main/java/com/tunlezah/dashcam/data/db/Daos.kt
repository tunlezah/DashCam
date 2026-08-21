package com.tunlezah.dashcam.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SegmentDao {

    @Insert
    suspend fun insert(segment: SegmentEntity): Long

    @Update
    suspend fun update(segment: SegmentEntity)

    @Query("SELECT * FROM segments WHERE id = :id")
    suspend fun byId(id: Long): SegmentEntity?

    @Query("SELECT * FROM segments ORDER BY startWallMs DESC")
    fun observeAll(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE protected = 1 ORDER BY startWallMs DESC")
    fun observeProtected(): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE state = 'RECORDING'")
    suspend fun inProgress(): List<SegmentEntity>

    @Query(
        "SELECT * FROM segments WHERE protected = 0 AND state != 'RECORDING' " +
            "ORDER BY startWallMs ASC LIMIT :limit"
    )
    suspend fun oldestUnprotected(limit: Int): List<SegmentEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE protected = 0")
    suspend fun loopBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segments WHERE protected = 1")
    suspend fun protectedBytes(): Long

    /** Segments overlapping [fromMs, toMs] — used for pre-event protection. */
    @Query(
        "SELECT * FROM segments WHERE startWallMs <= :toMs AND " +
            "(endWallMs IS NULL OR endWallMs >= :fromMs) ORDER BY startWallMs ASC"
    )
    suspend fun overlapping(fromMs: Long, toMs: Long): List<SegmentEntity>

    @Query("UPDATE segments SET protected = 1, eventId = :eventId WHERE id IN (:ids)")
    suspend fun protect(ids: List<Long>, eventId: Long)

    @Query("UPDATE segments SET protected = 0, eventId = NULL WHERE id = :id")
    suspend fun unprotect(id: Long)

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM segments")
    suspend fun all(): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE filePath = :path")
    suspend fun deleteByPath(path: String)
}

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    @Query("SELECT * FROM events ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE state = 'PENDING'")
    suspend fun pending(): List<EventEntity>

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)
}
