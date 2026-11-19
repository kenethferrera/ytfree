/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridMusicQueueCoordinatorTest {
    private lateinit var coordinator: HybridMusicQueueCoordinator

    private fun track(id: String): MusicTrack =
        MusicTrack(
            videoId = id,
            title = "Track $id",
            artist = "Artist",
            thumbnailUrl = "thumb",
            duration = 200,
        )

    @Before
    fun setup() {
        coordinator = HybridMusicQueueCoordinator()
    }

    @Test
    fun `loadSource sets active and original entries`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        val source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "pl1", title = "Favorites")

        coordinator.loadSource(source, tracks, startIndex = 1)

        assertEquals(3, coordinator.queue.value.size)
        assertEquals("2", coordinator.currentTrack.value?.videoId)
        assertEquals(1, coordinator.currentQueueIndex.value)
        assertEquals(source, coordinator.playbackSource.value)
        assertEquals(3, coordinator.session.value.originalEntries.size)
    }

    @Test
    fun `playNext inserts immediately after current playing track`() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.ALBUM, title = "Album"), tracks, startIndex = 0)

        val insertIndex = coordinator.playNext(track("X"))

        assertEquals(1, insertIndex)
        assertEquals(listOf("1", "X", "2", "3"), coordinator.queue.value.map { it.videoId })
        assertEquals(0, coordinator.currentQueueIndex.value)
        assertEquals(QueueSource.PLAY_NEXT, coordinator.queueEntries.value[1].source)
        assertTrue(coordinator.queueEntries.value[1].isUserAdded)
    }

    @Test
    fun `addToUserQueue inserts before autoplay recommendations`() {
        val tracks = listOf(track("1"), track("2"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.SEARCH, title = "Search"), tracks, startIndex = 0)

        // Append autoplay tracks
        coordinator.appendAutoplayBatch(listOf(track("AutoA"), track("AutoB")))
        assertEquals(listOf("1", "2", "AutoA", "AutoB"), coordinator.queue.value.map { it.videoId })

        // User manually adds song to queue
        val insertIndex = coordinator.addToUserQueue(track("UserQueue"))

        // Expected order: 1 -> 2 -> UserQueue -> AutoA -> AutoB
        assertEquals(2, insertIndex)
        assertEquals(listOf("1", "2", "UserQueue", "AutoA", "AutoB"), coordinator.queue.value.map { it.videoId })
        assertTrue(coordinator.queueEntries.value[2].isUserAdded)
        assertFalse(coordinator.queueEntries.value[3].isUserAdded)
    }

    @Test
    fun `toggleShuffle randomizes upcoming and restore restores original order`() {
        val tracks = (1..6).map { track("$it") }
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "List"), tracks, startIndex = 1)

        // Shuffle ON
        coordinator.toggleShuffle(true)
        assertTrue(coordinator.shuffleEnabled.value)
        assertEquals("2", coordinator.currentTrack.value?.videoId)
        assertEquals(1, coordinator.currentQueueIndex.value)

        // Shuffle OFF
        coordinator.toggleShuffle(false)
        assertFalse(coordinator.shuffleEnabled.value)
        assertEquals((1..6).map { "$it" }, coordinator.queue.value.map { it.videoId })
        assertEquals(1, coordinator.currentQueueIndex.value)
        assertEquals("2", coordinator.currentTrack.value?.videoId)
    }

    @Test
    fun `onMediaItemTransition updates current track and preserves history`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.SINGLE_TRACK, title = "Single"), tracks, startIndex = 0)

        coordinator.onMediaItemTransition(playerIndex = 1, mediaId = "B")

        assertEquals(1, coordinator.currentQueueIndex.value)
        assertEquals("B", coordinator.currentTrack.value?.videoId)
        assertEquals(1, coordinator.session.value.history.size)
        assertEquals(
            "A",
            coordinator.session.value.history
                .first()
                .track.videoId,
        )
    }

    @Test
    fun `multiple playNext operations insert newest first below current`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        // Play Next X
        coordinator.playNext(track("X"))
        assertEquals(listOf("A", "X", "B", "C"), coordinator.queue.value.map { it.videoId })

        // Play Next Y -> Y should be inserted immediately after A (before X)
        coordinator.playNext(track("Y"))
        assertEquals(listOf("A", "Y", "X", "B", "C"), coordinator.queue.value.map { it.videoId })
    }

    @Test
    fun `clearUserQueue removes all user-added tracks from queue`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        coordinator.playNext(track("User1"))
        coordinator.addToUserQueue(track("User2"))
        coordinator.appendAutoplayBatch(listOf(track("Auto1")))

        assertEquals(listOf("A", "User1", "B", "C", "User2", "Auto1"), coordinator.queue.value.map { it.videoId })

        coordinator.clearUserQueue()

        // User1 and User2 removed, original B, C and autoplay Auto1 remain
        assertEquals(listOf("A", "B", "C", "Auto1"), coordinator.queue.value.map { it.videoId })
    }

    @Test
    fun `intentional duplicate user additions have unique entry ids`() {
        val tracks = listOf(track("A"), track("B"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        coordinator.addToUserQueue(track("A"))

        assertEquals(3, coordinator.queue.value.size)
        assertEquals(listOf("A", "B", "A"), coordinator.queue.value.map { it.videoId })
        val entries = coordinator.queueEntries.value
        assertEquals("A", entries[0].track.videoId)
        assertEquals("A", entries[2].track.videoId)
        assertFalse(entries[0].id == entries[2].id)
    }

    @Test
    fun `move and removeAt operate correctly on active entries`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        coordinator.move(fromIndex = 1, toIndex = 3)
        assertEquals(listOf("A", "C", "D", "B"), coordinator.queue.value.map { it.videoId })

        val removed = coordinator.removeAt(index = 2)
        assertEquals("D", removed?.track?.videoId)
        assertEquals(listOf("A", "C", "B"), coordinator.queue.value.map { it.videoId })
    }

    @Test
    fun `session restoration restores complete playback session state`() {
        val originalEntries =
            listOf(
                QueueEntry(track = track("Orig1"), source = QueueSource.PLAYLIST),
                QueueEntry(track = track("Orig2"), source = QueueSource.PLAYLIST),
            )
        val activeEntries =
            listOf(
                QueueEntry(track = track("Orig1"), source = QueueSource.PLAYLIST),
                QueueEntry(track = track("UserNext"), source = QueueSource.PLAY_NEXT),
                QueueEntry(track = track("Orig2"), source = QueueSource.PLAYLIST),
            )
        val historyEntries =
            listOf(
                QueueEntry(track = track("PastSong"), source = QueueSource.PLAYLIST),
            )
        val session =
            PlaybackSession(
                source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "restored_id", title = "Restored Playlist"),
                activeEntries = activeEntries,
                originalEntries = originalEntries,
                currentIndex = 1,
                history = historyEntries,
                shuffleEnabled = true,
                repeatMode = io.github.aedev.flow.player.RepeatMode.ALL,
                autoplayEnabled = false,
            )

        coordinator.restoreSession(session)

        assertEquals(3, coordinator.queue.value.size)
        assertEquals("UserNext", coordinator.currentTrack.value?.videoId)
        assertEquals(1, coordinator.currentQueueIndex.value)
        assertTrue(coordinator.shuffleEnabled.value)
        assertEquals(io.github.aedev.flow.player.RepeatMode.ALL, coordinator.repeatMode.value)
        assertFalse(coordinator.autoplayEnabled.value)
        assertEquals("Restored Playlist", coordinator.playbackSource.value?.title)
        assertEquals(1, coordinator.session.value.history.size)
    }
}
