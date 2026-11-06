package com.example.waferai.ui.chat

import com.example.waferai.ui.theme.GlassButton as Button
import com.example.waferai.ui.theme.GlassTextButton as TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.waferai.data.ai.ChatArchive
import com.example.waferai.ui.theme.StudioGlyph
import com.example.waferai.ui.theme.StudioIcon
import com.example.waferai.ui.theme.UiText as Text

@Composable
internal fun ChatHistoryDrawer(
    drawer: DrawerState,
    archive: ChatArchive,
    busy: Boolean,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onTasks: () -> Unit
) {
    val conversations = archive.conversations.filter { it.turns.isNotEmpty() }
    ModalDrawerSheet(
        drawerState = drawer,
        modifier = Modifier.width(LocalConfiguration.current.screenWidthDp.dp / 2).fillMaxHeight(),
        windowInsets = WindowInsets(0, 0, 0, 0),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("对话历史", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClose) { Text("关闭") }
            }
            NavigationDrawerItem(
                label = { Text("定时任务") },
                selected = false,
                onClick = onTasks,
                icon = { StudioGlyph(StudioIcon.CLOCK, Modifier.size(18.dp), LocalContentColor.current) }
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (conversations.isEmpty()) item {
                    Text("还没有保存的对话。", Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(conversations, key = { it.id }) { chat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NavigationDrawerItem(
                            label = { Text(chat.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            selected = chat.id == archive.activeId,
                            onClick = { if (!busy) onOpen(chat.id) },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton({ onDelete(chat.id) }, enabled = !busy) { Text("删除") }
                    }
                }
            }
            HorizontalDivider()
            Button(onNew, enabled = !busy,
                modifier = Modifier.align(Alignment.End).padding(top = 10.dp)) {
                StudioGlyph(StudioIcon.PLUS, Modifier.size(16.dp), LocalContentColor.current)
                Text("新对话", Modifier.padding(start = 6.dp))
            }
        }
    }
}
