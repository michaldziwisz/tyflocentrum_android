package org.tyflocentrum.android.core.playback

import android.os.Bundle
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
            mediaInfo.metadata?.let(::setMetadata)
            mediaInfo.customData?.let(::setCustomData)
        }.build()

        return MediaQueueItem.Builder(rebuiltMediaInfo).apply {
            setAutoplay(queueItem.autoplay)
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
        return liveConfiguration != MediaItem.LiveConfiguration.UNSET
    }

    companion object {
        const val EXTRA_CAST_STREAM_URL: String = "cast_stream_url"
        const val EXTRA_CAST_MIME_TYPE: String = "cast_mime_type"

        fun castExtras(url: String, mimeType: String): Bundle {
            return Bundle().apply {
                putString(EXTRA_CAST_STREAM_URL, url)
                putString(EXTRA_CAST_MIME_TYPE, mimeType)
            }
        }
    }
}
