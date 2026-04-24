const RADIO_SOURCE_URL = 'https://radio.tyflopodcast.net/';
const RADIO_HLS_URL = 'https://radio.tyflopodcast.net/hls/stream.m3u8';
const RADIO_CONTENT_TYPE = 'audio/mpeg';

const context = cast.framework.CastReceiverContext.getInstance();
const playerManager = context.getPlayerManager();

context.setLoggerLevel(cast.framework.LoggerLevel.DEBUG);

playerManager.setMessageInterceptor(
  cast.framework.messages.MessageType.LOAD,
  (loadRequestData) => {
    const media = loadRequestData && loadRequestData.media;
    if (!media) {
      return loadRequestData;
    }

    const requestedUrl = normalizeUrl(media.contentUrl || media.contentId || '');
    const isTyfloRadio =
      requestedUrl === normalizeUrl(RADIO_SOURCE_URL) ||
      requestedUrl === normalizeUrl(RADIO_HLS_URL);

    if (!isTyfloRadio) {
      return loadRequestData;
    }

    media.contentId = RADIO_SOURCE_URL;
    media.contentUrl = RADIO_SOURCE_URL;
    media.contentType = RADIO_CONTENT_TYPE;
    media.streamType = chrome.cast.media.StreamType.LIVE;
    loadRequestData.autoplay = true;

    if (!media.metadata) {
      const metadata = new chrome.cast.media.MusicTrackMediaMetadata();
      metadata.title = 'Tyfloradio';
      metadata.albumTitle = 'Tyflopodcast';
      metadata.artist = 'Radio live';
      media.metadata = metadata;
    }

    console.log('Tyfloradio load', {
      contentId: media.contentId,
      contentType: media.contentType,
      streamType: media.streamType,
      autoplay: loadRequestData.autoplay,
    });

    return loadRequestData;
  }
);

playerManager.addEventListener(
  cast.framework.events.EventType.ERROR,
  (event) => {
    console.error('Tyfloradio receiver error', event);
  }
);

context.start({
  disableIdleTimeout: true,
  supportedCommands:
    cast.framework.messages.Command.ALL_BASIC_MEDIA |
    cast.framework.messages.Command.STREAM_VOLUME |
    cast.framework.messages.Command.STREAM_MUTE,
});

function normalizeUrl(url) {
  return String(url || '').trim().replace(/\/+$/, '');
}
