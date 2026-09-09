package app.typelauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Asks, once, whether the launcher may send crash reports.
 *
 * Shown at the top of Settings while the question is unanswered, with a dot on
 * the Settings gear so it is findable from the home screen (see `SearchCard`'s
 * badge slot). Either answer retires both, and so does using the Analytics
 * switch further down the same screen — that is answering the question too.
 *
 * Until it *is* answered nothing is uploaded (see [TELEMETRY_REQUIRES_CONSENT]),
 * so the card is not gating a feature the user is waiting on; it exists so the
 * default isn't decided silently on their behalf.
 *
 * Stateless, like [CrashBannerCard] beside it, so the screenshot test renders it
 * directly.
 */
@Composable
internal fun TelemetryConsentCard(
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TELEMETRY_CONSENT_TAG),
    ) {
        Text(
            text = stringResource(R.string.telemetry_consent_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.telemetry_consent_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        // Opposite ends, not the right-aligned pair [CrashBannerCard] uses. This
        // is a two-way choice with no default — neither answer is the one we are
        // steering toward — so separating them makes them read as alternatives
        // rather than as an action and its escape hatch. The positive action
        // stays on the right either way.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // `weight(fill = false)` caps each button at half the row while
            // letting it stay its natural size when it fits. Without the cap a
            // long translated pair (Greek is the longest of the 63) overruns
            // the row and the labels are squeezed into their buttons' minimum
            // width and truncated; with it, the label wraps instead. Both keep
            // their own horizontal content padding, so even two half-width
            // buttons stay visually apart.
            TextButton(
                onClick = onDeny,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(TELEMETRY_CONSENT_DENY_TAG),
            ) {
                Text(stringResource(R.string.telemetry_consent_deny))
            }
            Button(
                onClick = onAllow,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(TELEMETRY_CONSENT_ALLOW_TAG),
            ) {
                Text(stringResource(R.string.telemetry_consent_allow))
            }
        }
    }
}
