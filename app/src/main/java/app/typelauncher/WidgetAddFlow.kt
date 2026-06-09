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

    /** A widget ID was allocated and its bind (direct or via dialog) begins. */
    fun onBindStarted(appWidgetId: Int) {
        pendingWidgetId = appWidgetId
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
