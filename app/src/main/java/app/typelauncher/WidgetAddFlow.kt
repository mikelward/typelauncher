package app.typelauncher

import android.appwidget.AppWidgetManager

/**
 * State machine for the add-widget flow: allocate → (system bind dialog) →
 * (configure activity) → add, with the allocated widget ID deleted on every
 * abandoned path so canceled flows never leak a bound widget instance in
 * `AppWidgetService`.
 *
 * The single piece of state is [pendingWidgetId], the ID of the widget whose
 * bind or configure step is currently in flight. It exists because activity
 * results are not guaranteed to carry `EXTRA_APPWIDGET_ID` back — a configure
 * activity canceled with back returns `RESULT_CANCELED` with null data, and
 * some configure activities return `RESULT_OK` without the extra — so the
 * result handlers fall back to it via [resolveResultWidgetId]. It must
 * therefore stay set for the whole life of an in-flight launch: clearing it
 * while a configure activity is foreground (the pre-extraction
 * `MainActivity` bug) turned every data-less configure result into
 * `INVALID_APPWIDGET_ID`, so canceled configures skipped the delete and
 * permanently leaked the already-bound widget ID.
 *
 * Extracted from `MainActivity` so the transition rules are unit-testable;
 * the activity owns the platform calls and injects them as callbacks.
 *
 * @param launchConfigure starts the provider's configure activity for the
 *   given widget ID, reporting [ConfigureLaunch.NotNeeded] when the provider
 *   has no configure activity and [ConfigureLaunch.Failed] when the launch
 *   itself blew up (so the bound ID is deleted, not added half-configured).
 * @param addWidget commits the bound-and-configured widget to the launcher.
 * @param deleteWidget frees an allocated widget ID that will never be added.
 */
internal class WidgetAddFlow(
    private val launchConfigure: (Int) -> ConfigureLaunch,
    private val addWidget: (Int) -> Unit,
    private val deleteWidget: (Int) -> Unit,
) {
    enum class ConfigureLaunch { Launched, NotNeeded, Failed }

    var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
        private set

    /**
     * True while an allocated ID's bind or configure step is in flight. The
     * flow tracks exactly one add at a time (see [pendingWidgetId]), so the
     * host must refuse to start a second add — before allocating its ID —
     * while this is true. Without the guard, a second "Add" tap during the
     * few hundred ms before a configure/bind launch covers the picker
     * overwrote [pendingWidgetId]: the first flow's data-less cancel then
     * resolved to the *second* flow's ID and deleted it out from under its
     * still-open configure activity, while the first bound ID leaked until
     * the next cold-start orphan sweep.
     */
    val isAddInFlight: Boolean
        get() = pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    /** A widget ID was allocated and its bind (direct or via dialog) begins. */
    fun onBindStarted(appWidgetId: Int) {
        pendingWidgetId = appWidgetId
    }

    /**
     * Re-seeds [pendingWidgetId] after the host activity is recreated (rotation,
     * theme change, process death) mid-flight. The bind/configure result is
     * re-delivered to the new activity instance, but a freshly-constructed
     * [WidgetAddFlow] has lost the in-flight ID — without restoring it the
     * configure-result fallback can't recover the ID when the result intent
     * omits `EXTRA_APPWIDGET_ID`, and a startup orphan sweep would mistake the
     * still-pending allocation for a leak and delete it. Restored from the
     * activity's saved instance state; a no-op for [AppWidgetManager.INVALID_APPWIDGET_ID].
     */
    fun restorePendingWidgetId(appWidgetId: Int) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            pendingWidgetId = appWidgetId
        }
    }

    /** `bindAppWidgetIdIfAllowed` succeeded without the system dialog. */
    fun onBindAllowed(appWidgetId: Int) {
        configureOrAdd(appWidgetId)
    }

    /** The system bind-permission dialog could not be launched. */
    fun onBindLaunchFailed() {
        val appWidgetId = pendingWidgetId
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            deleteWidget(appWidgetId)
        }
    }

    /** The system bind-permission dialog returned. */
    fun onBindResult(success: Boolean, resultWidgetId: Int?) {
        val appWidgetId = resolveResultWidgetId(resultWidgetId)
        if (success && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            configureOrAdd(appWidgetId)
        } else {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                deleteWidget(appWidgetId)
            }
        }
    }

    /** The provider's configure activity returned. */
    fun onConfigureResult(success: Boolean, resultWidgetId: Int?) {
        val appWidgetId = resolveResultWidgetId(resultWidgetId)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            return
        }
        if (success) {
            addWidget(appWidgetId)
        } else {
            deleteWidget(appWidgetId)
        }
    }

    private fun configureOrAdd(appWidgetId: Int) {
        // Set before launching so the configure result can recover the ID
        // even when the result intent doesn't carry the extra back.
        pendingWidgetId = appWidgetId
        when (launchConfigure(appWidgetId)) {
            ConfigureLaunch.Launched -> Unit
            ConfigureLaunch.NotNeeded -> {
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                addWidget(appWidgetId)
            }
            ConfigureLaunch.Failed -> {
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                deleteWidget(appWidgetId)
            }
        }
    }

    /**
     * Prefers the ID the result intent carried; falls back to the in-flight
     * ID when the intent is missing entirely or lacks the extra (in which
     * case `getIntExtra`'s default surfaces here as `INVALID_APPWIDGET_ID`).
     */
    private fun resolveResultWidgetId(resultWidgetId: Int?): Int =
        resultWidgetId?.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }
            ?: pendingWidgetId
}
