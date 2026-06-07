package com.rvthak.netsurvey.ui.map

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rvthak.netsurvey.data.NetSurveyRepository
import com.rvthak.netsurvey.data.SettingsRepository
import com.rvthak.netsurvey.data.db.PlanEntity
import com.rvthak.netsurvey.stats.TypeAggregate
import com.rvthak.netsurvey.ui.common.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A pin on the map = one [com.rvthak.netsurvey.data.db.MeasurementTypeEntity].
 * Position is stored as 0..1 fractions of the image, so it survives zoom/scale.
 * [valueLabel] and [color] reflect the configured **primary metric** (SPEC §8):
 * the equal-weight aggregate of this spot's measurements, coloured green→red by
 * the metric's threshold band. Both are `null` until the spot has a measured value
 * for the current primary metric — the canvas then falls back to name + a neutral
 * dot rather than implying "great".
 */
data class PinUi(
    val typeId: Long,
    val name: String,
    val xFrac: Float,
    val yFrac: Float,
    val measurementCount: Int,
    val valueLabel: String? = null,
    val color: Color? = null,
)

/**
 * Drives the map shell (SPEC §8): the list of plans, which one is selected, and
 * the pins on the selected plan. Pins are not yet wired to the measurement flow
 * (Phase 5) nor coloured by value (Phase 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = NetSurveyRepository(app)
    private val settingsRepo = SettingsRepository(app)

    /** Real plans only — the Phase-3 debug placeholder (empty imageUri) is hidden. */
    val plans: StateFlow<List<PlanEntity>> =
        repo.observePlans()
            .map { list -> list.filter { it.imageUri.isNotEmpty() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedPlanId = MutableStateFlow<Long?>(null)

    /** The selected plan, defaulting to the first one when nothing is chosen yet. */
    val selectedPlan: StateFlow<PlanEntity?> =
        combine(plans, selectedPlanId) { list, chosen ->
            list.firstOrNull { it.id == chosen } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val activePlanId: Flow<Long?> = selectedPlan.map { it?.id }.distinctUntilChanged()

    val pins: StateFlow<List<PinUi>> =
        activePlanId.flatMapLatest { id ->
            if (id == null) {
                flowOf(emptyList())
            } else {
                combine(
                    repo.observeTypes(id),
                    repo.observeMeasurementsForPlan(id),
                    settingsRepo.settings,
                ) { types, measurements, settings ->
                    // Settings is just another flow input, so switching the primary
                    // metric or editing a band recolours/relabels every pin live.
                    val metric = settings.primaryMetric
                    val band = settings.band(metric)
                    val byType = measurements.groupBy { it.typeId }
                    types.map { t ->
                        val mine = byType[t.id].orEmpty()
                        val value = TypeAggregate.from(mine.map { it.summary }).value(metric)
                        PinUi(
                            typeId = t.id,
                            name = t.name,
                            xFrac = t.pinX,
                            yFrac = t.pinY,
                            measurementCount = mine.size,
                            valueLabel = if (value == null) null else Format.metric(metric, value),
                            color = metricColor(value, band),
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectPlan(id: Long) {
        selectedPlanId.value = id
    }

    /** Copy the picked PNG into app storage, then select the new plan. */
    fun addPlan(name: String, source: Uri) = viewModelScope.launch {
        val id = repo.createPlan(name.trim().ifEmpty { "Plan" }, source)
        selectedPlanId.value = id
    }

    /** Drop a new measurement spot at the given image fraction on the selected plan. */
    fun addType(name: String, xFrac: Float, yFrac: Float) = viewModelScope.launch {
        val planId = selectedPlan.value?.id ?: return@launch
        repo.createType(planId, name.trim().ifEmpty { "Spot" }, xFrac, yFrac)
    }
}
