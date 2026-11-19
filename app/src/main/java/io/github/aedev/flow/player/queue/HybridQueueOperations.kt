/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.PlaylistQueueOrder
import io.github.aedev.flow.player.QueueRemoval
import io.github.aedev.flow.player.ReorderedQueue
import kotlin.random.Random

/**
 * Functional queue manipulation algorithms for Flow's YouTube Music-style hybrid queue.
 *
 * Enforces strict separation between:
 * 1. Playback Source (original tracks order)
 * 2. User-controlled priority tracks (Play Next and Add to Queue)
 * 3. Algorithmic Autoplay recommendations (strictly placed after all scheduled items)
 */
internal object HybridQueueOperations {
    /**
     * Inserts [newEntry] immediately after the currently playing item.
     * Consecutive Play Next operations insert at `currentIndex + 1`, creating a newest-first
     * stack right behind the current track.
     */
    fun insertPlayNext(
        entries: List<QueueEntry>,
        currentIndex: Int,
        newEntry: QueueEntry,
    ): ReorderedQueue<QueueEntry> {
        val updated = entries.toMutableList()
        val insertIndex =
            if (currentIndex in entries.indices) {
                currentIndex + 1
            } else {
                entries.size
            }
        updated.add(insertIndex, newEntry)
        val updatedCurrentIndex =
            if (currentIndex >= 0 && currentIndex < updated.size) {
                currentIndex
            } else {
                0
            }
        return ReorderedQueue(updated, updatedCurrentIndex)
    }

    /**
     * Inserts [newEntry] at the end of the user-scheduled queue.
     *
     * If automatic [QueueSource.AUTOPLAY] recommendations are already buffered at the tail,
     * this inserts *before* the first autoplay track so manual additions always retain priority.
     */
    fun addToUserQueue(
        entries: List<QueueEntry>,
        currentIndex: Int,
        newEntry: QueueEntry,
    ): ReorderedQueue<QueueEntry> {
        val updated = entries.toMutableList()
        val searchStart = if (currentIndex in entries.indices) currentIndex + 1 else 0
        val firstAutoplayIndex =
            entries.indices
                .filter { it >= searchStart }
                .firstOrNull { entries[it].source == QueueSource.AUTOPLAY }

        if (firstAutoplayIndex != null) {
            updated.add(firstAutoplayIndex, newEntry)
        } else {
            updated.add(newEntry)
        }

        val updatedCurrentIndex =
            if (currentIndex >= 0 && currentIndex < updated.size) {
                currentIndex
            } else {
                0
            }
        return ReorderedQueue(updated, updatedCurrentIndex)
    }

    /**
     * Shuffles only upcoming scheduled tracks while keeping the current track playing at [currentIndex]
     * and preserving already-played history.
     */
    fun shuffleRemaining(
        entries: List<QueueEntry>,
        currentIndex: Int,
        random: Random = Random.Default,
    ): ReorderedQueue<QueueEntry> {
        if (entries.size <= 1 || currentIndex !in entries.indices) {
            return ReorderedQueue(entries, currentIndex)
        }

        val historyAndCurrent = entries.take(currentIndex + 1)
        val upcoming = entries.drop(currentIndex + 1)
        val shuffledUpcoming = upcoming.shuffled(random)

        return ReorderedQueue(
            items = historyAndCurrent + shuffledUpcoming,
            currentIndex = currentIndex,
        )
    }

    /**
     * Restores the queue to its original source sequence while preserving:
     * 1. The currently playing track identity and position.
     * 2. Any user-added songs ([QueueEntry.isUserAdded]), placed in their relative upcoming order.
     */
    fun restoreOriginalOrder(
        originalEntries: List<QueueEntry>,
        activeEntries: List<QueueEntry>,
        currentIndex: Int,
    ): ReorderedQueue<QueueEntry> {
        if (originalEntries.isEmpty()) {
            return ReorderedQueue(activeEntries, currentIndex)
        }

        val currentEntry =
            activeEntries.getOrNull(currentIndex)
                ?: return ReorderedQueue(originalEntries, 0)

        // Find user-added tracks currently in activeEntries
        val userAddedUpcoming =
            activeEntries
                .drop(currentIndex + 1)
                .filter { it.isUserAdded }
        val userAddedIds = userAddedUpcoming.map { it.id }.toSet()

        // Find autoplay recommendations in upcoming activeEntries
        val autoplayUpcoming =
            activeEntries
                .drop(currentIndex + 1)
                .filter { it.source == QueueSource.AUTOPLAY }

        // Find unplayed original entries
        val playedOriginalIds =
            activeEntries
                .take(currentIndex + 1)
                .map { it.id }
                .toSet()

        val remainingOriginal =
            originalEntries.filter { entry ->
                entry.id !in playedOriginalIds &&
                    entry.id !in userAddedIds &&
                    entry.source != QueueSource.AUTOPLAY
            }

        // Reconstruct: already played history up to current track + user-added + remaining original + autoplay
        val historyAndCurrent = activeEntries.take(currentIndex + 1)
        val restoredUpcoming = userAddedUpcoming + remainingOriginal + autoplayUpcoming

        return ReorderedQueue(
            items = historyAndCurrent + restoredUpcoming,
            currentIndex = currentIndex,
        )
    }

    /**
     * Moves an item from [fromIndex] to [toIndex], updating [currentIndex] accordingly.
     */
    fun move(
        entries: List<QueueEntry>,
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int,
    ): ReorderedQueue<QueueEntry>? = PlaylistQueueOrder.move(entries, currentIndex, fromIndex, toIndex)

    /**
     * Removes the item at [index], updating [currentIndex].
     */
    fun removeAt(
        entries: List<QueueEntry>,
        currentIndex: Int,
        index: Int,
    ): QueueRemoval<QueueEntry>? = PlaylistQueueOrder.removeAt(entries, currentIndex, index)

    /**
     * Deduplicates candidate tracks for Autoplay against existing queue items and recent history.
     * Does NOT touch intentional duplicate user additions in the queue.
     */
    fun filterAutoplayCandidates(
        candidates: List<MusicTrack>,
        activeEntries: List<QueueEntry>,
        history: List<QueueEntry> = emptyList(),
    ): List<MusicTrack> {
        val existingVideoIds = HashSet<String>(activeEntries.size + history.size)
        activeEntries.forEach { existingVideoIds.add(it.track.videoId) }
        history.forEach { existingVideoIds.add(it.track.videoId) }

        return candidates
            .distinctBy { it.videoId }
            .filterNot { it.videoId in existingVideoIds }
    }
}
