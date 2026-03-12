package com.droidprobe.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.input.KeyboardType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggestedValues: List<String>,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    supportingText: @Composable (() -> Unit)? = null
) {
    val placeholderContent: @Composable (() -> Unit)? = placeholder?.let {
        { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
    }

    if (suggestedValues.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholderContent,
            supportingText = supportingText,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
        )
    } else {
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    expanded = true
                },
                label = { Text(label) },
                placeholder = placeholderContent,
                supportingText = supportingText,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )

            // Filter suggestions based on current text
            val filtered = if (value.isBlank()) suggestedValues
            else suggestedValues.filter { it.contains(value, ignoreCase = true) }

            if (filtered.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    filtered.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                onValueChange(suggestion)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
