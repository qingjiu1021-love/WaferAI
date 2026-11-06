package com.example.waferai.ui.chat

import com.example.waferai.ui.theme.GlassTextButton as TextButton
import com.example.waferai.ui.theme.GlassDropdownMenu as DropdownMenu
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.waferai.data.ai.ChatThinking
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun ChatActionsMenu(
    open: Boolean, thinking: ChatThinking, onDismiss: () -> Unit, onCamera: () -> Unit,
    onPhoto: () -> Unit, onFiles: () -> Unit, onPlugins: () -> Unit, onThinking: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(240.dp),
        offset = DpOffset(0.dp, (-6).dp),
        shape = RoundedCornerShape(26.dp),
        containerColor = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        properties = PopupProperties(focusable = false)
    ) {
        ActionRow("拍照", StudioIcon.CAMERA, onCamera)
        ActionRow("照片", StudioIcon.PHOTO, onPhoto)
        ActionRow("文件", StudioIcon.ATTACH, onFiles)
        ActionRow("插件", StudioIcon.PLUG, onPlugins)
        ActionRow("深入思考", StudioIcon.THINK, onThinking,
            selected = thinking != ChatThinking.DEFAULT && thinking != ChatThinking.OFF)
    }
}

@Composable
private fun ActionRow(title: String, icon: StudioIcon, onClick: () -> Unit, selected: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurface
    DropdownMenuItem(
        modifier = Modifier.heightIn(min = 56.dp),
        text = { Text(title, color = tint, style = MaterialTheme.typography.titleSmall) },
        onClick = onClick,
        leadingIcon = {
            Surface(Modifier.size(40.dp), shape = CircleShape, color = scheme.surfaceContainerHighest) {
                Box(contentAlignment = Alignment.Center) { StudioGlyph(icon, Modifier.size(21.dp), tint) }
            }
        },
        trailingIcon = if (selected) {
            { StudioGlyph(StudioIcon.CHECK, Modifier.size(20.dp), tint) }
        } else null
    )
}

@Composable
internal fun ChatThinkingDialog(
    options: List<ChatThinking>, selected: ChatThinking, onDismiss: () -> Unit, onSelect: (ChatThinking) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("思考强度") },
        text = {
            Column {
                if (options.isEmpty()) {
                    Text("当前模型暂不支持调节。选择支持思考强度的在线模型后即可设置。")
                } else {
                    Text("可用档位由模型决定。较高强度可能耗时更长、消耗更多额度。",
                        style = MaterialTheme.typography.bodySmall)
                    options.forEach { effort ->
                        DropdownMenuItem(
                            text = { Text(effort.label) },
                            onClick = { onSelect(effort) },
                            trailingIcon = if (effort == selected) {
                                { StudioGlyph(StudioIcon.CHECK, Modifier.size(18.dp), MaterialTheme.colorScheme.primary) }
                            } else null
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭") } }
    )
}
