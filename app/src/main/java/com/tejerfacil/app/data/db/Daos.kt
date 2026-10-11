package com.tejerfacil.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PatternDao {
    @Query(
        "SELECT p.*, (SELECT COUNT(*) FROM diagrams d WHERE d.patternId = p.id) AS diagramCount " +
            "FROM patterns p ORDER BY p.importedAt DESC"
    )
    fun observeSummaries(): Flow<List<PatternSummary>>

    @Query("SELECT * FROM patterns WHERE id = :id")
    suspend fun get(id: Long): PatternEntity?

    @Query("SELECT * FROM patterns")
    suspend fun listAll(): List<PatternEntity>

    @Query("SELECT * FROM patterns WHERE id = :id")
    fun observe(id: Long): Flow<PatternEntity?>

    @Insert
    suspend fun insert(p: PatternEntity): Long

    @Update
    suspend fun update(p: PatternEntity)

    @Query("DELETE FROM patterns WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DiagramDao {
    @Query(
        "SELECT d.*, pr.currentRow AS currentRow, pr.updatedAt AS progressUpdatedAt, " +
            "(SELECT COUNT(*) FROM diagram_rows r WHERE r.diagramId = d.id) AS rowCount, " +
            "pt.name AS patternName " +
            "FROM diagrams d JOIN patterns pt ON pt.id = d.patternId " +
            "LEFT JOIN progress pr ON pr.diagramId = d.id " +
            "WHERE d.patternId = :patternId ORDER BY d.pageIndex, d.indexInPage"
    )
    fun observeForPattern(patternId: Long): Flow<List<DiagramListItem>>

    @Query("SELECT * FROM diagrams WHERE patternId = :patternId ORDER BY pageIndex, indexInPage")
    suspend fun listForPattern(patternId: Long): List<DiagramEntity>

    @Query("SELECT * FROM diagrams WHERE patternId = :patternId AND pageIndex = :page ORDER BY indexInPage")
    suspend fun listForPage(patternId: Long, page: Int): List<DiagramEntity>

    @Query("SELECT * FROM diagrams WHERE id = :id")
    suspend fun get(id: Long): DiagramEntity?

    @Query("SELECT * FROM diagrams WHERE id = :id")
    fun observe(id: Long): Flow<DiagramEntity?>

    @Insert
    suspend fun insert(d: DiagramEntity): Long

    @Update
    suspend fun update(d: DiagramEntity)

    @Delete
    suspend fun delete(d: DiagramEntity)
}

@Dao
abstract class RowDao {
    @Query("SELECT * FROM diagram_rows WHERE diagramId = :diagramId ORDER BY orderIndex")
    abstract suspend fun listForDiagram(diagramId: Long): List<RowEntity>

    @Query("SELECT * FROM diagram_rows WHERE diagramId = :diagramId ORDER BY orderIndex")
    abstract fun observeForDiagram(diagramId: Long): Flow<List<RowEntity>>

    @Query("DELETE FROM diagram_rows WHERE diagramId = :diagramId")
    abstract suspend fun deleteForDiagram(diagramId: Long)

    @Insert
    abstract suspend fun insertAll(rows: List<RowEntity>)

    @Transaction
    open suspend fun replace(diagramId: Long, rows: List<RowEntity>) {
        deleteForDiagram(diagramId)
        insertAll(rows.map { it.copy(id = 0, diagramId = diagramId) })
    }
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE diagramId = :diagramId")
    suspend fun get(diagramId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE diagramId = :diagramId")
    fun observe(diagramId: Long): Flow<ProgressEntity?>

    @Upsert
    suspend fun upsert(p: ProgressEntity)
}
