package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.player.queue.QueueEntry
import io.github.aedev.flow.ui.components.shared.CollectionEditDialog

/** The pull-up queue sheet used where there is no room for a side pane. */
@Composable
internal fun QueueSheet(
    sheetCornerRadius: Dp,
    queue: List<MusicTrack>,
    radioTracks: List<MusicTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    isRadioLoading: Boolean,
    endlessRadioEnabled: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    downloadedTrackIds: Set<String>,
    swipes: QueueSwipes,
    actions: QueueActions,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    queueEntries: List<QueueEntry> = emptyList(),
) {
    val selection = remember { QueueSelection() }
    var showSaveQueueDialog by rememberSaveable { mutableStateOf(false) }

    if (showSaveQueueDialog) {
        CollectionEditDialog(
            title = stringResource(R.string.save_queue_dialog_title),
            confirmLabel = stringResource(R.string.save),
            onDismiss = { showSaveQueueDialog = false },
            onConfirm = { name, _ ->
                showSaveQueueDialog = false
                actions.onSaveQueueAsPlaylist?.invoke(name)
            },
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            showDescription = false,
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = sheetCornerRadius, topEnd = sheetCornerRadius),
        shadowElevation = 24.dp,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .then(dragHandleModifier),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 42.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                    )
                }
                QueueHeader(
                    trackCount = queue.size,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onShuffleQueue = actions.onShuffleQueue,
                    onCycleRepeat = actions.onCycleRepeat,
                    onSelect = { selection.start() },
                    onSaveQueue =
                        if (queue.isNotEmpty() &&
                            actions.onSaveQueueAsPlaylist != null
                        ) {
                            ({ showSaveQueueDialog = true })
                        } else {
                            null
                        },
                    onClearUserQueue = actions.onClearUserQueue,
                    hasUserQueue = queueEntries.any { it.isUserAdded },
                )
            }

            QueueList(
                queue = queue,
                radioTracks = radioTracks,
                currentIndex = currentIndex,
                isPlaying = isPlaying,
                isRadioLoading = isRadioLoading,
                endlessRadioEnabled = endlessRadioEnabled,
                downloadedTrackIds = downloadedTrackIds,
                swipes = swipes,
                actions = actions,
                modifier = Modifier.weight(1f),
                selection = selection,
                queueEntries = queueEntries,
            )
        }
    }
}
