package org.tyflocentrum.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentThreadingTest {
    @Test
    fun toThreadedCommentsSortsOldestFirstAndKeepsRepliesUnderParents() {
        val comments = listOf(
            Comment(
                id = 30,
                post = 1,
                parent = 10,
                date = "2026-04-22T10:30:00",
                authorName = "Cezary",
                content = Comment.CommentContent(rendered = "<p>Nested reply</p>")
            ),
            Comment(
                id = 10,
                post = 1,
                parent = 0,
                date = "2026-04-22T10:00:00",
                authorName = "Anna",
                content = Comment.CommentContent(rendered = "<p>Root A</p>")
            ),
            Comment(
                id = 40,
                post = 1,
                parent = 0,
                date = "2026-04-22T11:00:00",
                authorName = "Dorota",
                content = Comment.CommentContent(rendered = "<p>Root B</p>")
            ),
            Comment(
                id = 20,
                post = 1,
                parent = 10,
                date = "2026-04-22T10:15:00",
                authorName = "Bartek",
                content = Comment.CommentContent(rendered = "<p>Reply A1</p>")
            )
        )

        val threaded = comments.toThreadedComments()

        assertEquals(listOf(10, 20, 30, 40), threaded.map { it.comment.id })
        assertEquals(listOf(0, 1, 1, 0), threaded.map { it.depth })
        assertEquals(listOf(null, "Anna", "Anna", null), threaded.map { it.parentAuthorName })
    }
}
