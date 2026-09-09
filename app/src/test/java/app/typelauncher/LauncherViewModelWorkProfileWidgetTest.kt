package app.typelauncher

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When a managed (work) profile becomes available again, the platform stops
 * painting its "Couldn't add widget" placeholder inside work-profile host views,
 * but the stale placeholder isn't refreshed until the host view re-attaches.
 * The ViewModel bumps `workProfileWidgetRefreshToken` on
 * `ACTION_MANAGED_PROFILE_AVAILABLE` so work-profile widget cards recreate their
 * host view and re-fetch RemoteViews — and must not bump on the unavailable
 * broadcast, which would needlessly recreate views as the profile pauses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelWorkProfileWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun managedProfileAvailableBumpsRefreshToken() {
        val viewModel = newViewModel()
        idle()

        val before = viewModel.uiState.value.workProfileWidgetRefreshToken
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        idle()

        assertEquals(before + 1, viewModel.uiState.value.workProfileWidgetRefreshToken)
    }

    @Test
    fun managedProfileUnlockedBumpsRefreshToken() {
        // A locked work profile resumes via the credential-unlock broadcast, not
        // AVAILABLE, so the unlock path must refresh work widgets too.
        val viewModel = newViewModel()
        idle()

        val before = viewModel.uiState.value.workProfileWidgetRefreshToken
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_UNLOCKED))
        idle()

        assertEquals(before + 1, viewModel.uiState.value.workProfileWidgetRefreshToken)
    }

    @Test
    fun managedProfileUnavailableDoesNotBumpRefreshToken() {
        val viewModel = newViewModel()
        idle()

        val before = viewModel.uiState.value.workProfileWidgetRefreshToken
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE))
        idle()

        assertEquals(before, viewModel.uiState.value.workProfileWidgetRefreshToken)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
