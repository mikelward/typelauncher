package app.typelauncher

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag

/**
 * Blocks until every composed [AppIcon]'s async bitmap load has settled —
 * i.e. no placeholder in the tree still carries [APP_ICON_LOADING_TAG].
 *
 * Screenshot tests must call this before capturing. The icon bitmaps load on
 * background dispatchers, so `waitForIdle` alone returns while loads are
 * still in flight and the capture freezes an arbitrary subset of loaded
 * icons — the source of the chronic `ci: refresh recorded screenshots` churn
 * on `main` (every run recorded a different number of icons). Waiting on the
 * loading tag is exact: the tag exists from an icon's first composition until
 * its load resolves, including loads that resolve to "no icon" (which drop
 * the tag without producing a bitmap), so this can't hang on an app that
 * legitimately renders the empty placeholder.
 */
internal fun ComposeTestRule.awaitAppIconsResolved() {
    waitForIdle()
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithTag(APP_ICON_LOADING_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty()
    }
    // The last resolution may have landed between frames; settle the
    // recomposition it triggered so the capture sees the painted bitmaps.
    waitForIdle()
}
