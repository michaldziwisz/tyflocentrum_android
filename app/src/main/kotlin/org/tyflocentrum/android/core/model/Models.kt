package net.tyflopodcast.tyflocentrum.core.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.Transient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

private val wpDateParser: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
private val displayDateFormatter: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(Locale("pl", "PL"))

@Serializable
data class WpRenderedText(
    val rendered: String
) {
    @Transient
    private var plainTextCache: String? = null

    val plainText: String
        get() = plainTextCache ?: rendered.htmlToPlainText().also { plainTextCache = it }
}

@Serializable
data class WpPostSummary(
    val id: Int,
    val date: String,
    val title: WpRenderedText,
    val excerpt: WpRenderedText? = null,
    val link: String
) {
    @Transient
    private var formattedDateCache: String? = null

    val formattedDate: String
        get() = formattedDateCache ?: date.formatWpDate().also { formattedDateCache = it }
}

@Serializable
data class WpPostDetail(
    val id: Int,
    val date: String,
    val title: WpRenderedText,
    val excerpt: WpRenderedText,
    val content: WpRenderedText,
    val guid: WpRenderedText
) {
    @Transient
    private var formattedDateCache: String? = null

    val formattedDate: String
        get() = formattedDateCache ?: date.formatWpDate().also { formattedDateCache = it }
}

@Serializable
data class Category(
    val name: String,
    val id: Int,
    val count: Int
)

@Serializable
data class Comment(
    val id: Int,
    val post: Int,
    val parent: Int,
    val date: String = "",
    @SerialName("author_name") val authorName: String,
    val content: CommentContent
) {
    @Transient
    private var formattedDateCache: String? = null

    val formattedDate: String?
        get() = date.takeIf { it.isNotBlank() }?.let {
            formattedDateCache ?: it.formatWpDate().also { formattedDateCache = it }
        }

    @Serializable
    data class CommentContent(
        val rendered: String
    )
}

data class ThreadedComment(
    val comment: Comment,
    val depth: Int,
    val parentAuthorName: String? = null
)

@Serializable
data class Availability(
    val available: Boolean,
    val title: String? = null
)

@Serializable
data class RadioSchedule(
    val available: Boolean,
    val text: String? = null,
    val error: String? = null
)

data class PagedResult<T>(
    val items: List<T>,
    val total: Int? = null,
    val totalPages: Int? = null
)

enum class ContentKind(val label: String) {
    PODCAST("Podcast"),
    ARTICLE("Artykuł");

    val lowercaseLabel: String
        get() = label.lowercase(Locale("pl", "PL"))
}

data class NewsItem(
    val kind: ContentKind,
    val post: WpPostSummary
) {
    val uniqueId: String = "${kind.name}.${post.id}"
}

data class SearchItem(
    val kind: ContentKind,
    val post: WpPostSummary
)

enum class ContentKindLabelPosition(val title: String) {
    BEFORE("Przed"),
    AFTER("Po")
}

enum class PlaybackRateRememberMode(val title: String) {
    GLOBAL("Globalnie"),
    PER_EPISODE("Dla każdego odcinka")
}

@Serializable
data class PushPreferences(
    val podcast: Boolean = true,
    val article: Boolean = true,
    val live: Boolean = true,
    val schedule: Boolean = true
) {
    val allEnabled: Boolean
        get() = podcast && article && live && schedule
}

data class AppSettings(
    val contentKindLabelPosition: ContentKindLabelPosition = ContentKindLabelPosition.BEFORE,
    val playbackRateRememberMode: PlaybackRateRememberMode = PlaybackRateRememberMode.GLOBAL,
    val pushPreferences: PushPreferences = PushPreferences()
)

data class ContactDraft(
    val name: String = "",
    val message: String = DEFAULT_CONTACT_MESSAGE
) {
    companion object {
        const val DEFAULT_CONTACT_MESSAGE: String = "\nWysłane przy pomocy aplikacji Tyflocentrum"
    }
}

enum class FavoriteKind(val title: String) {
    PODCAST("Podcasty"),
    ARTICLE("Artykuły"),
    TOPIC("Tematy"),
    LINK("Linki")
}

enum class FavoritesFilter(val title: String, val kind: FavoriteKind?) {
    ALL("Wszystkie", null),
    PODCASTS("Podcasty", FavoriteKind.PODCAST),
    ARTICLES("Artykuły", FavoriteKind.ARTICLE),
    TOPICS("Tematy", FavoriteKind.TOPIC),
    LINKS("Linki", FavoriteKind.LINK)
}

enum class FavoriteArticleOrigin {
    POST,
    PAGE
}

@Serializable
sealed class FavoriteItem {
    abstract val id: String
    abstract val kind: FavoriteKind
    abstract val title: String
    abstract val subtitle: String?

    @Serializable
    @SerialName("podcast")
    data class PodcastFavorite(
        val summary: WpPostSummary
    ) : FavoriteItem() {
        override val id: String = "podcast.${summary.id}"
        override val kind: FavoriteKind = FavoriteKind.PODCAST
        override val title: String = summary.title.plainText
        override val subtitle: String = summary.formattedDate
    }

    @Serializable
    @SerialName("article")
    data class ArticleFavorite(
        val summary: WpPostSummary,
        val origin: FavoriteArticleOrigin
    ) : FavoriteItem() {
        override val id: String = "article.${origin.name.lowercase()}.${summary.id}"
        override val kind: FavoriteKind = FavoriteKind.ARTICLE
        override val title: String = summary.title.plainText
        override val subtitle: String = summary.formattedDate
    }

    @Serializable
    @SerialName("topic")
    data class TopicFavorite(
        val podcastId: Int,
        val podcastTitle: String,
        val podcastSubtitle: String? = null,
        val topicTitle: String,
        val seconds: Double
    ) : FavoriteItem() {
        override val id: String = "topic.$podcastId.${seconds.toInt()}.${topicTitle.trim().lowercase()}"
        override val kind: FavoriteKind = FavoriteKind.TOPIC
        override val title: String = topicTitle
        override val subtitle: String = podcastTitle
    }

    @Serializable
    @SerialName("link")
    data class LinkFavorite(
        val podcastId: Int,
        val podcastTitle: String,
        val podcastSubtitle: String? = null,
        val linkTitle: String,
        val urlString: String
    ) : FavoriteItem() {
        override val id: String = "link.$podcastId.${urlString.lowercase()}"
        override val kind: FavoriteKind = FavoriteKind.LINK
        override val title: String = linkTitle
        override val subtitle: String = podcastTitle
    }
}

data class ChapterMarker(
    val title: String,
    val seconds: Double
) {
    val id: String = "${seconds.toInt()}-$title"
}

data class RelatedLink(
    val title: String,
    val url: String
) {
    val id: String = "$title-$url"
}

data class ShowNotesData(
    val markers: List<ChapterMarker> = emptyList(),
    val links: List<RelatedLink> = emptyList()
)

data class PlayerRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val isLive: Boolean = false,
    val podcastPostId: Int? = null,
    val initialSeekMs: Long? = null
)

data class PlayerUiState(
    val current: PlayerRequest? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val isRemotePlayback: Boolean = false,
    val durationMs: Long? = null,
    val elapsedMs: Long = 0,
    val playbackRate: Float = 1f,
    val errorMessage: String? = null
)

fun String.htmlToPlainText(): String {
    val text = Jsoup.parse(this).text()
        .replace('\u00A0', ' ')
        .trim()
    return if (text.isBlank()) trim() else text
}

fun String.formatWpDate(): String {
    return try {
        val parsed = LocalDateTime.parse(this, wpDateParser)
            .atZone(ZoneId.systemDefault())
        displayDateFormatter.format(parsed)
    } catch (_: Exception) {
        this
    }
}

fun WpPostSummary.toDetailStub(): WpPostDetail {
    return WpPostDetail(
        id = id,
        date = date,
        title = title,
        excerpt = excerpt ?: WpRenderedText(""),
        content = WpRenderedText(""),
        guid = WpRenderedText(link)
    )
}

fun ContentKind.accessibilityTitle(title: String, position: ContentKindLabelPosition): String {
    return when (position) {
        ContentKindLabelPosition.BEFORE -> "$label. $title"
        ContentKindLabelPosition.AFTER -> "$title. $label"
    }
}

fun List<Comment>.toThreadedComments(): List<ThreadedComment> {
    if (isEmpty()) return emptyList()

    val commentById = associateBy { it.id }
    val childrenByParent = groupBy { it.parent }
    val result = mutableListOf<ThreadedComment>()
    val comparator = compareBy<Comment>({ it.date.toWpDateOrNull() ?: LocalDateTime.MIN }, { it.id })

    fun appendThread(comment: Comment, depth: Int) {
        result += ThreadedComment(
            comment = comment,
            depth = depth,
            parentAuthorName = commentById[comment.parent]?.authorName
        )
        childrenByParent[comment.id]
            .orEmpty()
            .sortedWith(comparator)
            .forEach { child ->
                appendThread(child, depth + 1)
            }
    }

    this
        .filter { it.parent == 0 || commentById[it.parent] == null }
        .sortedWith(comparator)
        .forEach { root ->
            appendThread(root, depth = 0)
        }

    return result
}

private fun String.toWpDateOrNull(): LocalDateTime? {
    return runCatching { LocalDateTime.parse(this, wpDateParser) }.getOrNull()
}
