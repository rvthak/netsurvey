package com.rvthak.netsurvey.data.backup

import android.content.Context
import android.net.Uri
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of the whole app to a single zip (SPEC §9): a [MANIFEST_ENTRY]
 * JSON tree plus one `plans/N.png` entry per plan. Survives uninstall / new phone.
 *
 * Import is **replace**, not merge — it wipes the current database and settings,
 * then rebuilds from the bundle (matches the "reinstall → restore" story and keeps
 * ids from colliding). Both paths are wrapped in [Result] so the UI can report a
 * plain success/failure message rather than crashing.
 */
class BackupManager(context: Context) {

    private val appContext = context.applicationContext
    private val repo = NetSurveyRepository(appContext)
    private val settingsRepo = SettingsRepository(appContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** Suggested filename for the SAF "create document" dialog. */
    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "netsurvey-backup-$stamp.zip"
    }

    suspend fun export(dest: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val plans = repo.snapshotPlans()
            val typesByPlan = repo.snapshotTypes().groupBy { it.planId }
            val detailsByType = repo.snapshotDetails().groupBy { it.measurement.typeId }

            // Pair each plan with its PNG bytes; skip plans whose image has gone
            // missing so every imageFile in the manifest is guaranteed present.
            val withImages = plans.mapNotNull { plan ->
                repo.readPlanImageBytes(plan)?.let { bytes -> plan to bytes }
            }

            val planBackups = withImages.mapIndexed { index, (plan, _) ->
                val imageFile = "plans/$index.png"
                val types = typesByPlan[plan.id].orEmpty().map { type ->
                    val measurements = detailsByType[type.id].orEmpty().map { d ->
                        MeasurementBackup(
                            startedAt = d.measurement.startedAt,
                            durationSec = d.measurement.durationSec,
                            notes = d.measurement.notes,
                            dataCap = d.measurement.dataCap,
                            summary = d.measurement.summary,
                            samples = d.samples,
                            servingCells = d.servingCells,
                            neighborCells = d.neighborCells,
                        )
                    }
                    TypeBackup(type.name, type.pinX, type.pinY, type.createdAt, measurements)
                }
                PlanBackup(plan.name, plan.createdAt, imageFile, types)
            }

            val bundle = BackupBundle(
                exportedAt = System.currentTimeMillis(),
                settings = settingsRepo.settings.first(),
                plans = planBackups,
            )

            val output = appContext.contentResolver.openOutputStream(dest)
                ?: error("can't open the chosen location for writing")
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(json.encodeToString(bundle).toByteArray())
                zip.closeEntry()
                withImages.forEachIndexed { index, (_, bytes) ->
                    zip.putNextEntry(ZipEntry("plans/$index.png"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            planBackups.size
        }
    }

    suspend fun import(src: Uri): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            // Backups are small; read every entry into memory so we can parse the
            // manifest first and then resolve image entries by name.
            val entries = HashMap<String, ByteArray>()
            val input = appContext.contentResolver.openInputStream(src)
                ?: error("can't open the selected file")
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val manifestBytes = entries[MANIFEST_ENTRY]
                ?: error("not a NetSurvey backup (no $MANIFEST_ENTRY)")
            val bundle = json.decodeFromString<BackupBundle>(manifestBytes.decodeToString())
            require(bundle.version <= BACKUP_VERSION) {
                "this backup is from a newer app version (v${bundle.version})"
            }

            // Replace: wipe data + settings, then rebuild from the bundle.
            repo.clearAllData()
            settingsRepo.replaceAll(bundle.settings)

            var typeCount = 0
            var measurementCount = 0
            var planCount = 0
            for (plan in bundle.plans) {
                val bytes = entries[plan.imageFile]
                    ?: error("backup is missing image ${plan.imageFile}")
                val planId = repo.importPlan(plan.name, plan.createdAt, bytes)
                planCount++
                for (type in plan.types) {
                    val typeId = repo.importType(planId, type.name, type.pinX, type.pinY, type.createdAt)
                    typeCount++
                    for (measurement in type.measurements) {
                        repo.importMeasurement(typeId, measurement)
                        measurementCount++
                    }
                }
            }
            ImportSummary(plans = planCount, types = typeCount, measurements = measurementCount)
        }
    }
}
