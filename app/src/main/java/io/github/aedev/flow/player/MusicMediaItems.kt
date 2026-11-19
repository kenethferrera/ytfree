package io.github.aedev.flow.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.musicvideo.MusicVideoItems
import io.github.aedev.flow.service.auto.AutoArtworkProvider

/**
 * The media item every music queue is built from, by the app and by playback resumption alike. A
 * streamed song is cached under its video id, so its audio is found again whichever URI asked.
 */
internal fun musicMediaItem(
    track: MusicTrack,
    uri: Uri,
    useCacheKey: Boolean = !LocalMediaIds.isLocal(track.videoId),
    fromVideoList: Boolean = false,
    queueEntryId: String? = null,
): MediaItem {
    val builder =
        MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(track.videoId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album.takeIf(String::isNotBlank))
                    .setArtworkUri(AutoArtworkProvider.sessionArtworkUri(track.highResThumbnailUrl))
                    .setDurationMs(track.duration.takeIf { it > 0 }?.let { it * MILLIS_PER_SECOND })
                    .apply {
                        val extras = Bundle()
                        if (fromVideoList) extras.putBoolean(EXTRA_VIDEO_LIST_ITEM, true)
                        if (queueEntryId != null) extras.putString(EXTRA_QUEUE_ENTRY_ID, queueEntryId)
                        if (!extras.isEmpty) setExtras(extras)
                    }.build(),
            )
    if (useCacheKey) builder.setCustomCacheKey(track.videoId)
    return builder.build()
}

private const val MILLIS_PER_SECOND = 1000L
private const val EXTRA_VIDEO_LIST_ITEM = "io.github.aedev.flow.VIDEO_LIST_ITEM"
internal const val EXTRA_QUEUE_ENTRY_ID = "io.github.aedev.flow.QUEUE_ENTRY_ID"

internal val MediaItem.queueEntryId: String?
    get() = mediaMetadata.extras?.getString(EXTRA_QUEUE_ENTRY_ID)

/**
 * A video picked from a video playlist or Watch later in the car (#1657). It plays as audio, but it is
 * not a song: it goes to the watch history instead of music taste and scrobbles.
 */
internal val MediaItem.isVideoListItem: Boolean
    get() = mediaMetadata.extras?.getBoolean(EXTRA_VIDEO_LIST_ITEM) == true

/** A song as the audio it streams, or its MediaStore file when it is on the device. */
internal fun musicAudioUri(videoId: String): Uri = LocalMediaIds.audioUri(videoId) ?: MusicVideoItems.streamUri(videoId, showsVideo = false)

/** The web artwork address of a session item, unwrapped from the car's `content://` form. */
internal val MediaMetadata.webArtworkUrl: String?
    get() = artworkUri?.toString()?.let(AutoArtworkProvider::webUrlOf)
