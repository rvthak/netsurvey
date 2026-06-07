package com.rvthak.netsurvey.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        PlanEntity::class,
        MeasurementTypeEntity::class,
        MeasurementEntity::class,
        SampleEntity::class,
        ServingCellEntity::class,
        NeighborCellEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class NetSurveyDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao
    abstract fun typeDao(): TypeDao
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile private var instance: NetSurveyDatabase? = null

        fun get(context: Context): NetSurveyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NetSurveyDatabase::class.java,
                    "netsurvey.db",
                ).build().also { instance = it }
            }
    }
}
