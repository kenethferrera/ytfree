package io.github.aedev.flow.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.withTypedArtists
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.player.queue.PlaybackSourceInfo
import io.github.aedev.flow.player.queue.QueueEntry
import io.github.aedev.flow.player.queue.QueueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val QUEUE_STORE_NAME = "music_queue"

private val Context.queueDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = QUEUE_STORE_NAME,
    scope = StoreScope.PROFILE_PRIVATE,
)

class QueuePersistence private constructor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "QueuePersistence"
        private const val SAVE_DEBOUNCE_MS = 5_000L // Debounce saves to every 5 seconds
        private const val AUTO_SAVE_INTERVAL_MS = 30_000L // Auto-save every 30 seconds

        private val QUEUE_KEY = stringPreferencesKey("queue_json")
        private val CURRENT_INDEX_KEY = intPreferencesKey("current_index")
        private val CURRENT_POSITION_KEY = longPreferencesKey("current_position")
        private val CURRENT_TRACK_ID_KEY = stringPreferencesKey("current_track_id")
        private val SHUFFLE_ENABLED_KEY = stringPreferencesKey("shuffle_enabled")
        private val REPEAT_MODE_KEY = intPreferencesKey("repeat_mode")
        private val SAVED_AT_KEY = longPreferencesKey("saved_at")
        private val AUTOMIX_KEY = stringPreferencesKey("automix_json")
        private val ENTRIES_KEY = stringPreferencesKey("queue_entries_json")
        private val ORIGINAL_ENTRIES_KEY = stringPreferencesKey("original_entries_json")
        private val SESSION_SOURCE_KEY = stringPreferencesKey("session_source_json")
        private val HISTORY_KEY = stringPreferencesKey("history_entries_json")
        private val AUTOPLAY_ENABLED_KEY = booleanPreferencesKey("autoplay_enabled")

        @Volatile
        private var instance: QueuePersistence? = null

        fun getInstance(context: Context): QueuePersistence =
            instance ?: synchronized(this) {
                instance ?: QueuePersistence(context.applicationContext).also { instance = it }
            }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val saveMutex = Mutex()

    private var saveJob: Job? = null
    private var autoSaveJob: Job? = null
    private var lastSaveTime = 0L

    /**
     * Data class holding all queue state for persistence
     */
    data class QueueState(
        val queue: List<MusicTrack>,
        val currentIndex: Int,
        val currentPosition: Long,
        val currentTrackId: String?,
        val shuffleEnabled: Boolean,
        val repeatMode: Int, // 0=OFF, 1=ALL, 2=ONE
        val savedAt: Long,
        val automix: List<MusicTrack> = emptyList(),
        val entries: List<QueueEntry> = emptyList(),
        val originalEntries: List<QueueEntry> = emptyList(),
        val sourceInfo: PlaybackSourceInfo? = null,
        val history: List<QueueEntry> = emptyList(),
        val autoplayEnabled: Boolean = true,
    )

    /**
     * Save queue state with debouncing to prevent excessive disk writes
     */
    fun saveQueueDebounced(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
        entries: List<QueueEntry> = emptyList(),
        originalEntries: List<QueueEntry> = emptyList(),
        sourceInfo: PlaybackSourceInfo? = null,
        history: List<QueueEntry> = emptyList(),
        autoplayEnabled: Boolean = true,
    ) {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveQueueImmediate(
                    queue = queue,
                    currentIndex = currentIndex,
                    currentPosition = currentPosition,
                    currentTrackId = currentTrackId,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    automix = automix,
                    entries = entries,
                    originalEntries = originalEntries,
                    sourceInfo = sourceInfo,
                    history = history,
                    autoplayEnabled = autoplayEnabled,
                )
            }
    }

    /** Drops a debounced save that an immediate save is about to supersede; it would write older state. */
    fun cancelPendingSave() {
        saveJob?.cancel()
    }

    /**
     * Immediately save queue state (use for critical moments like app pause)
     */
    suspend fun saveQueueImmediate(
        queue: List<MusicTrack>,
        currentIndex: Int,
        currentPosition: Long,
        currentTrackId: String?,
        shuffleEnabled: Boolean = false,
        repeatMode: Int = 0,
        automix: List<MusicTrack> = emptyList(),
        entries: List<QueueEntry> = emptyList(),
        originalEntries: List<QueueEntry> = emptyList(),
        sourceInfo: PlaybackSourceInfo? = null,
        history: List<QueueEntry> = emptyList(),
        autoplayEnabled: Boolean = true,
    ) {
        if (queue.isEmpty()) return

        saveMutex.withLock {
            try {
                val now = System.currentTimeMillis()
                context.queueDataStore.edit { prefs ->
                    prefs[QUEUE_KEY] = gson.toJson(queue)
                    prefs[CURRENT_INDEX_KEY] = currentIndex
                    prefs[CURRENT_POSITION_KEY] = currentPosition
                    prefs[CURRENT_TRACK_ID_KEY] = currentTrackId ?: ""
                    prefs[SHUFFLE_ENABLED_KEY] = shuffleEnabled.toString()
                    prefs[REPEAT_MODE_KEY] = repeatMode
                    prefs[SAVED_AT_KEY] = now
                    prefs[AUTOMIX_KEY] = gson.toJson(automix)
                    prefs[ENTRIES_KEY] = gson.toJson(entries)
                    prefs[ORIGINAL_ENTRIES_KEY] = gson.toJson(originalEntries)
                    prefs[SESSION_SOURCE_KEY] = gson.toJson(sourceInfo)
                    prefs[HISTORY_KEY] = gson.toJson(history)
                    prefs[AUTOPLAY_ENABLED_KEY] = autoplayEnabled
                }
                lastSaveTime = now
                Log.d(TAG, "Queue saved: ${queue.size} tracks, index=$currentIndex, pos=$currentPosition, automix=${automix.size}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save queue", e)
            }
        }
    }

    /**
     * Restore queue state from persistent storage
     */
    suspend fun restoreQueue(): QueueState? {
        return try {
            val prefs = context.queueDataStore.data.first()

            val queueJson = prefs[QUEUE_KEY] ?: return null
            val type = object : TypeToken<List<MusicTrack>>() {}.type
            val queue: List<MusicTrack> =
                gson.fromJson<List<MusicTrack>>(queueJson, type)?.map { it.withTypedArtists() } ?: return null

            if (queue.isEmpty()) return null

            val automixJson = prefs[AUTOMIX_KEY]
            val automix: List<MusicTrack> =
                if (!automixJson.isNullOrBlank()) {
                    gson.fromJson<List<MusicTrack>>(automixJson, type)?.map { it.withTypedArtists() } ?: emptyList()
                } else {
                    emptyList()
                }

            val entryType = object : TypeToken<List<QueueEntry>>() {}.type
            val entriesJson = prefs[ENTRIES_KEY]
            val decodedEntries: List<QueueEntry> =
                if (!entriesJson.isNullOrBlank()) {
                    gson.fromJson<List<QueueEntry>>(entriesJson, entryType) ?: emptyList()
                } else {
                    emptyList()
                }

            val resolvedEntries =
                if (decodedEntries.isNotEmpty()) {
                    decodedEntries
                } else {
                    queue.map { QueueEntry(track = it, source = QueueSource.MANUAL_QUEUE) }
                }

            val originalEntriesJson = prefs[ORIGINAL_ENTRIES_KEY]
            val resolvedOriginalEntries: List<QueueEntry> =
                if (!originalEntriesJson.isNullOrBlank()) {
                    gson.fromJson<List<QueueEntry>>(originalEntriesJson, entryType) ?: resolvedEntries
                } else {
                    resolvedEntries
                }

            val sourceJson = prefs[SESSION_SOURCE_KEY]
            val resolvedSource: PlaybackSourceInfo? =
                if (!sourceJson.isNullOrBlank()) {
                    gson.fromJson(sourceJson, PlaybackSourceInfo::class.java)
                } else {
                    null
                }

            val historyJson = prefs[HISTORY_KEY]
            val resolvedHistory: List<QueueEntry> =
                if (!historyJson.isNullOrBlank()) {
                    gson.fromJson<List<QueueEntry>>(historyJson, entryType) ?: emptyList()
                } else {
                    emptyList()
                }

            val autoplayEnabled = prefs[AUTOPLAY_ENABLED_KEY] ?: true

            val state =
                QueueState(
                    queue = queue,
                    currentIndex = prefs[CURRENT_INDEX_KEY] ?: 0,
                    currentPosition = prefs[CURRENT_POSITION_KEY] ?: 0L,
                    currentTrackId = prefs[CURRENT_TRACK_ID_KEY]?.takeIf { it.isNotBlank() },
                    shuffleEnabled = prefs[SHUFFLE_ENABLED_KEY]?.toBooleanStrictOrNull() ?: false,
                    repeatMode = prefs[REPEAT_MODE_KEY] ?: 0,
                    savedAt = prefs[SAVED_AT_KEY] ?: 0L,
                    automix = automix,
                    entries = resolvedEntries,
                    originalEntries = resolvedOriginalEntries,
                    sourceInfo = resolvedSource,
                    history = resolvedHistory,
                    autoplayEnabled = autoplayEnabled,
                )

            Log.d(TAG, "Queue restored: ${queue.size} tracks, saved ${(System.currentTimeMillis() - state.savedAt) / 1000}s ago")
            state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore queue", e)
            null
        }
    }

    /**
     * Start automatic periodic saving
     */
    fun startAutoSave(getQueueState: () -> QueueState?) {
        autoSaveJob?.cancel()
        autoSaveJob =
            scope.launch {
                while (true) {
                    delay(AUTO_SAVE_INTERVAL_MS)
                    getQueueState()?.let { state ->
                        if (state.queue.isNotEmpty()) {
                            saveQueueImmediate(
                                queue = state.queue,
                                currentIndex = state.currentIndex,
                                currentPosition = state.currentPosition,
                                currentTrackId = state.currentTrackId,
                                shuffleEnabled = state.shuffleEnabled,
                                repeatMode = state.repeatMode,
                                automix = state.automix,
                                entries = state.entries,
                                originalEntries = state.originalEntries,
                                sourceInfo = state.sourceInfo,
                                history = state.history,
                                autoplayEnabled = state.autoplayEnabled,
                            )
                        }
                    }
                }
            }
    }

    /**
     * Stop automatic saving (call when service/player is stopped)
     */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    /**
     * Whether a queue was ever saved, from the store's file alone. Only non-empty queues are written
     * and nothing clears the store, so the file exists exactly when there is a queue to resume. A media
     * button that cold-starts the music service needs this answer on the main thread, before any read
     * of the store could finish.
     */
    fun hasSavedQueueFile(): Boolean = ProfileFiles.preferences(context, StoreScope.PROFILE_PRIVATE, QUEUE_STORE_NAME).exists()
}
