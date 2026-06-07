package com.rvthak.netsurvey.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A measurement with its raw samples and captured cells (for the detail screen). */
data class MeasurementWithDetails(
    @Embedded val measurement: MeasurementEntity,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val samples: List<SampleEntity>,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val servingCells: List<ServingCellEntity>,
    @Relation(parentColumn = "id", entityColumn = "measurementId")
    val neighborCells: List<NeighborCellEntity>,
)
