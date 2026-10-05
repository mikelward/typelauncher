package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Receives Android's pin-shortcut requests — a browser's "Add to Home screen",
 * or a PWA install that falls back to a shortcut (Firefox, a Chromium browser
 * without a WebAPK, Chrome on a device without Play services) — and, once the
 * user confirms in [PinShortcutConfirmDialog], accepts them so the page joins
 * the app list. The system only routes these requests to the default home
 * app, and the `LauncherApps` callback that follows the accept reloads the
 * app list.
 *
 * Asks rather than accepting outright: any app on screen may request a pin,
 * with whatever label and icon it likes, so the requesting app's own
 * confirmation (a browser's) can't be relied on.
 *
 * Once confirmed, the accept runs on the process-wide scope, not this
 * activity's: Back, navigating away, or anything else that finishes the
 * activity early can't cancel it. The activity stays open until the accept
 * finishes to keep a visible activity for its duration. The manifest declares
 * every configuration change so a rotation doesn't recreate it; a restore
 * after process death re-reads the request and asks again only if it is
 * still valid (an answered one no longer is).
 *
 * Exported because the system starts it. A forged intent is harmless: only a
 * request the system issued round-trips through `getPinItemRequest` as valid.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No early return on a restore: after the process was killed with the
        // dialog up, the restored intent still carries the request, and the
        // validity check below says whether it is still waiting for an answer.
        // getPinItemRequest only unparcels the intent extra; the calls into
        // the shortcut service happen in readPendingPin and loadPinIcon, off the main
        // thread.
        val launcherApps = getSystemService<LauncherApps>()
        val request = try {
            launcherApps?.getPinItemRequest(intent)
        } catch (exception: RuntimeException) {
            LauncherDebugLog.failure(exception, "PinShortcutActivity request unreadable")
            null
        }
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            LauncherDebugLog.event("PinShortcutActivity ignored request type=%s", request?.requestType)
            finish()
            return
        }
        val density = resources.displayMetrics.densityDpi
        // Rasterized at the dialog's 48 dp so a large adaptive icon isn't
        // scaled down on every frame.
        val iconPx = (48 * resources.displayMetrics.density).toInt()
        setContent {
            TypeLauncherTheme {
                // What the dialog needs from the request is a call into the
                // shortcut service — the shortcut itself and whether the
                // request is still waiting for an answer (one restored after
                // process death may not be) — so it is read off the main
                // thread before the dialog shows. The icon loads after, so a
                // slow icon never holds the dialog back.
                val pending by produceState<PendingPin?>(initialValue = null) {
                    value = withContext(LauncherDispatchers.io) { readPendingPin(request) }
                }
                LaunchedEffect(pending) {
                    if (pending == PendingPin.Unusable) {
                        LauncherDebugLog.event("PinShortcutActivity request unusable")
                        finish()
                    }
                }
                val ready = pending as? PendingPin.Ready
                val icon by produceState<ImageBitmap?>(initialValue = null, ready) {
                    val shortcut = ready?.shortcut ?: return@produceState
                    value = withContext(LauncherDispatchers.io) { loadPinIcon(launcherApps, shortcut, density, iconPx) }
                }
                var confirmed by remember { mutableStateOf(false) }
                if (ready != null && !confirmed) {
                    PinShortcutConfirmDialog(
                        label = ready.label,
                        icon = icon,
                        onAdd = {
                            confirmed = true
                            accept(request, ready.shortcut)
                        },
                        onCancel = {
                            LauncherDebugLog.event("PinShortcutActivity declined")
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun accept(request: LauncherApps.PinItemRequest, shortcut: ShortcutInfo) {
        val app = applicationContext
        val scope = (application as? TypeLauncherApp)?.appScope ?: lifecycleScope
        val accept = scope.launch(LauncherDispatchers.io) { acceptPinRequest(app, request, shortcut) }
        lifecycleScope.launch {
            accept.join()
            finish()
        }
    }
}

/** What the confirmation dialog shows, once read off the main thread. */
private sealed interface PendingPin {
    /** [shortcut] is kept so the accept doesn't read it from the service again. */
    data class Ready(val shortcut: ShortcutInfo, val label: String) : PendingPin

    /** Expired, already answered, or unreadable: nothing to ask about. */
    data object Unusable : PendingPin
}

private fun readPendingPin(request: LauncherApps.PinItemRequest): PendingPin {
    val shortcut = try {
        request.shortcutInfo?.takeIf { request.isValid }
    } catch (exception: RuntimeException) {
        LauncherDebugLog.failure(exception, "PinShortcutActivity request unreadable")
        null
    } ?: return PendingPin.Unusable
    val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString().orEmpty()
    return PendingPin.Ready(shortcut = shortcut, label = label)
}

// Null — the dialog keeps its placeholder — when the icon can't be read; the
// pin itself is unaffected.
private fun loadPinIcon(launcherApps: LauncherApps?, shortcut: ShortcutInfo, density: Int, iconPx: Int): ImageBitmap? =
    try {
        launcherApps?.getShortcutIconDrawable(shortcut, density)?.toBitmap(iconPx, iconPx)?.asImageBitmap()
    } catch (exception: RuntimeException) {
        LauncherDebugLog.failure(exception, "PinShortcutActivity icon unavailable")
        null
    }

/**
 * Accepts [request] and reports the outcome in a toast. Runs on IO: the
 * accept is a call into the system's shortcut service and the reveal reads
 * and writes the hidden-app store on disk.
 *
 * A request for a shortcut that is already pinned asks for another launcher
 * icon. The app list has one entry per shortcut, so the answer is to make
 * sure that entry shows — the user may have hidden it — and say it's already
 * there. A failed reveal write leaves the page hidden, so it reports as a
 * failed add rather than "Already in app list".
 */
private suspend fun acceptPinRequest(app: Context, request: LauncherApps.PinItemRequest, shortcut: ShortcutInfo) {
    // From the ShortcutInfo read for the dialog: isPinned is a field on it,
    // not another call to the shortcut service.
    val alreadyPinned = shortcut.isPinned
    val accepted = try {
        // Under the pinned-set lock so a Remove's read-and-replace can't
        // overwrite this pin with a set it read before the pin landed.
        pinnedShortcutSetLock.withLock { request.isValid && request.accept() }
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        // The request expired, or the publisher's profile locked, between the
        // system starting the activity and the accept.
        LauncherDebugLog.failure(exception, "PinShortcutActivity accept failed")
        false
    }
    // Every accepted shortcut is revealed, not just a repeat pin: a hidden id
    // can outlive its publisher's uninstall and come back as a fresh pin
    // with the same id. Revealing a page that wasn't hidden is a no-op.
    val succeeded = if (accepted) {
        PinnedShortcutReveals.reveal(app, pinnedShortcutEntryId(shortcut.userHandle, shortcut.`package`, shortcut.id))
    } else {
        accepted
    }
    LauncherDebugLog.event("PinShortcutActivity accepted=%s alreadyPinned=%s", succeeded, alreadyPinned)
    withContext(Dispatchers.Main) {
        Toast.makeText(
            app,
            when {
                !succeeded -> R.string.shortcut_pin_failed
                alreadyPinned -> R.string.shortcut_already_listed
                else -> R.string.shortcut_pinned
            },
            Toast.LENGTH_SHORT,
        ).show()
    }
}
