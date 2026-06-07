package com.rvthak.netsurvey.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Insert fun insert(plan: PlanEntity): Long

    @Update suspend fun update(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)

    /** Wipe everything — cascades types → measurements → samples/cells (import replace). */
    @Query("DELETE FROM plans")
    suspend fun deleteAll()

    @Query("SELECT * FROM plans ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun get(id: Long): PlanEntity?

    @Query("SELECT * FROM plans ORDER BY createdAt ASC")
    suspend fun getAll(): List<PlanEntity>
}

@Dao
interface TypeDao {
    @Insert fun insert(type: MeasurementTypeEntity): Long

    @Update suspend fun update(type: MeasurementTypeEntity)

    @Query("DELETE FROM types WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM types WHERE planId = :planId ORDER BY createdAt ASC")
    fun observeForPlan(planId: Long): Flow<List<MeasurementTypeEntity>>

    @Query("SELECT * FROM types WHERE id = :id")
    fun observe(id: Long): Flow<MeasurementTypeEntity?>

    @Query("SELECT * FROM types")
    suspend fun getAll(): List<MeasurementTypeEntity>
}

@Dao
interface MeasurementDao {
    @Insert fun insertMeasurement(m: MeasurementEntity): Long

    @Insert fun insertSamples(samples: List<SampleEntity>)

    @Insert fun insertServingCells(cells: List<ServingCellEntity>)

    @Insert fun insertNeighborCells(cells: List<NeighborCellEntity>)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM measurements WHERE typeId = :typeId ORDER BY startedAt DESC")
    fun observeForType(typeId: Long): Flow<List<MeasurementEntity>>

    /** All measurements under a plan (joined via type), for map pin aggregates. */
    @Query(
        """
        SELECT m.* FROM measurements m
        INNER JOIN types t ON m.typeId = t.id
        WHERE t.planId = :planId
        """,
    )
    fun observeForPlan(planId: Long): Flow<List<MeasurementEntity>>

    @Transaction
    @Query("SELECT * FROM measurements WHERE id = :id")
    fun observeDetails(id: Long): Flow<MeasurementWithDetails?>

    @Transaction
    @Query("SELECT * FROM measurements")
    suspend fun getAllDetails(): List<MeasurementWithDetails>

    /**
     * Persist a complete measurement (summary + raw samples + cells) atomically.
     * Child rows are stamped with the generated measurement id.
     */
    @Transaction
    fun insertComplete(
        measurement: MeasurementEntity,
        samples: List<SampleEntity>,
        servingCells: List<ServingCellEntity>,
        neighborCells: List<NeighborCellEntity>,
    ): Long {
        val id = insertMeasurement(measurement)
        if (samples.isNotEmpty()) insertSamples(samples.map { it.copy(measurementId = id) })
        if (servingCells.isNotEmpty()) insertServingCells(servingCells.map { it.copy(measurementId = id) })
        if (neighborCells.isNotEmpty()) insertNeighborCells(neighborCells.map { it.copy(measurementId = id) })
        return id
    }
}
