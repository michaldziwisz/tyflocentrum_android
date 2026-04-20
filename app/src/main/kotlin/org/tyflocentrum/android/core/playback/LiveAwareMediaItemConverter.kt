package org.tyflocentrum.android.core.playback

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
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
        val rebuiltMediaInfo = MediaInfo.Builder(mediaInfo.contentId).apply {
            mediaInfo.contentUrl?.let(::setContentUrl)
            setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            setContentType(mediaInfo.contentType ?: "application/x-mpegURL")
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
}
