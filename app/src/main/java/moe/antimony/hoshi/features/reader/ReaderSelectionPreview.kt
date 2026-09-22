package moe.antimony.hoshi.features.reader

internal fun readerSelectionPreviewConfigurationScript(
    darkMode: Boolean,
    eInkMode: Boolean,
    verticalWriting: Boolean,
): String = """
    window.hoshiReaderSelectionPreview.configure({
      darkMode: $darkMode,
      eInkMode: $eInkMode,
      verticalWriting: $verticalWriting
    });
""".trimIndent()
