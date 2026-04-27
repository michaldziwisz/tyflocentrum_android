package net.tyflopodcast.tyflocentrum.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowNotesParserTest {
    @Test
    fun parseExtractsMarkersAndLinksFromComments() {
        val markersHtml = """
            <p>Znaczniki czasu:<br />
            Intro 00:00:00<br />
            Co u nas 00:02:54</p>
        """.trimIndent()

        val linksHtml = """
            <p>A oto odnośniki uzupełniające audycję:<br />
            – Nowy numer Tyfloświata (1/2026) dostępny:<br />
            https://tyfloswiat.pl/czasopismo/tyfloswiat-1-2026-70/<br />
            – Grupa skupiająca testerów dostępnego telegrama na iOS:<br />
            https://t.me/accessiblegram<br />
            e-mail do autora: miet@violinist . pl</p>
        """.trimIndent()

        val comments = listOf(
            Comment(id = 1, post = 123, parent = 0, authorName = "TyfloPodcast", content = Comment.CommentContent(rendered = markersHtml)),
            Comment(id = 2, post = 123, parent = 0, authorName = "TyfloPodcast", content = Comment.CommentContent(rendered = linksHtml))
        )

        val (markers, links) = ShowNotesParser.parse(comments)

        assertEquals(2, markers.size)
        assertEquals("Intro", markers.first().title)
        assertEquals(0.0, markers.first().seconds, 0.0)
        assertEquals("Co u nas", markers.last().title)
        assertEquals(174.0, markers.last().seconds, 0.0)

        val urls = links.map { it.url }.toSet()
        assertTrue(urls.contains("https://tyfloswiat.pl/czasopismo/tyfloswiat-1-2026-70/"))
        assertTrue(urls.contains("https://t.me/accessiblegram"))
        assertTrue(urls.contains("mailto:miet@violinist.pl"))
    }
}
