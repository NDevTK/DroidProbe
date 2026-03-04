package com.droidprobe.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun DroidProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(LocalContext.current),
        typography = Typography,
        content = content
    )
}
