package com.rvthak.netsurvey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rvthak.netsurvey.ui.NetSurveyApp
import com.rvthak.netsurvey.ui.theme.NetSurveyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetSurveyTheme {
                // Phase 4: the map-centric shell. The Phase 1/3 spike screens stay
                // reachable via the "Debug tools" menu entry for hardware testing.
                NetSurveyApp()
            }
        }
    }
}
