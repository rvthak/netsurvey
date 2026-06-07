package com.rvthak.netsurvey.data.backup

import com.rvthak.netsurvey.data.db.NeighborCellEntity
import com.rvthak.netsurvey.data.db.SampleEntity
import com.rvthak.netsurvey.data.db.ServingCellEntity
import com.rvthak.netsurvey.model.AppSettings
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.MeasurementSummary
import kotlinx.serialization.Serializable

/** Bundle format version — bump if the shape changes incompatibly (SPEC §9). */
const val BACKUP_VERSION = 1

/** Name of the JSON manifest entry inside the backup zip. */
const val MANIFEST_ENTRY = "manifest.json"

/**
 * The whole app's data as one serializable tree (SPEC §9). Deliberately
 * **hierarchical** — plans own types own measurements own samples/cells — so no
 * database ids need to be carried or remapped: import just recreates the tree and
 * lets Room assign fresh ids. Plan PNGs live as separate entries in the zip,
 * referenced by [PlanBackup.imageFile].
 */
@Serializable
data class BackupBundle(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long,
    val settings: AppSettings,
    val plans: List<PlanBackup>,
)

@Serializable
data class PlanBackup(
    val name: String,
    val createdAt: Long,
    /** Path of this plan's PNG inside the zip, e.g. "plans/0.png". */
    val imageFile: String,
    val types: List<TypeBackup>,
)

@Serializable
data class TypeBackup(
    val name: String,
    val pinX: Float,
    val pinY: Float,
    val createdAt: Long,
    val measurements: List<MeasurementBackup>,
)

@Serializable
data class MeasurementBackup(
    val startedAt: Long,
    val durationSec: Int,
    val notes: String,
    val dataCap: DataCapConfig,
    val summary: MeasurementSummary,
    // Leaf rows reuse the Room entities directly; their id/measurementId are
    // ignored on import (reset to 0, then restamped by insertComplete).
    val samples: List<SampleEntity>,
    val servingCells: List<ServingCellEntity>,
    val neighborCells: List<NeighborCellEntity>,
)

/** What an import actually restored, for an honest confirmation message. */
data class ImportSummary(
    val plans: Int,
    val types: Int,
    val measurements: Int,
)
