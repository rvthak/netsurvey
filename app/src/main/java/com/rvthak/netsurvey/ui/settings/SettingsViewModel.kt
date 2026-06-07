package com.rvthak.netsurvey.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.SettingsRepository
import com.rvthak.netsurvey.data.backup.BackupManager
import com.rvthak.netsurvey.data.db.PlanEntity
import com.rvthak.netsurvey.model.AppSettings
import com.rvthak.netsurvey.model.DataCapConfig
import com.rvthak.netsurvey.model.Metric
import com.rvthak.netsurvey.model.ThresholdBand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Settings screen (SPEC §8): the editable settings blob plus plan
 * management. Reads go through [SettingsRepository] / [NetSurveyRepository] flows;
 * every mutator is a fire-and-forget write — DataStore/Room re-emit, so the UI and
 * the live map both update on their own.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NetSurveyRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val backup = BackupManager(app)

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT)

    /** Real plans only — the Phase-3 debug placeholder (empty imageUri) is hidden. */
    val plans: StateFlow<List<PlanEntity>> =
        repo.observePlans()
            .map { list -> list.filter { it.imageUri.isNotEmpty() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPrimaryMetric(metric: Metric) = viewModelScope.launch {
        settingsRepo.setPrimaryMetric(metric)
    }

    fun setThreshold(metric: Metric, band: ThresholdBand) = viewModelScope.launch {
        settingsRepo.setThreshold(metric, band)
    }

    fun setDefaultDataCap(cap: DataCapConfig) = viewModelScope.launch {
        settingsRepo.setDefaultDataCap(cap)
    }

    fun renamePlan(plan: PlanEntity, newName: String) = viewModelScope.launch {
        val name = newName.trim()
        if (name.isNotEmpty()) repo.renamePlan(plan, name)
    }

    fun deletePlan(plan: PlanEntity) = viewModelScope.launch { repo.deletePlan(plan) }

    // --- backup (SPEC §9) ----------------------------------------------------

    /** One-shot user-facing messages (export/import results), shown as snackbars. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** True while an export/import is in flight, so the buttons can disable. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun suggestedBackupName(): String = backup.suggestedFileName()

    fun exportTo(dest: Uri) = viewModelScope.launch {
        _busy.value = true
        backup.export(dest)
            .onSuccess { count -> _messages.emit("Exported $count plan${plural(count)}.") }
            .onFailure { _messages.emit("Export failed: ${it.message ?: "unknown error"}") }
        _busy.value = false
    }

    fun importFrom(src: Uri) = viewModelScope.launch {
        _busy.value = true
        backup.import(src)
            .onSuccess { s ->
                _messages.emit(
                    "Imported ${s.plans} plan${plural(s.plans)}, " +
                        "${s.types} spot${plural(s.types)}, " +
                        "${s.measurements} measurement${plural(s.measurements)}.",
                )
            }
            .onFailure { _messages.emit("Import failed: ${it.message ?: "unknown error"}") }
        _busy.value = false
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
