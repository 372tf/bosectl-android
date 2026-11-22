package eu.depau.bosectl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.R
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.data.DeviceRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(onBack: () -> Unit) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    val starred = state.favorites?.starred ?: emptySet()
    val widgetModeIds = state.widgetModeIds
    val visibleModes = state.modes.filter { !it.isFreeSlot }
        .sortedWith(
            compareByDescending<ModeConfig> { it.modeIdx in starred }
                .thenByDescending { it.modeIdx in widgetModeIds }
                .thenBy { it.modeIdx }
        )
    val freeSlot = state.modes.firstOrNull { it.isFreeSlot }?.modeIdx

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (freeSlot != null && state.connected) {
                ExtendedFloatingActionButton(
                    onClick = { editorTarget = EditorTarget(null, freeSlot) },
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    text = { Text("New mode") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 88.dp
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            item(key = "legend") {
                Text(
                    "家アイコン: ウィジェット表示（未選択時は★を表示） / ★: 本体ショートカット",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            items(visibleModes, key = { it.modeIdx }) { mode ->
                ModeListCard(
                    mode = mode,
                    active = mode.modeIdx == state.currentModeIdx,
                    starred = mode.modeIdx in starred,
                    inWidget = mode.modeIdx in widgetModeIds,
                    enabled = state.connected,
                    onClick = { deviceAction { DeviceRepository.setMode(mode.modeIdx) } },
                    onToggleWidget = {
                        deviceAction { DeviceRepository.toggleWidgetMode(mode.modeIdx) }
                    },
                    onToggleStar = {
                        deviceAction { DeviceRepository.toggleStar(mode.modeIdx) }
                    },
                    onOpenSheet = { editorTarget = EditorTarget(mode, mode.modeIdx) },
                )
            }
        }
    }

    editorTarget?.let { target ->
        ProfileEditorSheet(
            mode = target.mode,
            slot = target.slot,
            onDismiss = { editorTarget = null },
        )
    }
}

data class EditorTarget(val mode: ModeConfig?, val slot: Int)

@Composable
private fun ModeListCard(
    mode: ModeConfig,
    active: Boolean,
    starred: Boolean,
    inWidget: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleWidget: () -> Unit,
    onToggleStar: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                promptIcon(mode.promptId), contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    mode.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    // On the tinted active card, "primary" barely separates from
                    // the container; use the matching on-container colour.
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (active) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (active) {
                IconButton(onClick = onOpenSheet) {
                    Icon(
                        if (mode.editable) AppIcons.Edit else AppIcons.Visibility,
                        contentDescription = if (mode.editable) "Edit" else "View",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            IconButton(onClick = onToggleWidget) {
                val badgeBg = when {
                    !inWidget -> Color.Transparent
                    active -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                }
                val iconTint = when {
                    inWidget && active -> MaterialTheme.colorScheme.onPrimaryContainer
                    inWidget -> MaterialTheme.colorScheme.primary
                    active -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(badgeBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_home),
                        contentDescription = if (inWidget) "Remove from widget" else "Show on widget",
                        modifier = Modifier.size(20.dp),
                        tint = iconTint,
                    )
                }
            }
            IconButton(onClick = onToggleStar, enabled = enabled) {
                if (starred) {
                    Icon(
                        AppIcons.StarFilled, contentDescription = "Unstar",
                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(AppIcons.Star, contentDescription = "Star")
                }
            }
        }
    }
}
