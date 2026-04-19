package org.tyflocentrum.android.core.model

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import org.jsoup.Jsoup

object SearchRanking {
    fun sort(items: List<SearchItem>, query: String): List<SearchItem> {
        val normalizedQuery = normalize(query)
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.length > 1 }
        return items.sortedWith(
            compareByDescending<SearchItem> { score(it, normalizedQuery, tokens) }
                .thenByDescending { it.post.date }
                .thenBy { it.kind.ordinal }
                .thenByDescending { it.post.id }
        )
    }

    private fun score(item: SearchItem, normalizedQuery: String, tokens: List<String>): Int {
        val normalizedTitle = normalize(item.post.title.plainText)
        return when {
            normalizedQuery.isNotBlank() && normalizedTitle.contains(normalizedQuery) -> 2
            tokens.isNotEmpty() && tokens.all { normalizedTitle.contains(it) } -> 1
            else -> 0
        }
    }

    private fun normalize(value: String): String {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .trim()
            .lowercase(Locale("pl", "PL"))
    }
}

object ShowNotesParser {
    private val timeCodeRegex = Regex("""(?:\b\d{1,2}:\d{2}:\d{2}\b|\b\d{1,2}:\d{2}\b)$""")
    private val emailRegex = Regex("""[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)

    fun parse(comments: List<Comment>): Pair<List<ChapterMarker>, List<RelatedLink>> {
        val markers = mutableListOf<ChapterMarker>()
        val links = mutableListOf<RelatedLink>()

        comments.forEach { comment ->
            val lines = normalizedLines(comment.content.rendered)
            markers += parseMarkers(lines)
            links += parseLinks(lines)
        }

        return markers.distinctBy { "${it.seconds.toInt()}|${it.title.lowercase()}" }.sortedBy { it.seconds } to
            links.distinctBy { "${it.title.lowercase()}|${it.url.lowercase()}" }
    }

    private fun normalizedLines(html: String): List<String> {
        return Jsoup.parse(html).text()
            .split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseMarkers(lines: List<String>): List<ChapterMarker> {
        val headerIndex = lines.indexOfFirst {
            val normalized = it.lowercase(Locale("pl", "PL"))
            normalized.startsWith("znaczniki czasu") || normalized.startsWith("znaczniki czasowe")
        }
        if (headerIndex == -1) return emptyList()
        return lines.drop(headerIndex + 1).mapNotNull { line ->
            val match = timeCodeRegex.find(line) ?: return@mapNotNull null
            val timeString = match.value
            val seconds = parseTimeCode(timeString) ?: return@mapNotNull null
            val title = line.removeSuffix(timeString)
                .trim()
                .trim('–', '—', '-', ':')
                .trim()
            if (title.isBlank()) null else ChapterMarker(title, seconds)
        }
    }

    private fun parseLinks(lines: List<String>): List<RelatedLink> {
        val headerIndex = lines.indexOfFirst {
            val normalized = it.lowercase(Locale("pl", "PL"))
            normalized.contains("odnośnik") || normalized.contains("odnosnik") || normalized.contains("linki")
        }
        if (headerIndex == -1) return emptyList()

        val result = mutableListOf<RelatedLink>()
        var currentTitle: String? = null
        val currentUrls = mutableListOf<String>()

        fun flushCurrent() {
            val title = currentTitle?.takeIf { it.isNotBlank() } ?: run {
                currentUrls.clear()
                return
            }
            currentUrls.distinct().forEach { url ->
                result += RelatedLink(title = title, url = url)
            }
            currentUrls.clear()
        }

        lines.drop(headerIndex + 1).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("–") || trimmed.startsWith("-")) {
                flushCurrent()
                val content = trimmed.drop(1).trim()
                val urls = extractUrls(content)
                currentTitle = urls.fold(content) { acc, url -> acc.replace(url, "") }
                    .trim()
                    .trim(':')
                    .trim()
                currentUrls += urls
                return@forEach
            }

            val urls = extractUrls(trimmed)
            if (urls.isNotEmpty()) {
                currentUrls += urls
                return@forEach
            }

            emailRegex.find(trimmed)?.value?.let { email ->
                flushCurrent()
                val label = trimmed.substringBefore(':').trim().ifBlank { "E-mail" }
                result += RelatedLink(label, "mailto:$email")
            }
        }
        flushCurrent()

        return result
    }

    private fun extractUrls(line: String): List<String> {
        return line.split(Regex("\\s+"))
            .map { it.trim().trim('.', ',', ';', ')', ']', '"', '\'') }
            .filter { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun parseTimeCode(value: String): Double? {
        val parts = value.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size !in 2..3) return null
        return if (parts.size == 3) {
            (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
        } else {
            (parts[0] * 60 + parts[1]).toDouble()
        }
    }
}

object MagazineParser {
    private val issuePattern = Regex("""(\d{1,2})\s*/\s*(\d{4})""")
    private val yearPattern = Regex("""(19\d{2}|20\d{2})""")
    private val pdfPattern = Pattern.compile("""href\s*=\s*['"]([^'"]+\.pdf[^'"]*)['"]""", Pattern.CASE_INSENSITIVE)

    fun parseIssueNumberAndYear(title: String): Pair<Int?, Int?> {
        issuePattern.find(title)?.let { match ->
            return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        }
        yearPattern.find(title)?.let { match ->
            return null to match.groupValues[1].toIntOrNull()
        }
        return null to null
    }

    fun extractFirstPdfUrl(html: String): String? {
        val matcher = pdfPattern.matcher(html)
        return if (matcher.find()) normalizeLink(matcher.group(1).orEmpty()) else null
    }

    fun orderedTableOfContents(children: List<WpPostSummary>, issueHtml: String): List<WpPostSummary> {
        if (children.isEmpty()) return emptyList()

        val orderedLinks = Jsoup.parse(issueHtml)
            .select("a[href]")
            .map { it.attr("href") }
            .map(::normalizeLink)
            .filter { link ->
                !link.lowercase(Locale("pl", "PL")).contains(".pdf") && link.contains("tyfloswiat.pl/czasopismo/")
            }

        val byLink = children.associateBy { normalizeLink(it.link) }
        val seen = mutableSetOf<Int>()
        val ordered = orderedLinks.mapNotNull { link ->
            byLink[link]?.takeIf { seen.add(it.id) }
        }
        val remaining = children.filterNot { seen.contains(it.id) }
            .sortedWith(compareByDescending<WpPostSummary> { it.date }.thenByDescending { it.id })
        return ordered + remaining
    }

    fun normalizeLink(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("/")) {
            return normalizeLink("https://tyfloswiat.pl$trimmed")
        }
        return trimmed
            .replace("http://", "https://")
            .trimEnd('/')
    }
}

object PlaybackRatePolicy {
    val supportedRates: List<Float> = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.2f, 2.5f, 2.8f, 3f)

    fun normalized(rate: Float): Float {
        if (!rate.isFinite() || rate <= 0f) return 1f
        return supportedRates.minBy { abs(it - rate) }
    }

    fun next(rate: Float): Float {
        val current = supportedRates.indexOf(normalized(rate))
        return supportedRates[(current + 1) % supportedRates.size]
    }

    fun previous(rate: Float): Float {
        val current = supportedRates.indexOf(normalized(rate))
        return supportedRates[if (current == 0) supportedRates.lastIndex else current - 1]
    }

    fun format(rate: Float): String {
        return if (rate % 1f == 0f) {
            rate.toInt().toString()
        } else {
            rate.toString()
        }
    }
}
