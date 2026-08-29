package app.typelauncher

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher [MainActivity] and [LauncherViewModel] hop their blocking work
 * onto — the fresh `LauncherApps` query, icon loads, widget-host IPC.
 *
 * Both already take it as a constructor parameter or a field, which is enough
 * for a test that builds them itself. It is not enough for a Robolectric test
 * that launches the real activity: `ActivityScenario` constructs it, and by the
 * time the test holds a reference the startup app load is already running. So
 * the default is read from here, before either object exists.
 *
 * Left at [Dispatchers.IO] in production. A test that launches the activity
 * sets [testOverride] to a dispatcher it controls and clears it afterwards;
 * otherwise the suite runs real background threads against Robolectric's
 * single-threaded main looper, and work outlives the test that started it.
 */
internal object LauncherDispatchers {
    @VisibleForTesting
    internal var testOverride: CoroutineDispatcher? = null

    val io: CoroutineDispatcher get() = testOverride ?: Dispatchers.IO
}
