package com.rvthak.netsurvey.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Temporary container for the Phase 1/3 spike screens (removed once the map ships). */
@Composable
fun DebugHost(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Probe") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Run") })
        }
        when (tab) {
            0 -> ProbeScreen(Modifier.fillMaxSize())
            else -> RunDebugScreen(Modifier.fillMaxSize())
        }
    }
}
