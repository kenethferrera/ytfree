/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Manages the state and transitions of Flow's YouTube Music-style hybrid queue.
 *
 * Distinctly separates:
 * 1. Playback Source (playlist, album, search, single track)
 * 2. User Additions (Play Next, Add to Queue)
 * 3. Autoplay Recommendations (appended by MusicBrain when the queue is exhausted)
 */
class HybridMusicQueueCoordinator {
    private val _session = MutableStateFlow(PlaybackSession())
    val session: StateFlow<PlaybackSession> = _session.asStateFlow()

    private val _queueEntries = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queueEntries: StateFlow<List<QueueEntry>> = _queueEntries.asStateFlow()

    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = _queue.asStateFlow()

    private val _currentQueueIndex = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = _currentTrack.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _autoplayEnabled = MutableStateFlow(true)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    private val _playbackSource = MutableStateFlow<PlaybackSourceInfo?>(null)
    val playbackSource: StateFlow<PlaybackSourceInfo?> = _playbackSource.asStateFlow()

    private val _automixItems = MutableStateFlow<List<MusicTrack>>(emptyList())
    val automixItems: StateFlow<List<MusicTrack>> = _automixItems.asStateFlow()

    /**
     * Initializes playback from a source (playlist, album, search, or radio).
     */
    fun loadSource(
        source: PlaybackSourceInfo,
        tracks: List<MusicTrack>,
        startIndex: Int = 0,
        startShuffled: Boolean = false,
    ) {
        val entries =
            tracks.map { track ->
                QueueEntry(
                    track = track,
                    source = source.type,
                )
            }

        val resolvedStartIndex = if (startIndex in entries.indices) startIndex else 0
        val originalEntries = entries.toList()

        val (activeEntries, activeIndex) =
            if (startShuffled && entries.size > 1) {
                val shuffled = HybridQueueOperations.shuffleRemaining(entries, resolvedStartIndex)
                shuffled.items to shuffled.currentIndex
            } else {
                entries to resolvedStartIndex
            }

        val newSession =
            PlaybackSession(
                source = source,
                originalEntries = originalEntries,
                activeEntries = activeEntries,
                currentIndex = activeIndex,
                shuffleEnabled = startShuffled,
                repeatMode = _repeatMode.value,
                autoplayEnabled = _autoplayEnabled.value,
                history = if (activeIndex > 0) activeEntries.take(activeIndex) else emptyList(),
            )

        applySession(newSession)
    }

    /**
     * Inserts [track] immediately after the currently playing song (Play Next).
     * Returns the 0-based insertion index in the active queue.
     */
    fun playNext(track: MusicTrack): Int {
        val entry =
            QueueEntry(
                track = track,
                source = QueueSource.PLAY_NEXT,
            )

        val currentSession = _session.value
        val result =
            HybridQueueOperations.insertPlayNext(
                entries = currentSession.activeEntries,
                currentIndex = currentSession.currentIndex,
                newEntry = entry,
            )

        val insertIndex =
            if (currentSession.currentIndex in currentSession.activeEntries.indices) {
                currentSession.currentIndex + 1
            } else {
                currentSession.activeEntries.size
            }

        // Also track in originalEntries so restoring order later doesn't discard user additions
        val updatedOriginal =
            currentSession.originalEntries.toMutableList().apply {
                val originalIndex = indexOfFirst { it.id == currentSession.currentEntry?.id }
                if (originalIndex >= 0) {
                    add(originalIndex + 1, entry)
                } else {
                    add(entry)
                }
            }

        applySession(
            currentSession.copy(
                activeEntries = result.items,
                originalEntries = updatedOriginal,
                currentIndex = result.currentIndex,
            ),
        )

        return insertIndex
    }

    /**
     * Appends [track] to the end of user-scheduled songs (before any autoplay recommendations).
     * Returns the 0-based insertion index in the active queue.
     */
    fun addToUserQueue(track: MusicTrack): Int {
        val entry =
            QueueEntry(
                track = track,
                source = QueueSource.MANUAL_QUEUE,
            )

        val currentSession = _session.value
        val searchStart =
            if (currentSession.currentIndex in currentSession.activeEntries.indices) {
                currentSession.currentIndex + 1
            } else {
                0
            }
        val firstAutoplayIndex =
            currentSession.activeEntries.indices
                .filter { it >= searchStart }
                .firstOrNull { currentSession.activeEntries[it].source == QueueSource.AUTOPLAY }

        val insertIndex = firstAutoplayIndex ?: currentSession.activeEntries.size

        val result =
            HybridQueueOperations.addToUserQueue(
                entries = currentSession.activeEntries,
                currentIndex = currentSession.currentIndex,
                newEntry = entry,
            )

        val updatedOriginal =
            currentSession.originalEntries.toMutableList().apply {
                add(entry)
            }

        applySession(
            currentSession.copy(
                activeEntries = result.items,
                originalEntries = updatedOriginal,
                currentIndex = result.currentIndex,
            ),
        )

        return insertIndex
    }

    /**
     * Appends a batch of algorithmic recommendations to the tail of the queue.
     */
    fun appendAutoplayBatch(tracks: List<MusicTrack>): List<QueueEntry> {
        val currentSession = _session.value
        val deduplicated =
            HybridQueueOperations.filterAutoplayCandidates(
                candidates = tracks,
                activeEntries = currentSession.activeEntries,
                history = currentSession.history,
            )

        if (deduplicated.isEmpty()) return emptyList()

        val newEntries =
            deduplicated.map { track ->
                QueueEntry(
                    track = track,
                    source = QueueSource.AUTOPLAY,
                )
            }

        val updatedActive = currentSession.activeEntries + newEntries

        applySession(
            currentSession.copy(activeEntries = updatedActive),
        )

        return newEntries
    }

    /**
     * Toggles shuffle on or off.
     */
    fun toggleShuffle(enabled: Boolean) {
        val currentSession = _session.value
        if (currentSession.shuffleEnabled == enabled) return

        val newSession =
            if (enabled) {
                val shuffled =
                    HybridQueueOperations.shuffleRemaining(
                        entries = currentSession.activeEntries,
                        currentIndex = currentSession.currentIndex,
                    )
                currentSession.copy(
                    activeEntries = shuffled.items,
                    currentIndex = shuffled.currentIndex,
                    shuffleEnabled = true,
                )
            } else {
                val restored =
                    HybridQueueOperations.restoreOriginalOrder(
                        originalEntries = currentSession.originalEntries,
                        activeEntries = currentSession.activeEntries,
                        currentIndex = currentSession.currentIndex,
                    )
                currentSession.copy(
                    activeEntries = restored.items,
                    currentIndex = restored.currentIndex,
                    shuffleEnabled = false,
                )
            }

        applySession(newSession)
    }

    fun setShuffle(enabled: Boolean) = toggleShuffle(enabled)

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        _session.update { it.copy(repeatMode = mode) }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        _autoplayEnabled.value = enabled
        _session.update { it.copy(autoplayEnabled = enabled) }
    }

    fun setAutomixItems(items: List<MusicTrack>) {
        _automixItems.value = items
    }

    fun removeAutomixItem(videoId: String) {
        _automixItems.update { current -> current.filterNot { it.videoId == videoId } }
    }

    /**
     * Moves an item from [fromIndex] to [toIndex].
     */
    fun move(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val currentSession = _session.value
        val result =
            HybridQueueOperations.move(
                entries = currentSession.activeEntries,
                currentIndex = currentSession.currentIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
            ) ?: return

        applySession(
            currentSession.copy(
                activeEntries = result.items,
                currentIndex = result.currentIndex,
            ),
        )
    }

    /**
     * Removes the item at [index].
     */
    fun removeAt(index: Int): QueueEntry? {
        val currentSession = _session.value
        val removal =
            HybridQueueOperations.removeAt(
                entries = currentSession.activeEntries,
                currentIndex = currentSession.currentIndex,
                index = index,
            ) ?: return null

        val updatedOriginal = currentSession.originalEntries.filterNot { it.id == removal.removedItem.id }

        applySession(
            currentSession.copy(
                activeEntries = removal.queue.items,
                originalEntries = updatedOriginal,
                currentIndex = removal.queue.currentIndex,
            ),
        )

        return removal.removedItem
    }

    /**
     * Updates the active track position when the player transitions media items.
     */
    fun onMediaItemTransition(
        playerIndex: Int,
        mediaId: String?,
        queueEntryId: String? = null,
    ) {
        val currentSession = _session.value
        val active = currentSession.activeEntries
        if (active.isEmpty()) return

        val targetIndex =
            when {
                queueEntryId != null -> {
                    active.indexOfFirst { it.id == queueEntryId }.takeIf { it >= 0 }
                        ?: (if (playerIndex in active.indices && active[playerIndex].track.videoId == mediaId) playerIndex else null)
                        ?: (if (mediaId != null) active.indexOfFirst { it.track.videoId == mediaId }.takeIf { it >= 0 } else null)
                        ?: playerIndex
                }

                playerIndex in active.indices && active[playerIndex].track.videoId == mediaId -> {
                    playerIndex
                }

                mediaId != null -> {
                    active.indexOfFirst { it.track.videoId == mediaId }.takeIf { it >= 0 } ?: playerIndex
                }

                else -> {
                    playerIndex
                }
            }.coerceIn(0, active.lastIndex)

        val previousEntry = currentSession.currentEntry
        val updatedHistory =
            if (previousEntry != null && previousEntry.id != active[targetIndex].id) {
                (currentSession.history + previousEntry).takeLast(50)
            } else {
                currentSession.history
            }

        applySession(
            currentSession.copy(
                currentIndex = targetIndex,
                history = updatedHistory,
            ),
        )
    }

    /**
     * Clears all user-added tracks from upcoming queue.
     */
    fun clearUserQueue() {
        val currentSession = _session.value
        val currentIdx = currentSession.currentIndex
        val historyAndCurrent = currentSession.activeEntries.take(currentIdx + 1)
        val upcomingWithoutUser = currentSession.activeEntries.drop(currentIdx + 1).filterNot { it.isUserAdded }
        val upcomingUserIds =
            currentSession.activeEntries
                .drop(currentIdx + 1)
                .filter { it.isUserAdded }
                .map { it.id }
                .toSet()

        applySession(
            currentSession.copy(
                activeEntries = historyAndCurrent + upcomingWithoutUser,
                originalEntries = currentSession.originalEntries.filterNot { it.id in upcomingUserIds },
            ),
        )
    }

    /**
     * Replaces or restores the full session from persistence.
     */
    fun restoreSession(session: PlaybackSession) {
        applySession(session)
    }

    private fun applySession(session: PlaybackSession) {
        _session.value = session
        _queueEntries.value = session.activeEntries
        _queue.value = session.activeEntries.map { it.track }
        _currentQueueIndex.value = session.currentIndex
        _currentTrack.value = session.currentTrack
        _shuffleEnabled.value = session.shuffleEnabled
        _repeatMode.value = session.repeatMode
        _autoplayEnabled.value = session.autoplayEnabled
        _playbackSource.value = session.source
    }
}
