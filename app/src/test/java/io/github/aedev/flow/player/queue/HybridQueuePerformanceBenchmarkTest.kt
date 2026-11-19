/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player.queue

import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureNanoTime

class HybridQueuePerformanceBenchmarkTest {
    private lateinit var coordinator: HybridMusicQueueCoordinator

    private fun track(id: String): MusicTrack =
        MusicTrack(
            videoId = id,
            title = "Track $id",
            artist = "Artist $id",
            thumbnailUrl = "thumb_$id",
            duration = 180,
        )

    @Before
    fun setup() {
        coordinator = HybridMusicQueueCoordinator()
    }

    private fun runBenchmark(size: Int) {
        val tracks = (1..size).map { track("t_$it") }
        val source = PlaybackSourceInfo(type = QueueSource.PLAYLIST, id = "bench_$size", title = "Benchmark $size")

        // 1. Benchmark loadSource
        val loadTimeNs =
            measureNanoTime {
                coordinator.loadSource(source, tracks, startIndex = size / 2)
            }
        assertEquals(size, coordinator.queue.value.size)

        // 2. Benchmark playNext
        val playNextTimeNs =
            measureNanoTime {
                coordinator.playNext(track("play_next_bench"))
            }

        // 3. Benchmark addToUserQueue
        val addToQueueTimeNs =
            measureNanoTime {
                coordinator.addToUserQueue(track("add_queue_bench"))
            }

        // 4. Benchmark toggleShuffle (shuffle)
        val shuffleTimeNs =
            measureNanoTime {
                coordinator.toggleShuffle(true)
            }
        assertTrue(coordinator.shuffleEnabled.value)

        // 5. Benchmark toggleShuffle (restore original order)
        val restoreTimeNs =
            measureNanoTime {
                coordinator.toggleShuffle(false)
            }

        // 6. Benchmark move
        val moveTimeNs =
            measureNanoTime {
                coordinator.move(fromIndex = 1, toIndex = size / 2)
            }

        // 7. Benchmark removeAt
        val removeTimeNs =
            measureNanoTime {
                coordinator.removeAt(index = 2)
            }

        // 8. Benchmark clearUserQueue
        val clearTimeNs =
            measureNanoTime {
                coordinator.clearUserQueue()
            }

        // Convert to milliseconds
        val loadMs = loadTimeNs / 1_000_000.0
        val playNextMs = playNextTimeNs / 1_000_000.0
        val addMs = addToQueueTimeNs / 1_000_000.0
        val shuffleMs = shuffleTimeNs / 1_000_000.0
        val restoreMs = restoreTimeNs / 1_000_000.0
        val moveMs = moveTimeNs / 1_000_000.0
        val removeMs = removeTimeNs / 1_000_000.0
        val clearMs = clearTimeNs / 1_000_000.0

        println(
            "Benchmark size=$size: load=${loadMs}ms, playNext=${playNextMs}ms, add=${addMs}ms, " +
                "shuffle=${shuffleMs}ms, restore=${restoreMs}ms, move=${moveMs}ms, remove=${removeMs}ms, clear=${clearMs}ms",
        )

        // Performance requirement: Each queue mutation must complete well within a 16ms frame budget (sub-10ms threshold)
        assertTrue("load took too long: ${loadMs}ms", loadMs < 50.0)
        assertTrue("playNext took too long: ${playNextMs}ms", playNextMs < 10.0)
        assertTrue("addToQueue took too long: ${addMs}ms", addMs < 10.0)
        assertTrue("shuffle took too long: ${shuffleMs}ms", shuffleMs < 15.0)
        assertTrue("restore took too long: ${restoreMs}ms", restoreMs < 15.0)
        assertTrue("move took too long: ${moveMs}ms", moveMs < 10.0)
        assertTrue("remove took too long: ${removeMs}ms", removeMs < 10.0)
        assertTrue("clear took too long: ${clearMs}ms", clearMs < 10.0)
    }

    @Test
    fun `benchmark queue operations with 20 tracks`() {
        runBenchmark(20)
    }

    @Test
    fun `benchmark queue operations with 100 tracks`() {
        runBenchmark(100)
    }

    @Test
    fun `benchmark queue operations with 500 tracks`() {
        runBenchmark(500)
    }
}
