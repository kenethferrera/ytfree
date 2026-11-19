/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HybridQueueOperationsTest {
    private fun createTrack(
        id: String,
        title: String = id,
    ): MusicTrack =
        MusicTrack(
            videoId = id,
            title = title,
            artist = "Artist",
            thumbnailUrl = "thumb",
            duration = 180,
        )

    private fun createEntry(
        id: String,
        source: QueueSource = QueueSource.PLAYLIST,
    ): QueueEntry =
        QueueEntry(
            track = createTrack(id),
            source = source,
        )

    @Test
    fun `insertPlayNext inserts immediately after current index`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val c = createEntry("C")
        val entries = listOf(a, b, c)

        val x = createEntry("X", QueueSource.PLAY_NEXT)
        val result = HybridQueueOperations.insertPlayNext(entries, currentIndex = 0, newEntry = x)

        assertEquals(listOf("A", "X", "B", "C"), result.items.map { it.track.videoId })
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `multiple Play Next operations follow newest first order immediately after current`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val c = createEntry("C")
        val entries = listOf(a, b, c)

        val x = createEntry("X", QueueSource.PLAY_NEXT)
        val step1 = HybridQueueOperations.insertPlayNext(entries, currentIndex = 0, newEntry = x)

        val y = createEntry("Y", QueueSource.PLAY_NEXT)
        val step2 = HybridQueueOperations.insertPlayNext(step1.items, currentIndex = 0, newEntry = y)

        assertEquals(listOf("A", "Y", "X", "B", "C"), step2.items.map { it.track.videoId })
        assertEquals(0, step2.currentIndex)
    }

    @Test
    fun `addToUserQueue appends to the end when no autoplay items exist`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val entries = listOf(a, b)

        val x = createEntry("X", QueueSource.MANUAL_QUEUE)
        val result = HybridQueueOperations.addToUserQueue(entries, currentIndex = 0, newEntry = x)

        assertEquals(listOf("A", "B", "X"), result.items.map { it.track.videoId })
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `addToUserQueue inserts before autoplay items when autoplay tracks are present`() {
        val a = createEntry("A", QueueSource.PLAYLIST)
        val b = createEntry("B", QueueSource.PLAYLIST)
        val auto1 = createEntry("E", QueueSource.AUTOPLAY)
        val auto2 = createEntry("F", QueueSource.AUTOPLAY)
        val entries = listOf(a, b, auto1, auto2)

        val manual = createEntry("X", QueueSource.MANUAL_QUEUE)
        val result = HybridQueueOperations.addToUserQueue(entries, currentIndex = 0, newEntry = manual)

        // Expected: A -> B -> X -> E -> F
        assertEquals(listOf("A", "B", "X", "E", "F"), result.items.map { it.track.videoId })
        assertEquals(0, result.currentIndex)
    }

    @Test
    fun `shuffleRemaining keeps current track and history unchanged while randomizing upcoming`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val c = createEntry("C")
        val d = createEntry("D")
        val e = createEntry("E")
        val entries = listOf(a, b, c, d, e)

        val result = HybridQueueOperations.shuffleRemaining(entries, currentIndex = 1, random = Random(42))

        assertEquals(1, result.currentIndex)
        assertEquals("A", result.items[0].track.videoId)
        assertEquals("B", result.items[1].track.videoId)
        assertEquals(
            setOf("C", "D", "E"),
            result.items
                .drop(2)
                .map { it.track.videoId }
                .toSet(),
        )
    }

    @Test
    fun `restoreOriginalOrder restores remaining source items and retains user-added items`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val c = createEntry("C")
        val d = createEntry("D")
        val original = listOf(a, b, c, d)

        // Simulate shuffle where D comes before C, and user added X via Play Next
        val x = createEntry("X", QueueSource.PLAY_NEXT)
        val activeEntries = listOf(a, b, x, d, c) // Currently at index 1 (B)

        val restored =
            HybridQueueOperations.restoreOriginalOrder(
                originalEntries = original,
                activeEntries = activeEntries,
                currentIndex = 1,
            )

        assertEquals(1, restored.currentIndex)
        assertEquals(listOf("A", "B", "X", "C", "D"), restored.items.map { it.track.videoId })
    }

    @Test
    fun `filterAutoplayCandidates excludes tracks in active queue or history`() {
        val a = createEntry("A")
        val b = createEntry("B")
        val historyEntry = createEntry("H")

        val candidates =
            listOf(
                createTrack("A"), // In active queue
                createTrack("H"), // In history
                createTrack("C"), // Fresh
                createTrack("D"), // Fresh
                createTrack("C"), // Duplicate in candidates
            )

        val filtered =
            HybridQueueOperations.filterAutoplayCandidates(
                candidates = candidates,
                activeEntries = listOf(a, b),
                history = listOf(historyEntry),
            )

        assertEquals(listOf("C", "D"), filtered.map { it.videoId })
    }

    @Test
    fun `user added intentional duplicates maintain distinct queue entries`() {
        val a1 = createEntry("A", QueueSource.PLAYLIST)
        val a2 = createEntry("A", QueueSource.MANUAL_QUEUE)

        assertNotEquals(a1.id, a2.id)
        assertEquals(a1.track.videoId, a2.track.videoId)
        assertTrue(a2.isUserAdded)
    }
}
