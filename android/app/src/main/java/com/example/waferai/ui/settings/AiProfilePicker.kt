package com.example.waferai.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import com.example.waferai.ui.theme.UiText as Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.waferai.data.ai.AiProfilesState
import com.example.waferai.ui.theme.GlassButton

@Composable
fun AiProfilePicker(library: AiProfilesState, onSelect: (String) -> Unit, onOpen: () -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        GlassButton(
            onClick = { if (library.active == null) onOpen() else expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            Text(library.active?.let { "当前模型 · ${it.selectionName} ▾" } ?: "添加 AI 模型",
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.width(maxWidth)) {
            library.profiles.forEach { profile ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(profile.selectionName)
                            Text(profile.selectionDetail, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingIcon = { if (profile.id == library.activeId) Text("✓") },
                    onClick = {
                        expanded = false
                        onSelect(profile.id)
                    }
                )
            }
        }
    }
}
