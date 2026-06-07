package com.rvthak.netsurvey.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Permissions the radio reads depend on (SPEC §2). */
val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_PHONE_STATE,
)

fun hasAllPermissions(context: Context): Boolean =
    REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** State + launcher for the runtime permission flow. */
class PermissionController(
    val granted: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberPermissionController(): PermissionController {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it } || hasAllPermissions(context)
    }
    return PermissionController(
        granted = granted,
        request = { launcher.launch(REQUIRED_PERMISSIONS) },
    )
}
