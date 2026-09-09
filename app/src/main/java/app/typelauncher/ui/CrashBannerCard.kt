package app.typelauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Post-crash prompt shown at the top of Settings when the previous run ended
 * in an uncaught exception (see [DebugFileSink.hasUnacknowledgedCrash]). It
 * offers to send the same bug report the overflow menu builds — so a crash
 * surfaces somewhere the user will see it without relying on them remembering
 * the menu action — or to dismiss it. A routine process death never raises
 * it, though the underlying classification can still misfire; the title asks
 * rather than asserts for exactly that reason.
 *
 * Rendered as the first item in Settings' scrollable content (see
 * `SettingsScreen`, right after the title row) rather than on Home: a crash
 * is not urgent enough to interrupt the home screen with a push-down layout,
 * and the Settings gear already carries a small warning-triangle badge (see
 * `SearchCard`) so the prompt is discoverable without being disruptive.
 * Stateless — [onShare] and [onDismiss] are owned by the caller — so the
 * screenshot test can render it directly.
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
