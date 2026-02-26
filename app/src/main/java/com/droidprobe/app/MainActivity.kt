package com.droidprobe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.droidprobe.app.navigation.DroidProbeNavGraph
import com.droidprobe.app.ui.theme.DroidProbeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DroidProbeTheme {
                DroidProbeNavGraph()
            }
        }
    }
}
