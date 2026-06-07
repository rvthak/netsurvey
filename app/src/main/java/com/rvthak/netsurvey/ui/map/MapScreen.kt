package com.rvthak.netsurvey.ui.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rvthak.netsurvey.data.db.PlanEntity

/**
 * The home screen *is* the floor plan (SPEC §8): a plan selector, an add-plan
 * action, and the pan/zoom canvas with its pins. Long-pressing the canvas drops a
 * new measurement spot; tapping a pin opens its (stub) detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenType: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
    vm: MapViewModel = viewModel(),
) {
    val plans by vm.plans.collectAsStateWithLifecycle()
    val selectedPlan by vm.selectedPlan.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()

    var pendingImage by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingPin by remember { mutableStateOf<Offset?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingImage = uri
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { PlanSelector(plans, selectedPlan, vm::selectPlan) },
                actions = {
                    IconButton(onClick = { picker.launch("image/*") }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add plan")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = { menuOpen = false; onOpenSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("Debug tools") },
                                onClick = { menuOpen = false; onOpenDebug() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val plan = selectedPlan
            if (plan == null) {
                EmptyState(onAdd = { picker.launch("image/*") })
            } else {
                MapCanvas(
                    imagePath = plan.imageUri,
                    pins = pins,
                    onPinTap = onOpenType,
                    onLongPressEmpty = { fx, fy -> pendingPin = Offset(fx, fy) },
                    modifier = Modifier.fillMaxSize(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                ) {
                    Text(
                        "Long-press the plan to drop a measurement spot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    pendingImage?.let { uri ->
        NameDialog(
            title = "Name this plan",
            confirmLabel = "Add",
            onConfirm = { name -> vm.addPlan(name, uri); pendingImage = null },
            onDismiss = { pendingImage = null },
        )
    }
    pendingPin?.let { f ->
        NameDialog(
            title = "Name this spot",
            confirmLabel = "Create",
            onConfirm = { name -> vm.addType(name, f.x, f.y); pendingPin = null },
            onDismiss = { pendingPin = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSelector(
    plans: List<PlanEntity>,
    selected: PlanEntity?,
    onSelect: (Long) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = plans.isNotEmpty()) {
            Text(selected?.name ?: "NetSurvey", maxLines = 1)
            if (plans.size > 1) Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch plan")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            plans.forEach { plan ->
                DropdownMenuItem(
                    text = { Text(plan.name) },
                    onClick = { onSelect(plan.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No floor plans yet", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Add a floor-plan image (e.g. exported from FloorSketch), then long-press it to drop measurement spots.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Text("  Add a plan")
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val submit = { if (text.isNotBlank()) onConfirm(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.widthIn(min = 240.dp),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
