package moe.antimony.hoshi.features.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSasayakiCuesTest {
    @Test
    fun groupCuesCarryOriginalChapterIdentityWithoutChangingSourceMatchOffsets() {
        val original = listOf(match("first", 0, 3, 2), match("last", 2, 1, 1), match("outside", 3, 4, 2))
        val data = SasayakiMatchData(matches = original, unmatched = 0)
        val group = ContinuousReadingGroup(listOf(0, 1, 2), listOf(10, 0, 5))
        val cues = Json.parseToJsonElement(ReaderSasayakiCues.groupCuesJson(data, group)!!).jsonArray
        assertEquals(listOf("0", "2"), cues.map { it.jsonObject.getValue("sourceIndex").jsonPrimitive.content })
        assertEquals(listOf("3", "1"), cues.map { it.jsonObject.getValue("start").jsonPrimitive.content })
        assertEquals(original, data.matches)
    }
    @Test
    fun chapterCuesSerializesOnlyCurrentChapterCuesSortedByStart() {
        val matchData = SasayakiMatchData(
            matches = listOf(
                match(id = "later", chapterIndex = 1, start = 20, length = 3),
                match(id = "other", chapterIndex = 0, start = 1, length = 4),
                match(id = "first", chapterIndex = 1, start = 10, length = 2),
            ),
            unmatched = 0,
        )

        val json = ReaderSasayakiCues.chapterCuesJson(matchData, chapterIndex = 1)
        val cues = Json.parseToJsonElement(requireNotNull(json)).jsonArray

        assertEquals(listOf("first", "later"), cues.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("10", "20"), cues.map { it.jsonObject.getValue("start").jsonPrimitive.content })
        assertEquals(listOf("2", "3"), cues.map { it.jsonObject.getValue("length").jsonPrimitive.content })
    }

    @Test
    fun chapterCuesReturnsNullWhenChapterHasNoMatches() {
        val matchData = SasayakiMatchData(
            matches = listOf(match(id = "other", chapterIndex = 0, start = 1, length = 4)),
            unmatched = 0,
        )

        assertNull(ReaderSasayakiCues.chapterCuesJson(matchData, chapterIndex = 1))
    }

    private fun match(
        id: String,
        chapterIndex: Int,
        start: Int,
        length: Int,
    ): SasayakiMatch =
        SasayakiMatch(
            id = id,
            startTime = start.toDouble(),
            endTime = start.toDouble() + 1,
            text = id,
            chapterIndex = chapterIndex,
            start = start,
            length = length,
        )
}
