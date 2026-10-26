package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionBridgeTest {
    @Test
    fun groupedLookupPayloadKeepsOriginalSourceAndLocalNormalizedOffset() {
        val payload = """{"text":"犬","sentence":"犬です。","rect":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},"sourceIndex":2,"normalizedOffset":4,"sentenceOffset":1}"""
        val parsed = requireNotNull(ReaderSelectionBridgePayload.fromJson(payload))
        assertEquals(2, parsed.sourceIndex)
        assertEquals(4, parsed.normalizedOffset)
        assertEquals(1, parsed.sentenceOffset)
        assertEquals(null, parsed.shiftDelta)
    }

    @Test
    fun shiftedLookupPayloadKeepsShiftDirection() {
        val payload = """{"text":"犬","sentence":"犬です。","rect":{"x":0.0,"y":0.0,"width":10.0,"height":10.0},"shiftDelta":-1}"""
        assertEquals(-1, requireNotNull(ReaderSelectionBridgePayload.fromJson(payload)).shiftDelta)
    }
    @Test
    fun parsesTextSelectionPayloadLikeIosMessageBody() {
        val payload = """
            {
                "text": "食べる",
                "sentence": "私は食べる。",
                "rect": {
                    "x": 12.5,
                    "y": 24.25,
                    "width": 40.0,
                    "height": 18.0
                },
                "normalizedOffset": 42,
                "futureField": "ignored"
            }
        """.trimIndent()

        assertEquals(
            ReaderSelectionData(
                text = "食べる",
                sentence = "私は食べる。",
                rect = ReaderSelectionRect(
                    x = 12.5,
                    y = 24.25,
                    width = 40.0,
                    height = 18.0,
                ),
                normalizedOffset = 42,
            ),
            ReaderSelectionBridgePayload.fromJson(payload),
        )
    }
}
