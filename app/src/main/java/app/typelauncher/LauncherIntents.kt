package app.typelauncher

import android.content.Intent
import android.net.Uri

internal fun Intent.asLauncherTaskIntent(): Intent =
    Intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

// Search the app store for a typed query that matched no installed app. The
// `market://` intent hands the search to the Play Store app; the `https://`
// form is the fallback for a device with no store app but a browser. `c=apps`
// scopes the results to apps rather than books / movies. The query is
// percent-encoded so spaces and `&` in an app name can't break the URI.
// TODO: a "preferred app store" setting would swap these for F-Droid / Aurora /
// a vendor store — see TODO.md.
internal fun playStoreSearchIntent(query: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}&c=apps"))

internal fun playStoreSearchWebIntent(query: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps"))
