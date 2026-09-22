package moe.antimony.hoshi.features.reader

internal const val ReaderBorderlessTopSafeAreaDp = 10
internal const val ReaderBorderlessBottomSafeAreaDp = 6

/**
 * The configured padding is retained while the border-removal mode is active so
 * disabling it restores the user's normal layout immediately.
 */
internal val ReaderSettings.effectiveVerticalPadding: Int
    get() = if (removeVerticalBorders) 0 else verticalPadding

internal val ReaderSettings.effectiveTopSafeAreaDp: Int
    get() = if (removeVerticalBorders) ReaderBorderlessTopSafeAreaDp else topSafeAreaDp.coerceReaderTopSafeAreaDp()

internal val ReaderSettings.effectiveBottomSafeAreaDp: Int
    get() = if (removeVerticalBorders) ReaderBorderlessBottomSafeAreaDp else bottomSafeAreaDp.coerceReaderBottomSafeAreaDp()
