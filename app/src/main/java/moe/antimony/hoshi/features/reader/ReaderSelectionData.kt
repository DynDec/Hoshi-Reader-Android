package moe.antimony.hoshi.features.reader

data class ReaderSelectionData(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionRect,
    val normalizedOffset: Int?,
    val sentenceOffset: Int? = null,
    val sourceIndex: Int? = null,
    /** Set when the selection came from a lookup shift swipe in this direction. */
    val shiftDelta: Int? = null,
)

data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)
