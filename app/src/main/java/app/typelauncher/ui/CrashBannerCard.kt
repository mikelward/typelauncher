package app.typelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The Home page's post-crash push-down layout. When [showCrashBanner] is true,
 * [CrashBannerCard] takes a strip at the top and [content] shifts down beneath it
 * (never overlapping) — the banner owns the top status-bar inset and shares the
 * search card's side gutter, and [content] is handed a top-zeroed [PaddingValues]
 * so that inset is not applied twice. When it is false this is just [content]
 * filling the page with the unchanged [innerPadding]. Extracted from
 * `TypeLauncherApp` so the push-down (Column + inset remap + weighted shrink) is
 * screenshot-testable without standing up the whole launcher (Codex on PR #593).
 *
 * [wallpaperActive] mirrors `HomeScreen`'s own wallpaper decision (the same
 * `pageRevealsWallpaper` value it renders under): the banner's strip and gutters
 * sit *outside* [content], so this wrapper must make the same
 * transparent-vs-opaque choice, or those margins would leak the system wallpaper
 * while opaque Home below them does not (Codex on PR #593).
 */
@Composable
internal fun HomeContentWithCrashBanner(
    innerPadding: PaddingValues,
    showCrashBanner: Boolean,
    wallpaperActive: Boolean,
    onShareCrash: () -> Unit,
    onDismissCrash: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (homeInnerPadding: PaddingValues) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // Same choice HomeScreen makes: transparent so the window wallpaper
            // shows through when it is Home's backdrop, opaque theme background
            // otherwise — so the banner's surrounding margins never leak wallpaper
            // that opaque Home would hide.
            .background(if (wallpaperActive) Color.Transparent else MaterialTheme.colorScheme.background),
    ) {
        val layoutDirection = LocalLayoutDirection.current
        if (showCrashBanner) {
            CrashBannerCard(
                onShare = onShareCrash,
                onDismiss = onDismissCrash,
                modifier = Modifier
                    .padding(top = innerPadding.calculateTopPadding())
                    .padding(
                        start = innerPadding.calculateStartPadding(layoutDirection) +
                            HOME_CONTENT_HORIZONTAL_INSET_DP.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) +
                            HOME_CONTENT_HORIZONTAL_INSET_DP.dp,
                    ),
            )
        }
        val homeInnerPadding = if (showCrashBanner) {
            PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = 0.dp,
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding(),
            )
        } else {
            innerPadding
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content(homeInnerPadding)
        }
    }
}

/**
 * Post-crash prompt shown at the top of Home when the previous run ended in an
 * uncaught exception (see [DebugFileSink.hasUnacknowledgedCrash]). It offers to
 * send the same bug report the overflow menu builds — so a crash surfaces on
 * its own instead of relying on the user to remember the menu action — or to
 * dismiss it. A routine process death never raises it.
 *
 * Rendered inline as the first element of the Home page (push-down): it takes a
 * strip at the top and the launcher content below shifts down, so it never
 * occludes the app icons. Stateless — [onShare] and [onDismiss] are owned by the
 * caller — so the screenshot test can render it directly.
 */
@Composable
internal fun CrashBannerCard(
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(CRASH_BANNER_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = stringResource(R.string.crash_banner_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.crash_banner_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        // Dismissive action left, confirming action right — the Material dialog
        // convention, matching the approved [Dismiss] [Share] order.
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(CRASH_BANNER_DISMISS_TAG),
            ) {
                Text(stringResource(R.string.crash_banner_dismiss))
            }
            Button(
                onClick = onShare,
                modifier = Modifier.testTag(CRASH_BANNER_SHARE_TAG),
            ) {
                Text(stringResource(R.string.crash_banner_share))
            }
        }
    }
}
