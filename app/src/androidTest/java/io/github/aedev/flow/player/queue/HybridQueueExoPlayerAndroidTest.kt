/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EXTRA_QUEUE_ENTRY_ID
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.player.musicMediaItem
import io.github.aedev.flow.player.queueEntryId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumentation tests validating synchronization between
 * [HybridMusicQueueCoordinator] and a real Media3 [ExoPlayer] instance.
 */
@RunWith(AndroidJUnit4::class)
class HybridQueueExoPlayerAndroidTest {
    private lateinit var context: Context
    private lateinit var player: ExoPlayer
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

    private fun buildItem(entry: QueueEntry): MediaItem =
        musicMediaItem(
            track = entry.track,
            uri = Uri.parse("https://example.com/${entry.track.videoId}.mp3"),
            queueEntryId = entry.id,
        )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        coordinator = HybridMusicQueueCoordinator()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(context).build()
        }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player.release()
        }
    }

    /**
     * Scenario 1: Load a playlist and start playback at a middle track.
     * Verifies coordinator and ExoPlayer have matching item order and active index.
     */
    @Test
    fun test01_playlistPlaybackMiddleTrack() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"), track("E"))
        val source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "pl_1", title = "Playlist")

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(source, tracks, startIndex = 2)
            val items = coordinator.queueEntries.value.map { buildItem(it) }
            player.setMediaItems(items, 2, 0L)
            player.prepare()

            // Compare coordinator and ExoPlayer
            assertEquals(5, player.mediaItemCount)
            assertEquals(2, player.currentMediaItemIndex)
            assertEquals("C", player.currentMediaItem?.mediaId)
            assertEquals(coordinator.queueEntries.value[2].id, player.currentMediaItem?.queueEntryId)
            assertEquals(2, coordinator.session.value.history.size)
        }
    }

    /**
     * Scenario 2: Insert multiple Play Next entries during playback.
     */
    @Test
    fun test02_multiplePlayNextDuringPlayback() {
        val tracks = listOf(track("A"), track("B"), track("C"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.ALBUM, title = "Album"), tracks, startIndex = 0)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            // Play Next X
            val idxX = coordinator.playNext(track("X"))
            val entryX = coordinator.queueEntries.value[idxX]
            player.addMediaItem(idxX, buildItem(entryX))

            // Play Next Y
            val idxY = coordinator.playNext(track("Y"))
            val entryY = coordinator.queueEntries.value[idxY]
            player.addMediaItem(idxY, buildItem(entryY))

            // Timeline: A -> Y -> X -> B -> C
            val expectedIds = listOf("A", "Y", "X", "B", "C")
            val playerIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            assertEquals(expectedIds, coordinator.queue.value.map { it.videoId })
            assertEquals(expectedIds, playerIds)
        }
    }

    /**
     * Scenario 3: Append Add to Queue entries.
     */
    @Test
    fun test03_addToQueue() {
        val tracks = listOf(track("A"), track("B"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            val idxX = coordinator.addToUserQueue(track("X"))
            player.addMediaItem(idxX, buildItem(coordinator.queueEntries.value[idxX]))

            val idxY = coordinator.addToUserQueue(track("Y"))
            player.addMediaItem(idxY, buildItem(coordinator.queueEntries.value[idxY]))

            val expectedIds = listOf("A", "B", "X", "Y")
            val playerIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            assertEquals(expectedIds, coordinator.queue.value.map { it.videoId })
            assertEquals(expectedIds, playerIds)
        }
    }

    /**
     * Scenario 4: Remove and reorder upcoming entries.
     */
    @Test
    fun test04_removeAndReorderUpcoming() {
        val tracks = listOf(track("A"), track("B"), track("C"), track("D"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            // Remove B (index 1)
            coordinator.removeAt(1)
            player.removeMediaItem(1)

            assertEquals(listOf("A", "C", "D"), coordinator.queue.value.map { it.videoId })
            val playerIdsAfterRemoval = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            assertEquals(listOf("A", "C", "D"), playerIdsAfterRemoval)

            // Move D (index 2) before C (index 1) -> A, D, C
            coordinator.move(fromIndex = 2, toIndex = 1)
            player.moveMediaItem(2, 1)

            val expected = listOf("A", "D", "C")
            assertEquals(expected, coordinator.queue.value.map { it.videoId })
            val playerIdsAfterMove = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
            assertEquals(expected, playerIdsAfterMove)
        }
    }

    /**
     * Scenario 5: Shuffle and restore original ordering.
     */
    @Test
    fun test05_shuffleAndRestoreOrder() {
        val tracks = (1..6).map { track("$it") }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 1)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 1, 0L)

            // Toggle shuffle ON
            coordinator.toggleShuffle(true)
            assertTrue(coordinator.shuffleEnabled.value)
            assertEquals(1, coordinator.currentQueueIndex.value)
            assertEquals("2", coordinator.currentTrack.value?.videoId)

            // Toggle shuffle OFF
            coordinator.toggleShuffle(false)
            assertFalse(coordinator.shuffleEnabled.value)
            assertEquals(tracks.map { it.videoId }, coordinator.queue.value.map { it.videoId })
        }
    }

    /**
     * Scenario 6: Execute Repeat One and Repeat All.
     */
    @Test
    fun test06_repeatModes() {
        val tracks = listOf(track("A"), track("B"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            // Repeat One
            player.repeatMode = Player.REPEAT_MODE_ONE
            coordinator.setRepeatMode(RepeatMode.ONE)
            assertEquals(RepeatMode.ONE, coordinator.repeatMode.value)
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)

            // Repeat All
            player.repeatMode = Player.REPEAT_MODE_ALL
            coordinator.setRepeatMode(RepeatMode.ALL)
            assertEquals(RepeatMode.ALL, coordinator.repeatMode.value)
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        }
    }

    /**
     * Scenario 7: Insert duplicate tracks with distinct queue entry IDs.
     */
    @Test
    fun test07_duplicateTracksWithDistinctEntryIds() {
        val tracks = listOf(track("A"), track("B"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)
            val insertIdx = coordinator.addToUserQueue(track("A"))

            val entryFirst = coordinator.queueEntries.value[0]
            val entrySecond = coordinator.queueEntries.value[insertIdx]

            assertNotEquals(entryFirst.id, entrySecond.id)
            assertEquals("A", entryFirst.track.videoId)
            assertEquals("A", entrySecond.track.videoId)

            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            assertEquals(3, player.mediaItemCount)
            assertEquals(entryFirst.id, player.getMediaItemAt(0).queueEntryId)
            assertEquals(entrySecond.id, player.getMediaItemAt(2).queueEntryId)
        }
    }

    /**
     * Scenario 8: Transition from scheduled entries to autoplay entries.
     */
    @Test
    fun test08_transitionToAutoplayEntries() {
        val tracks = listOf(track("A"), track("B"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 1)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 1, 0L)

            val autoEntries = coordinator.appendAutoplayBatch(listOf(track("Auto1"), track("Auto2")))
            autoEntries.forEach { player.addMediaItem(buildItem(it)) }

            assertEquals(4, player.mediaItemCount)
            assertEquals(listOf("A", "B", "Auto1", "Auto2"), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })

            // Transition to Auto1
            coordinator.onMediaItemTransition(playerIndex = 2, mediaId = "Auto1", queueEntryId = autoEntries[0].id)
            player.seekTo(2, 0L)

            assertEquals(2, coordinator.currentQueueIndex.value)
            assertEquals("Auto1", coordinator.currentTrack.value?.videoId)
            assertEquals(QueueSource.AUTOPLAY, coordinator.queueEntries.value[2].source)
        }
    }

    /**
     * Scenario 9: Release and recreate the player.
     */
    @Test
    fun test09_releaseAndRecreatePlayer() {
        val tracks = listOf(track("A"), track("B"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.loadSource(PlaybackSourceInfo(type = QueueSource.PLAYLIST, title = "Playlist"), tracks, startIndex = 0)
            player.setMediaItems(coordinator.queueEntries.value.map { buildItem(it) }, 0, 0L)

            // Release player
            player.release()

            // Recreate player and re-hydrate from coordinator
            player = ExoPlayer.Builder(context).build()
            player.setMediaItems(
                coordinator.queueEntries.value.map { buildItem(it) },
                coordinator.currentQueueIndex.value,
                0L,
            )

            assertEquals(2, player.mediaItemCount)
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals("A", player.currentMediaItem?.mediaId)
        }
    }

    /**
     * Scenario 10: Restore a persisted session.
     */
    @Test
    fun test10_restorePersistedSession() {
        val originalEntries = listOf(QueueEntry(track = track("A"), source = QueueSource.PLAYLIST))
        val activeEntries =
            listOf(
                QueueEntry(track = track("A"), source = QueueSource.PLAYLIST),
                QueueEntry(track = track("UserNext"), source = QueueSource.PLAY_NEXT),
            )
        val session =
            PlaybackSession(
                source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "restored", title = "Saved Session"),
                activeEntries = activeEntries,
                originalEntries = originalEntries,
                currentIndex = 1,
                shuffleEnabled = true,
                repeatMode = RepeatMode.ONE,
                autoplayEnabled = false,
            )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            coordinator.restoreSession(session)
            player.setMediaItems(
                coordinator.queueEntries.value.map { buildItem(it) },
                coordinator.currentQueueIndex.value,
                1500L,
            )

            assertEquals(2, player.mediaItemCount)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("UserNext", player.currentMediaItem?.mediaId)
            assertEquals(session.currentEntry?.id, player.currentMediaItem?.queueEntryId)
            assertTrue(coordinator.shuffleEnabled.value)
            assertEquals(RepeatMode.ONE, coordinator.repeatMode.value)
            assertFalse(coordinator.autoplayEnabled.value)
        }
    }
}
