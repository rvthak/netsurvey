package com.rvthak.netsurvey.data

import android.content.Context
import android.net.Uri
import com.rvthak.netsurvey.data.db.MeasurementEntity
import com.rvthak.netsurvey.data.db.MeasurementTypeEntity
import com.rvthak.netsurvey.data.db.MeasurementWithDetails
import com.rvthak.netsurvey.data.db.NeighborCellEntity
import com.rvthak.netsurvey.data.db.NetSurveyDatabase
import com.rvthak.netsurvey.data.db.PlanEntity
import com.rvthak.netsurvey.data.db.SampleEntity
import com.rvthak.netsurvey.data.db.ServingCellEntity
import com.rvthak.netsurvey.data.backup.MeasurementBackup
import com.rvthak.netsurvey.engine.RunResult
import com.rvthak.netsurvey.model.DataCapConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Single entry point to persistence: Room DAOs plus on-disk storage of the
 * floor-plan PNGs. Blocking DAO calls are dispatched to [Dispatchers.IO].
 */
class NetSurveyRepository(private val context: Context) {

    private val db = NetSurveyDatabase.get(context)
    private val planDao = db.planDao()
    private val typeDao = db.typeDao()
    private val measurementDao = db.measurementDao()

    private val planImageDir: File by lazy {
        File(context.filesDir, "plans").apply { mkdirs() }
    }

    // --- plans ---------------------------------------------------------------

    fun observePlans(): Flow<List<PlanEntity>> = planDao.observeAll()

    suspend fun getPlan(id: Long): PlanEntity? = withContext(Dispatchers.IO) { planDao.get(id) }

    /** Copies the picked PNG into app storage and inserts the plan. */
    suspend fun createPlan(name: String, source: Uri): Long = withContext(Dispatchers.IO) {
        val dest = File(planImageDir, "${UUID.randomUUID()}.png")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "cannot open image source" }
            dest.outputStream().use { input.copyTo(it) }
        }
        planDao.insert(
            PlanEntity(name = name, imageUri = dest.absolutePath, createdAt = System.currentTimeMillis()),
        )
    }

    /** Inserts a plan whose image bytes already live on disk (import path). */
    suspend fun insertPlanWithImageFile(name: String, createdAt: Long, imageBytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            val dest = File(planImageDir, "${UUID.randomUUID()}.png")
            dest.outputStream().use { it.write(imageBytes) }
            planDao.insert(PlanEntity(name = name, imageUri = dest.absolutePath, createdAt = createdAt))
        }

    suspend fun renamePlan(plan: PlanEntity, newName: String) = withContext(Dispatchers.IO) {
        planDao.update(plan.copy(name = newName))
    }

    suspend fun deletePlan(plan: PlanEntity) = withContext(Dispatchers.IO) {
        runCatching { File(plan.imageUri).delete() }
        planDao.delete(plan.id) // cascades types → measurements → samples/cells
    }

    // --- types ---------------------------------------------------------------

    fun observeTypes(planId: Long): Flow<List<MeasurementTypeEntity>> = typeDao.observeForPlan(planId)

    fun observeType(typeId: Long): Flow<MeasurementTypeEntity?> = typeDao.observe(typeId)

    suspend fun createType(planId: Long, name: String, pinX: Float, pinY: Float): Long =
        withContext(Dispatchers.IO) {
            typeDao.insert(
                MeasurementTypeEntity(
                    planId = planId, name = name, pinX = pinX, pinY = pinY,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    suspend fun renameType(type: MeasurementTypeEntity, newName: String) = withContext(Dispatchers.IO) {
        typeDao.update(type.copy(name = newName))
    }

    suspend fun deleteType(typeId: Long) = withContext(Dispatchers.IO) { typeDao.delete(typeId) }

    // --- measurements --------------------------------------------------------

    fun observeMeasurementsForType(typeId: Long): Flow<List<MeasurementEntity>> =
        measurementDao.observeForType(typeId)

    fun observeMeasurementsForPlan(planId: Long): Flow<List<MeasurementEntity>> =
        measurementDao.observeForPlan(planId)

    fun observeMeasurementDetails(id: Long): Flow<MeasurementWithDetails?> =
        measurementDao.observeDetails(id)

    suspend fun saveMeasurement(
        measurement: MeasurementEntity,
        samples: List<SampleEntity>,
        servingCells: List<ServingCellEntity>,
        neighborCells: List<NeighborCellEntity>,
    ): Long = withContext(Dispatchers.IO) {
        measurementDao.insertComplete(measurement, samples, servingCells, neighborCells)
    }

    /** Persist a finished engine run under a measurement type. */
    suspend fun saveRun(typeId: Long, dataCap: DataCapConfig, notes: String, run: RunResult): Long =
        saveMeasurement(
            measurement = MeasurementEntity(
                typeId = typeId,
                startedAt = run.startedAt,
                durationSec = run.durationSec,
                notes = notes,
                dataCap = dataCap,
                summary = run.summary,
            ),
            samples = run.samples,
            servingCells = run.servingCells,
            neighborCells = run.neighborCells,
        )

    suspend fun deleteMeasurement(id: Long) = withContext(Dispatchers.IO) { measurementDao.delete(id) }

    // --- backup: export gather (SPEC §9) -------------------------------------

    /** Real plans only (the debug placeholder has an empty imageUri). */
    suspend fun snapshotPlans(): List<PlanEntity> = withContext(Dispatchers.IO) {
        planDao.getAll().filter { it.imageUri.isNotEmpty() }
    }

    suspend fun snapshotTypes(): List<MeasurementTypeEntity> =
        withContext(Dispatchers.IO) { typeDao.getAll() }

    suspend fun snapshotDetails(): List<MeasurementWithDetails> =
        withContext(Dispatchers.IO) { measurementDao.getAllDetails() }

    /** Raw PNG bytes for a plan, or null if the file has gone missing. */
    suspend fun readPlanImageBytes(plan: PlanEntity): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(plan.imageUri).readBytes() }.getOrNull()
    }

    // --- backup: import restore (SPEC §9) ------------------------------------

    /** Replace-on-import: drop every plan (cascades all the way down). */
    suspend fun clearAllData() = withContext(Dispatchers.IO) { planDao.deleteAll() }

    /** Insert a plan whose PNG bytes came from a backup (preserves createdAt). */
    suspend fun importPlan(name: String, createdAt: Long, imageBytes: ByteArray): Long =
        insertPlanWithImageFile(name, createdAt, imageBytes)

    suspend fun importType(
        planId: Long,
        name: String,
        pinX: Float,
        pinY: Float,
        createdAt: Long,
    ): Long = withContext(Dispatchers.IO) {
        typeDao.insert(
            MeasurementTypeEntity(planId = planId, name = name, pinX = pinX, pinY = pinY, createdAt = createdAt),
        )
    }

    /** Recreate one measurement (summary + raw samples + cells) under a type. */
    suspend fun importMeasurement(typeId: Long, m: MeasurementBackup) = withContext(Dispatchers.IO) {
        measurementDao.insertComplete(
            measurement = MeasurementEntity(
                typeId = typeId,
                startedAt = m.startedAt,
                durationSec = m.durationSec,
                notes = m.notes,
                dataCap = m.dataCap,
                summary = m.summary,
            ),
            // Reset leaf ids so the new measurement gets a clean, conflict-free set.
            samples = m.samples.map { it.copy(id = 0, measurementId = 0) },
            servingCells = m.servingCells.map { it.copy(id = 0, measurementId = 0) },
            neighborCells = m.neighborCells.map { it.copy(id = 0, measurementId = 0) },
        )
    }

    /** Debug-only: a throwaway plan+type so the Phase-3 run screen can persist. */
    suspend fun ensureDebugType(): Long = withContext(Dispatchers.IO) {
        val plan = planDao.getAll().firstOrNull { it.name == DEBUG_PLAN }
            ?: planDao.get(
                planDao.insert(
                    PlanEntity(name = DEBUG_PLAN, imageUri = "", createdAt = System.currentTimeMillis()),
                ),
            )!!
        typeDao.getAll().firstOrNull { it.planId == plan.id && it.name == DEBUG_TYPE }?.id
            ?: typeDao.insert(
                MeasurementTypeEntity(
                    planId = plan.id, name = DEBUG_TYPE, pinX = 0.5f, pinY = 0.5f,
                    createdAt = System.currentTimeMillis(),
                ),
            )
    }

    private companion object {
        const val DEBUG_PLAN = "__debug__"
        const val DEBUG_TYPE = "Debug spot"
    }
}
