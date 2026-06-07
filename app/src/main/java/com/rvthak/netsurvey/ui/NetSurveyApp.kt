package com.rvthak.netsurvey.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rvthak.netsurvey.ui.debug.DebugHost
import com.rvthak.netsurvey.ui.map.MapScreen
import com.rvthak.netsurvey.ui.measurement.MeasurementDetailScreen
import com.rvthak.netsurvey.ui.run.MeasurementRunScreen
import com.rvthak.netsurvey.ui.settings.SettingsScreen
import com.rvthak.netsurvey.ui.type.TypeDetailScreen

private object Routes {
    const val MAP = "map"
    const val TYPE = "type/{typeId}"
    const val RUN = "run/{typeId}"
    const val MEASUREMENT = "measurement/{measurementId}"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"

    fun type(typeId: Long) = "type/$typeId"
    fun run(typeId: Long) = "run/$typeId"
    fun measurement(measurementId: Long) = "measurement/$measurementId"
}

/** Top-level navigation graph. The map is home; the debug spike stays reachable. */
@Composable
fun NetSurveyApp() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAP, modifier = Modifier.fillMaxSize()) {
        composable(Routes.MAP) {
            MapScreen(
                onOpenType = { nav.navigate(Routes.type(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDebug = { nav.navigate(Routes.DEBUG) },
            )
        }
        composable(
            Routes.TYPE,
            arguments = listOf(navArgument("typeId") { type = NavType.LongType }),
        ) { entry ->
            val typeId = entry.arguments?.getLong("typeId") ?: 0L
            TypeDetailScreen(
                typeId = typeId,
                onBack = { nav.popBackStack() },
                onNewMeasurement = { nav.navigate(Routes.run(it)) },
                onOpenMeasurement = { nav.navigate(Routes.measurement(it)) },
            )
        }
        composable(
            Routes.RUN,
            arguments = listOf(navArgument("typeId") { type = NavType.LongType }),
        ) { entry ->
            val typeId = entry.arguments?.getLong("typeId") ?: 0L
            MeasurementRunScreen(
                typeId = typeId,
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() },
            )
        }
        composable(
            Routes.MEASUREMENT,
            arguments = listOf(navArgument("measurementId") { type = NavType.LongType }),
        ) { entry ->
            val measurementId = entry.arguments?.getLong("measurementId") ?: 0L
            MeasurementDetailScreen(
                measurementId = measurementId,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DEBUG) {
            DebugScreen(onBack = { nav.popBackStack() })
        }
    }
}

/** Wraps the Phase 1/3 spike tabs with a back affordance for hardware testing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        DebugHost(Modifier.padding(padding))
    }
}
