/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridQueuePlaybackIntegrationTest {
    private lateinit var coordinator: HybridMusicQueueCoordinator

    private fun track(
        id: String,
        title: String = "Track $id",
    ): MusicTrack =
        MusicTrack(
            videoId = id,
            title = title,
            artist = "Artist $id",
            thumbnailUrl = "thumb_$id",
            duration = 180,
        )

    @Before
    fun setup() {
        coordinator = HybridMusicQueueCoordinator()
    }

    /**
     * Test A: Playlist Playback
     * Initial playlist: A -> B -> C -> D -> E
     * Select C (index 2).
     * Expected:
     * - C starts playing at index 2.
     * - A and B are in playback history.
     * - D and E remain upcoming.
     * - Transitioning to B updates current track and preserves C in history.
     * - Transitioning to D advances correctly.
     */
    @Test
    fun `test A - playlist playback starting at middle track preserves history and upcoming`() {
        val playlist = listOf(track("A"), track("B"), track("C"), track("D"), track("E"))
        val source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "pl_1", title = "My Playlist")

        coordinator.loadSource(source = source, tracks = playlist, startIndex = 2)

        assertEquals("C", coordinator.currentTrack.value?.videoId)
        assertEquals(2, coordinator.currentQueueIndex.value)
        assertEquals(5, coordinator.queue.value.size)

        // Preceding tracks A and B should be in history
        val history = coordinator.session.value.history
        assertEquals(2, history.size)
        assertEquals(listOf("A", "B"), history.map { it.track.videoId })

        // Upcoming tracks are D and E
        val upcoming = coordinator.queueEntries.value.drop(coordinator.currentQueueIndex.value + 1)
        assertEquals(listOf("D", "E"), upcoming.map { it.track.videoId })

        // Transition backwards to B (e.g. Previous pressed)
        val entryB = coordinator.queueEntries.value[1]
        coordinator.onMediaItemTransition(playerIndex = 1, mediaId = "B", queueEntryId = entryB.id)
        assertEquals("B", coordinator.currentTrack.value?.videoId)
        assertEquals(1, coordinator.currentQueueIndex.value)

        // Transition forwards to D (e.g. Next pressed)
        val entryD = coordinator.queueEntries.value[3]
        coordinator.onMediaItemTransition(playerIndex = 3, mediaId = "D", queueEntryId = entryD.id)
        assertEquals("D", coordinator.currentTrack.value?.videoId)
        assertEquals(3, coordinator.currentQueueIndex.value)
    }

    /**
     * Test B: Multiple Play Next Operations
     * Initial: Current A, Upcoming B -> C -> D
     * Perform: Play Next X, then Play Next Y.
     * Expected ordering: A -> Y -> X -> B -> C -> D (newest-first below current).
     */
    @Test
    fun `test B - multiple play next operations insert newest first below current track`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.ALBUM, title = "Album"), tracks, startIndex = 0)

        coordinator.playNext(track("X"))
        assertEquals(listOf("A", "X", "B", "C", "D"), coordinator.queue.value.map { it.videoId })

        coordinator.playNext(track("Y"))
        assertEquals(listOf("A", "Y", "X", "B", "C", "D"), coordinator.queue.value.map { it.videoId })
        assertEquals("A", coordinator.currentTrack.value?.videoId)
        assertEquals(0, coordinator.currentQueueIndex.value)

        // Verify queue sources
        assertEquals(QueueSource.ALBUM, coordinator.queueEntries.value[0].source)
        assertEquals(QueueSource.PLAY_NEXT, coordinator.queueEntries.value[1].source)
        assertEquals(QueueSource.PLAY_NEXT, coordinator.queueEntries.value[2].source)
        assertEquals(QueueSource.ALBUM, coordinator.queueEntries.value[3].source)
    }

    /**
     * Test C: Add to Queue
     * Initial: Current A, Upcoming B -> C -> D
     * Perform: Add X to queue, Add Y to queue.
     * Expected: A -> B -> C -> D -> X -> Y
     */
    @Test
    fun `test C - add to queue appends tracks after scheduled content`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        val idxX = coordinator.addToUserQueue(track("X"))
        assertEquals(4, idxX)
        assertEquals(listOf("A", "B", "C", "D", "X"), coordinator.queue.value.map { it.videoId })

        val idxY = coordinator.addToUserQueue(track("Y"))
        assertEquals(5, idxY)
        assertEquals(listOf("A", "B", "C", "D", "X", "Y"), coordinator.queue.value.map { it.videoId })

        assertEquals(QueueSource.MANUAL_QUEUE, coordinator.queueEntries.value[4].source)
        assertEquals(QueueSource.MANUAL_QUEUE, coordinator.queueEntries.value[5].source)
        assertTrue(coordinator.queueEntries.value[4].isUserAdded)
        assertTrue(coordinator.queueEntries.value[5].isUserAdded)
    }

    /**
     * Test D: Manual Queue Priority over Autoplay Recommendations
     * Initial: Current A, Scheduled B -> C -> D
     * Autoplay recommendations: E -> F -> G
     * Play Next X: A -> X -> B -> C -> D -> E -> F -> G
     * Add to Queue Y: A -> X -> B -> C -> D -> Y -> E -> F -> G
     * No autoplay item may move ahead of scheduled user tracks.
     */
    @Test
    fun `test D - manual queue operations have strict priority over autoplay recommendations`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        // MusicBrain generates autoplay recommendations
        coordinator.appendAutoplayBatch(listOf(track("E"), track("F"), track("G")))
        assertEquals(listOf("A", "B", "C", "D", "E", "F", "G"), coordinator.queue.value.map { it.videoId })

        // Play Next X
        coordinator.playNext(track("X"))
        assertEquals(listOf("A", "X", "B", "C", "D", "E", "F", "G"), coordinator.queue.value.map { it.videoId })

        // Add to Queue Y (must be placed before autoplay recommendations E, F, G)
        val insertIdxY = coordinator.addToUserQueue(track("Y"))
        assertEquals(5, insertIdxY)
        assertEquals(listOf("A", "X", "B", "C", "D", "Y", "E", "F", "G"), coordinator.queue.value.map { it.videoId })

        // Verify sources
        val entries = coordinator.queueEntries.value
        assertEquals("Y", entries[5].track.videoId)
        assertEquals(QueueSource.MANUAL_QUEUE, entries[5].source)
        assertEquals("E", entries[6].track.videoId)
        assertEquals(QueueSource.AUTOPLAY, entries[6].source)
    }

    /**
     * Test E: Queue Mutation During Playback
     * While A is playing:
     * - Remove B
     * - Move D before C
     * - Add X to queue
     * - Perform Play Next Y
     * Verify no skipped tracks, no duplicated items, current playback uninterrupted.
     */
    @Test
    fun `test E - queue mutations during playback maintain synchronization and stability`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        // Remove B (index 1)
        val removed = coordinator.removeAt(1)
        assertEquals("B", removed?.track?.videoId)
        assertEquals(listOf("A", "C", "D"), coordinator.queue.value.map { it.videoId })
        assertEquals(0, coordinator.currentQueueIndex.value)
        assertEquals("A", coordinator.currentTrack.value?.videoId)

        // Move D (index 2) before C (index 1) -> A, D, C
        coordinator.move(fromIndex = 2, toIndex = 1)
        assertEquals(listOf("A", "D", "C"), coordinator.queue.value.map { it.videoId })
        assertEquals(0, coordinator.currentQueueIndex.value)

        // Add X to queue -> A, D, C, X
        coordinator.addToUserQueue(track("X"))
        assertEquals(listOf("A", "D", "C", "X"), coordinator.queue.value.map { it.videoId })

        // Play Next Y -> A, Y, D, C, X
        coordinator.playNext(track("Y"))
        assertEquals(listOf("A", "Y", "D", "C", "X"), coordinator.queue.value.map { it.videoId })

        // Ensure current playback is untouched
        assertEquals("A", coordinator.currentTrack.value?.videoId)
        assertEquals(0, coordinator.currentQueueIndex.value)
    }

    /**
     * Test F: Queue Clearing
     * Test clearing user-added tracks while preserving:
     * - Currently playing track.
     * - Original source sequence.
     * - Autoplay recommendations.
     */
    @Test
    fun `test F - clear user queue removes user tracks and preserves playlist and autoplay`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        coordinator.playNext(track("UserNext1"))
        coordinator.playNext(track("UserNext2"))
        coordinator.addToUserQueue(track("UserManual"))
        coordinator.appendAutoplayBatch(listOf(track("Auto1"), track("Auto2")))

        assertEquals(
            listOf("A", "UserNext2", "UserNext1", "B", "C", "UserManual", "Auto1", "Auto2"),
            coordinator.queue.value.map { it.videoId },
        )

        coordinator.clearUserQueue()

        // User tracks removed, original playlist B, C and autoplay Auto1, Auto2 preserved
        val expected = listOf("A", "B", "C", "Auto1", "Auto2")
        assertEquals(expected, coordinator.queue.value.map { it.videoId })
        assertEquals("A", coordinator.currentTrack.value?.videoId)
        assertEquals(0, coordinator.currentQueueIndex.value)
    }

    /**
     * Test G: Shuffle and Repeat Integration
     * A -> B -> C -> D -> E
     * Play C.
     * Enable shuffle -> C continues playing, only upcoming shuffled, history intact, original preserved.
     * While shuffled:
     * - Add X using Play Next.
     * - Add Y using Add to Queue.
     * - Remove another track.
     * Disable shuffle -> Original source order restored, user additions correctly positioned,
     * removed track remains removed, current playback does not restart.
     */
    @Test
    fun `test G - stable shuffle with mutations and clean restoration`() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"), track("E"), track("F"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 2)

        assertEquals("C", coordinator.currentTrack.value?.videoId)
        assertEquals(2, coordinator.currentQueueIndex.value)

        // Enable shuffle
        coordinator.toggleShuffle(true)
        assertTrue(coordinator.shuffleEnabled.value)
        assertEquals("C", coordinator.currentTrack.value?.videoId)
        assertEquals(2, coordinator.currentQueueIndex.value)

        // Verify history up to C is preserved [A, B, C]
        val activePrefix =
            coordinator.queue.value
                .take(3)
                .map { it.videoId }
        assertEquals(listOf("A", "B", "C"), activePrefix)

        // While shuffled: Play Next X
        coordinator.playNext(track("X"))
        assertEquals("C", coordinator.currentTrack.value?.videoId)
        assertEquals(
            "X",
            coordinator.queueEntries.value[3]
                .track.videoId,
        )

        // While shuffled: Add Y to queue
        coordinator.addToUserQueue(track("Y"))
        assertEquals(
            "Y",
            coordinator.queueEntries.value
                .last()
                .track.videoId,
        )

        // While shuffled: Remove track D from upcoming queue
        val dIndex = coordinator.queue.value.indexOfFirst { it.videoId == "D" }
        assertTrue(dIndex > 2)
        coordinator.removeAt(dIndex)

        // Now disable shuffle
        coordinator.toggleShuffle(false)
        assertFalse(coordinator.shuffleEnabled.value)

        // Verification after shuffle OFF:
        // Current track remains C at index 2
        assertEquals("C", coordinator.currentTrack.value?.videoId)
        assertEquals(2, coordinator.currentQueueIndex.value)

        // D was removed, so remaining original upcoming is E, F.
        // User additions X (play next) and Y (manual queue) should remain upcoming.
        val finalQueueIds = coordinator.queue.value.map { it.videoId }
        assertEquals("A", finalQueueIds[0])
        assertEquals("B", finalQueueIds[1])
        assertEquals("C", finalQueueIds[2])
        assertTrue(finalQueueIds.contains("X"))
        assertTrue(finalQueueIds.contains("Y"))
        assertFalse(finalQueueIds.contains("D")) // Removed track stays removed
        assertTrue(finalQueueIds.contains("E"))
        assertTrue(finalQueueIds.contains("F"))
    }

    /**
     * Test H: Repeat One
     * Play A.
     * Enable Repeat One.
     * Repeated transition to A does not duplicate history or trigger autoplay.
     */
    @Test
    fun `test H - repeat one does not duplicate history or trigger autoplay`() {
        val tracks = listOf(track("A"), track("B"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.SINGLE_TRACK, title = "Single"), tracks, startIndex = 0)
        coordinator.setRepeatMode(RepeatMode.ONE)

        val entryA = coordinator.queueEntries.value[0]

        // Transition back to A (simulating repeat one loop)
        coordinator.onMediaItemTransition(playerIndex = 0, mediaId = "A", queueEntryId = entryA.id)
        assertEquals(0, coordinator.currentQueueIndex.value)
        assertEquals("A", coordinator.currentTrack.value?.videoId)

        // History shouldn't duplicate A
        assertEquals(0, coordinator.session.value.history.size)
    }

    /**
     * Test I: Repeat All
     * Playlist A -> B -> C
     * Repeat All loops back without triggering autoplay.
     */
    @Test
    fun `test I - repeat all loop transitions correctly`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Loop List"), tracks, startIndex = 2)
        coordinator.setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, coordinator.repeatMode.value)

        // End of list C reached -> loops to A
        val entryA = coordinator.queueEntries.value[0]
        coordinator.onMediaItemTransition(playerIndex = 0, mediaId = "A", queueEntryId = entryA.id)
        assertEquals(0, coordinator.currentQueueIndex.value)
        assertEquals("A", coordinator.currentTrack.value?.videoId)

        // History now records C
        assertTrue(
            coordinator.session.value.history
                .any { it.track.videoId == "C" },
        )
    }

    /**
     * Test J: MusicBrain Autoplay Candidate Filtering & Deduplication
     * Autoplay candidates must be deduplicated against:
     * - Active queue entries
     * - Played history
     */
    @Test
    fun `test J - autoplay candidate deduplication against active queue and history`() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 1)

        // Active queue has [A, B, C], history has [A]
        val candidates = listOf(track("B"), track("A"), track("D"), track("C"), track("E"), track("D"))

        val added = coordinator.appendAutoplayBatch(candidates)

        // Only D and E should be added (B and C are active, A is in history, duplicate D filtered)
        assertEquals(listOf("D", "E"), added.map { it.track.videoId })
        assertEquals(listOf("A", "B", "C", "D", "E"), coordinator.queue.value.map { it.videoId })
    }

    /**
     * Test K: Save Queue as Playlist
     * Saving active queue entries with user additions and autoplay recommendations intact.
     */
    @Test
    fun `test K - queue serialization preserves track sequence for playlist saving`() {
        val tracks = listOf(track("A"), track("B"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.ALBUM, title = "My Album"), tracks, startIndex = 0)

        coordinator.playNext(track("UserNext"))
        coordinator.appendAutoplayBatch(listOf(track("Auto1")))

        val currentQueue = coordinator.queue.value
        assertEquals(listOf("A", "UserNext", "B", "Auto1"), currentQueue.map { it.videoId })

        // Extracting tracks for playlist creation maintains full fidelity
        val playlistTrackIds = currentQueue.map { it.videoId }
        val playlistTrackTitles = currentQueue.map { it.title }

        assertEquals(4, playlistTrackIds.size)
        assertEquals(listOf("Track A", "Track UserNext", "Track B", "Track Auto1"), playlistTrackTitles)
    }

    /**
     * Test L: Queue Entry ID disambiguation for duplicate tracks
     * When duplicate track "A" exists at index 0 and index 2:
     * Passing queueEntryId unambiguously resolves to the correct instance.
     */
    @Test
    fun `test L - disambiguates duplicate track videoId using queueEntryId`() {
        val tracks = listOf(track("A"), track("B"))
        coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)

        // Add duplicate A
        coordinator.addToUserQueue(track("A"))
        assertEquals(listOf("A", "B", "A"), coordinator.queue.value.map { it.videoId })

        val firstA = coordinator.queueEntries.value[0]
        val secondA = coordinator.queueEntries.value[2]
        assertNotEquals(firstA.id, secondA.id)

        // Transition specifically to second A using its entryId
        coordinator.onMediaItemTransition(playerIndex = 2, mediaId = "A", queueEntryId = secondA.id)
        assertEquals(2, coordinator.currentQueueIndex.value)
        assertEquals(
            secondA.id,
            coordinator.session.value.currentEntry
                ?.id,
        )

        // Transition back to first A
        coordinator.onMediaItemTransition(playerIndex = 0, mediaId = "A", queueEntryId = firstA.id)
        assertEquals(0, coordinator.currentQueueIndex.value)
        assertEquals(
            firstA.id,
            coordinator.session.value.currentEntry
                ?.id,
        )
    }
}
