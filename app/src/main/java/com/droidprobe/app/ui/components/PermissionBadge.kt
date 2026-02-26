package com.droidprobe.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidprobe.app.ui.theme.ExportedBadge
import com.droidprobe.app.ui.theme.ProtectedBadge
import com.droidprobe.app.ui.theme.SecureBadge

@Composable
fun PermissionBadge(
    isExported: Boolean,
    permission: String?,
    modifier: Modifier = Modifier
) {
    val (text, color) = when {
        !isExported -> "Not Exported" to SecureBadge
        permission != null -> "Protected" to ProtectedBadge
        else -> "Exported" to ExportedBadge
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
