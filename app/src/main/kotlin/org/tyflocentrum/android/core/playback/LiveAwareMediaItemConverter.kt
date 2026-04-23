package org.tyflocentrum.android.core.playback

import android.os.Bundle
import android.util.Log
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem

class LiveAwareMediaItemConverter(
    private val delegate: MediaItemConverter = DefaultMediaItemConverter()
) : MediaItemConverter {
    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val queueItem = delegate.toMediaQueueItem(mediaItem)
        if (!mediaItem.isLiveItem()) {
            debugLog(
                "cast.queue.default",
                "mediaId=${mediaItem.mediaId} uri=${mediaItem.localConfiguration?.uri} " +
                    "contentId=${queueItem.media?.contentId} contentUrl=${queueItem.media?.contentUrl} " +
                    "contentType=${queueItem.media?.contentType}"
            )
            return queueItem
        }

        val mediaInfo = queueItem.media ?: return queueItem
        val castStreamUrl = mediaItem.mediaMetadata.extras?.getString(EXTRA_CAST_STREAM_URL)
        val castMimeType = mediaItem.mediaMetadata.extras?.getString(EXTRA_CAST_MIME_TYPE)
        val castContentId = castStreamUrl ?: mediaInfo.contentId
        val rebuiltMediaInfo = MediaInfo.Builder(castContentId).apply {
            (castStreamUrl ?: mediaInfo.contentUrl)?.let(::setContentUrl)
            setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            setContentType(castMimeType ?: mediaInfo.contentType ?: MimeTypes.APPLICATION_M3U8)
            setStreamDuration(MediaInfo.UNKNOWN_DURATION)
            mediaInfo.metadata?.let(::setMetadata)
            mediaInfo.customData?.let(::setCustomData)
        }.build()

        debugLog(
            "cast.queue.live",
            "mediaId=${mediaItem.mediaId} sourceUri=${mediaItem.localConfiguration?.uri} " +
                "castStreamUrl=$castStreamUrl contentId=${rebuiltMediaInfo.contentId} " +
                "contentUrl=${rebuiltMediaInfo.contentUrl} contentType=${rebuiltMediaInfo.contentType}"
        )

        return MediaQueueItem.Builder(rebuiltMediaInfo).apply {
            setAutoplay(true)
            setStartTime(queueItem.startTime)
            setPreloadTime(queueItem.preloadTime)
            setPlaybackDuration(queueItem.playbackDuration)
            queueItem.customData?.let(::setCustomData)
        }.build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val mediaItem = delegate.toMediaItem(mediaQueueItem)
        return if (mediaQueueItem.media?.streamType == MediaInfo.STREAM_TYPE_LIVE) {
            mediaItem.buildUpon()
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
                .build()
        } else {
            mediaItem
        }
    }

    private fun MediaItem.isLiveItem(): Boolean {
        return liveConfiguration != MediaItem.LiveConfiguration.UNSET ||
            mediaMetadata.extras?.containsKey(EXTRA_CAST_STREAM_URL) == true
    }

    companion object {
        private const val TAG = "TyfloCast"
        const val EXTRA_CAST_STREAM_URL: String = "cast_stream_url"
        const val EXTRA_CAST_MIME_TYPE: String = "cast_mime_type"

        fun castExtras(url: String, mimeType: String): Bundle {
            return Bundle().apply {
                putString(EXTRA_CAST_STREAM_URL, url)
                putString(EXTRA_CAST_MIME_TYPE, mimeType)
            }
        }

        private fun debugLog(event: String, details: String) {
            Log.d(TAG, "$event: $details")
        }
    }
}
