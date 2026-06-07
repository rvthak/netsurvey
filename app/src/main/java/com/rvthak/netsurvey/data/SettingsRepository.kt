package com.rvthak.netsurvey.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rvthak.netsurvey.model.AppSettings
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.ThresholdBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * App settings persisted as a single JSON blob in Preferences DataStore. One blob
 * keeps reads/writes atomic and makes the export bundle (Phase 7) trivial.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("app_settings")

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings.DEFAULT
    }

    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
            } ?: AppSettings.DEFAULT
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    suspend fun setPrimaryMetric(metric: Metric) = update { it.copy(primaryMetricKey = metric.key) }

    suspend fun setThreshold(metric: Metric, band: ThresholdBand) = update {
        it.copy(thresholds = it.thresholds.toMutableMap().apply { put(metric.key, band) })
    }

    suspend fun setDefaultDataCap(cap: DataCapConfig) = update { it.copy(defaultDataCap = cap) }

    suspend fun replaceAll(settings: AppSettings) = update { settings }
}
