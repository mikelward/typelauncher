package app.typelauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Add to app list" share target: any app sharing a web page (a
 * browser's Share, most often) can add it to the app list as a web link,
 * once the user confirms its name in [AddWebLinkDialog]. Only http and https
 * addresses are taken; anything else gets a toast and nothing is added.
 *
 * Like [PinShortcutActivity], the add runs on the process-wide scope so
 * finishing the activity can't cancel it, and the activity stays until it
 * lands. Exported because other apps start it; a share carries only text,
 * and nothing is added until the user taps Add.
 */
class AddWebLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            parseSharedWebLink(
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
            )
        } else {
            null
        }
        if (shared == null) {
            // Nothing about what was shared: it is the sharing app's content.
            LauncherDebugLog.event("AddWebLinkActivity ignored share: not a web page")
            Toast.makeText(this, R.string.web_link_not_a_page, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            TypeLauncherTheme {
                var name by rememberSaveable { mutableStateOf(shared.name) }
                // Not saved: after process death the add may never have run,
                // so the dialog asks again (adding twice keeps one link).
                var confirmed by remember { mutableStateOf(false) }
                if (!confirmed) {
                    AddWebLinkDialog(
                        name = name,
                        onNameChange = { name = it },
                        url = shared.url,
                        onAdd = {
                            confirmed = true
                            add(name.trim(), shared.url)
                        },
                        onCancel = {
                            LauncherDebugLog.event("AddWebLinkActivity declined")
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun add(name: String, url: String) {
        val app = applicationContext
        val scope = (application as? TypeLauncherApp)?.appScope ?: lifecycleScope
        val add = scope.launch(LauncherDispatchers.io) { addWebLink(app, name, url) }
        lifecycleScope.launch {
            add.join()
            finish()
        }
    }
}

/**
 * Adds the link and reports the outcome in a toast. On IO: both stores write
 * to disk. A page already in the list keeps its entry and is revealed — the
 * user may have hidden it — the way a repeat pin is; a failed reveal leaves it
 * hidden, so it reports as a failed add.
 */
private suspend fun addWebLink(app: Context, name: String, url: String) {
    val result = WebLinkStore(app).add(name, url)
    val succeeded = result != null &&
        PinnedShortcutReveals.reveal(app, webLinkEntryId(android.os.Process.myUserHandle(), result.link.id))
    LauncherDebugLog.event("AddWebLinkActivity added=%s new=%s", succeeded, result?.isNew)
    withContext(Dispatchers.Main) {
        Toast.makeText(
            app,
            when {
                !succeeded -> R.string.shortcut_pin_failed
                result?.isNew == false -> R.string.shortcut_already_listed
                else -> R.string.shortcut_pinned
            },
            Toast.LENGTH_SHORT,
        ).show()
    }
}
