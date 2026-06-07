package com.rvthak.netsurvey.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.MeasurementSummary
import com.rvthak.netsurvey.model.SampleKind
import com.rvthak.netsurvey.telephony.CellRole
import com.rvthak.netsurvey.telephony.CellTech
import kotlinx.serialization.Serializable

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** App-internal copy of the floor-plan PNG (SPEC §8). */
    val imageUri: String,
    val createdAt: Long,
)

@Entity(
    tableName = "types",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId")],
)
data class MeasurementTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val name: String,
    /** Pin position as a fraction (0..1) of the image, so it survives zoom/scale. */
    val pinX: Float,
    val pinY: Float,
    val createdAt: Long,
)

@Entity(
    tableName = "measurements",
    foreignKeys = [
        ForeignKey(
            entity = MeasurementTypeEntity::class,
            parentColumns = ["id"],
            childColumns = ["typeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("typeId")],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val typeId: Long,
    val startedAt: Long,
    val durationSec: Int,
    val notes: String = "",
    @Embedded(prefix = "cap_") val dataCap: DataCapConfig = DataCapConfig.DEFAULT,
    @Embedded(prefix = "sum_") val summary: MeasurementSummary = MeasurementSummary(),
)

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = MeasurementEntity::class,
            parentColumns = ["id"],
            childColumns = ["measurementId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("measurementId")],
)
@Serializable
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measurementId: Long,
    val tOffsetMs: Long,
    val kind: SampleKind,
    // signal samples
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val radioType: String? = null,
    val band: String? = null,
    val carrier: String? = null,
    // probe samples
    val latencyMs: Long? = null,
    val ok: Boolean? = null,
)

@Entity(
    tableName = "serving_cells",
    foreignKeys = [
        ForeignKey(
            entity = MeasurementEntity::class,
            parentColumns = ["id"],
            childColumns = ["measurementId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("measurementId")],
)
@Serializable
data class ServingCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measurementId: Long,
    val tech: CellTech,
    val globalId: Long?,
    val derivedTowerId: Long?,
    val pci: Int?,
    val tac: Int?,
    val earfcn: Int?,
    val band: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val dwellMs: Long,
    /** PRIMARY anchor or a SECONDARY (CA / 5G-NSA) carrier used during the run. */
    val role: CellRole = CellRole.PRIMARY,
    /** Number of 1 Hz signal snapshots this cell was serving in. */
    val sampleCount: Int = 0,
    /** sampleCount as a percentage of the run's signal snapshots — the honest
     * "how much of this measurement used this tower" figure (time-share, since the
     * OS exposes no per-cell byte attribution). */
    val sharePct: Double? = null,
)

@Entity(
    tableName = "neighbor_cells",
    foreignKeys = [
        ForeignKey(
            entity = MeasurementEntity::class,
            parentColumns = ["id"],
            childColumns = ["measurementId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("measurementId")],
)
@Serializable
data class NeighborCellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measurementId: Long,
    val tech: CellTech,
    val pci: Int?,
    val earfcn: Int?,
    val globalId: Long?,
    val bestSignal: Int?,
)
