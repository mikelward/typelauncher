package app.typelauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries

/**
 * Open-source attribution, reached from Settings → About → Licenses: every
 * third-party component bundled in the APK, and the license each ships under.
 *
 * The list is read from the committed `res/raw/aboutlibraries.json`,
 * regenerated with `./gradlew :app:exportBundledLicenses` — the AboutLibraries
 * plugin can't wire the resource in automatically under AGP 9 (see
 * app/build.gradle.kts).
 */
@Composable
internal fun LicensesScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    // System-bar icon contrast, for as long as this page is up.
    //
    // With "Show wallpaper" on, Settings hands the bars a *wallpaper*-derived
    // contrast and never restores it — the window keeps whatever it was last
    // told, and Home re-applies its own on the way back, so Settings has
    // nothing to undo. This page breaks that assumption: it replaces Settings
    // with an opaque themed background, so those wallpaper-derived icons can
    // land on a surface they have no contrast against — light icons on a light
    // page, and the system controls vanish until the user leaves.
    //
    // So drive the contrast from this page's own background while it is
    // showing, and hand back exactly what was there on the way out, so
    // returning to Settings finds the window as it left it.
    val background = MaterialTheme.colorScheme.background
    DisposableEffect(view, background) {
        val bars = context.findActivity()?.window?.let { WindowInsetsControllerCompat(it, view) }
        val previousStatusBars = bars?.isAppearanceLightStatusBars
        val previousNavigationBars = bars?.isAppearanceLightNavigationBars
        // "Light appearance" means a light *background*, which is what asks
        // for dark icons — so it tracks the page's own luminance.
        val lightBackground = background.luminance() > 0.5f
        bars?.isAppearanceLightStatusBars = lightBackground
        bars?.isAppearanceLightNavigationBars = lightBackground
        onDispose {
            previousStatusBars?.let { bars?.isAppearanceLightStatusBars = it }
            previousNavigationBars?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }
    // rememberLibraries parses the bundled JSON off the composition thread and
    // swaps it in when ready, so the page's header paints in the first frame
    // rather than waiting on a ~150 KB parse — this replaces Settings on a tap,
    // and an empty pause there reads as a dead button.
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    LicensesContent(
        libraries = libraries,
        innerPadding = innerPadding,
        onBack = onBack,
        onOpenLicenseUrl = { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (exception: ActivityNotFoundException) {
                // No browser (or a stripped device). Same guard, and the same
                // log, as the About dialog's privacy-policy link.
                LauncherDebugLog.failure(exception, "license link: no activity for %s", url)
            }
        },
    )
}

@Composable
internal fun LicensesContent(
    libraries: Libs?,
    innerPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenLicenseUrl: (String) -> Unit = {},
) {
    // Back returns to Settings rather than closing it: this handler sits below
    // TypeLauncherApp's settings-wide one in the composition, so it wins while
    // the page is up.
    BackHandler(enabled = true, onBack = onBack)
    // The tapped component's stable id, if any — its details fill the dialog
    // below. Saved (not a plain remember) so an open dialog survives rotation
    // and process death; resolved back to the library once the list is loaded.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(libraries, selectedId) {
        selectedId?.let { id -> libraries?.libraries?.firstOrNull { it.uniqueId == id } }
    }
    // The export lists components in dependency-coordinate order, which reads
    // as no order at all once the coordinates themselves are hidden —
    // "Experimental annotation" lands nowhere near "Annotation". The displayed
    // name is the only thing a reader can scan by here, and there is no search,
    // so sort on exactly that. Case-insensitive so a lowercase coordinate
    // fallback name doesn't sort into its own block after the Z's.
    val sortedLibraries = remember(libraries) {
        libraries?.libraries.orEmpty()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Library::name))
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque, unlike Settings, which lets the wallpaper through behind
            // its cards: this page is a dense uncarded list of small text, and
            // over an arbitrary wallpaper that is the one layout here that
            // can't stay legible.
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .testTag(SETTINGS_LICENSES_SCREEN_TAG),
    ) {
        // Same header shape as Settings' own — title on the left, the action
        // that leaves the page on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_licenses_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(
                onClick = onBack,
                modifier = Modifier.testTag(SETTINGS_LICENSES_BACK_BUTTON_TAG),
            ) {
                Text(stringResource(R.string.settings_licenses_back_button))
            }
        }
        // Just the component names, one compact row each; the version and
        // license live behind a tap so a 140-row list stays scannable.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SETTINGS_LICENSES_LIST_TAG),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(
                items = sortedLibraries,
                key = { it.uniqueId },
            ) { library ->
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = library.uniqueId }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
    selected?.let { library ->
        LibraryDetailsDialog(
            library = library,
            onOpenLicenseUrl = onOpenLicenseUrl,
            onDismiss = { selectedId = null },
        )
    }
}

/**
 * Version, authors and license(s) for a tapped [library]. The bundled export
 * carries no license text (it's excluded to keep CI's regenerate-and-diff
 * deterministic — see app/build.gradle.kts), so each license with a URL is a
 * link to the full text rather than inline body copy.
 */
@Composable
internal fun LibraryDetailsDialog(
    library: Library,
    onOpenLicenseUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SETTINGS_LICENSES_DETAILS_DIALOG_TAG),
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_licenses_details_dismiss))
            }
        },
        title = { Text(library.name) },
        text = { LibraryDetails(library = library, onOpenLicenseUrl = onOpenLicenseUrl) },
    )
}

/**
 * The dialog's body, split out from the dialog itself so a screenshot test can
 * render it: an `AlertDialog` lives in its own popup window, which the
 * decorView capture helper can't reach. Same split, and the same reason, as
 * `EditAppDialog` / `EditAppDialogContent` in `HomeScreen.kt`.
 */
@Composable
internal fun LibraryDetails(
    library: Library,
    onOpenLicenseUrl: (String) -> Unit,
) {
    val authors = remember(library) { library.authorsOrEmpty() }
    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        library.artifactVersion?.let { version ->
            Text(
                text = stringResource(R.string.settings_licenses_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (authors.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_licenses_authors, authors),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        library.licenses.forEach { license ->
            val url = license.url
            if (!url.isNullOrEmpty()) {
                // A link to the full license text — primary color and a tap
                // target signal it opens in the browser.
                //
                // It is a control, so it owes Android's 48dp minimum touch
                // target: bodyMedium's own line box is about 20dp and the 8dp
                // padding alone left it at roughly 36dp. The min height wins
                // for a one-line name; a name long enough to wrap grows past
                // it and keeps the padding.
                Text(
                    text = license.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLicenseUrl(url) }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(vertical = 8.dp),
                )
            } else {
                Text(
                    text = license.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Who wrote this component, as the export records it — the POM's declared
 * developers, or the organization that published it when no developer is
 * named. Empty when the POM declares neither, and the dialog then shows no
 * authors line at all rather than an empty label.
 *
 * Apache-2.0 §4 asks that attribution travel with the code, and the license
 * name alone does not carry it: a page that says "Apache License 2.0" and
 * nothing else has named the terms without naming who the terms are for.
 * Names only — the export also carries organization URLs, and a second link
 * per component would bury the license link this dialog exists for.
 */
internal fun Library.authorsOrEmpty(): String =
    developers
        .mapNotNull { developer -> developer.name?.takeIf(String::isNotBlank) }
        .ifEmpty { listOfNotNull(organization?.name?.takeIf(String::isNotBlank)) }
        .joinToString(", ")
