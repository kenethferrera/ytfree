/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import androidx.compose.runtime.Immutable
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The origin of tracks loaded into the playback sequence.
 */
@Serializable
enum class QueueSource {
    PLAYLIST,
    ALBUM,
    SEARCH,
    SINGLE_TRACK,
    PLAY_NEXT,
    MANUAL_QUEUE,
    AUTOPLAY,
    RADIO,
}

/**
 * Identifies the high-level playback source (e.g. a specific playlist or album).
 */
@Immutable
@Serializable
data class PlaybackSourceInfo(
    val type: QueueSource,
    val id: String? = null,
    val title: String,
)

/**
 * A discrete entry in the playback queue.
 *
 * Using unique [id]s allows the same song to appear at multiple legitimate positions in a playlist
 * or user queue without identity collisions or key ambiguity in Compose.
 */
@Immutable
@Serializable
data class QueueEntry(
    val id: String = UUID.randomUUID().toString(),
    val track: MusicTrack,
    val source: QueueSource,
    val addedAt: Long = System.currentTimeMillis(),
    val isUserAdded: Boolean = (source == QueueSource.PLAY_NEXT || source == QueueSource.MANUAL_QUEUE),
)

/**
 * Snapshot of the active music playback session.
 */
@Immutable
@Serializable
data class PlaybackSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val source: PlaybackSourceInfo? = null,
    val originalEntries: List<QueueEntry> = emptyList(),
    val activeEntries: List<QueueEntry> = emptyList(),
    val currentIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val autoplayEnabled: Boolean = true,
    val history: List<QueueEntry> = emptyList(),
) {
    val currentEntry: QueueEntry?
        get() = activeEntries.getOrNull(currentIndex)

    val id: String
        get() = sessionId

    val currentTrack: MusicTrack?
        get() = currentEntry?.track

    val upcomingEntries: List<QueueEntry>
        get() =
            if (currentIndex in activeEntries.indices && currentIndex < activeEntries.lastIndex) {
                activeEntries.subList(currentIndex + 1, activeEntries.size)
            } else {
                emptyList()
            }

    val isExhausted: Boolean
        get() = activeEntries.isEmpty() || currentIndex >= activeEntries.lastIndex
}
