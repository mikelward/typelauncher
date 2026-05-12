package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockedAppStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun reorderMovesIdToTargetIndex() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")
        store.dock("d")

        store.reorder(fromIndex = 0, toIndex = 2)

        assertEquals(listOf("b", "c", "a", "d"), store.dockedAppIds)
    }

    @Test
    fun reorderSupportsMovingLeft() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")
        store.dock("d")

        store.reorder(fromIndex = 3, toIndex = 1)

        assertEquals(listOf("a", "d", "b", "c"), store.dockedAppIds)
    }

    @Test
    fun reorderClampsTargetIndexIntoRange() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")

        store.reorder(fromIndex = 0, toIndex = 99)

        assertEquals(listOf("b", "c", "a"), store.dockedAppIds)
    }

    @Test
    fun reorderIgnoresOutOfRangeFromIndex() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")

        store.reorder(fromIndex = 5, toIndex = 0)

        assertEquals(listOf("a", "b"), store.dockedAppIds)
    }

    @Test
    fun reorderPersistsAcrossProcessRestart() {
        DockedAppStore(context).apply {
            dock("a")
            dock("b")
            dock("c")
            reorder(fromIndex = 2, toIndex = 0)
        }

        val reloaded = DockedAppStore(context)
        assertEquals(listOf("c", "a", "b"), reloaded.dockedAppIds)
    }

    @Test
    fun dockAssignsRowColumnPositions() {
        val store = DockedAppStore(context)

        store.dock("a", columnCount = 4)
        store.dock("b", columnCount = 4)
        store.dock("c", columnCount = 4)
        store.dock("d", columnCount = 4)
        store.dock("e", columnCount = 4)

        assertEquals(DockPosition(0, 0), store.dockedAppPositions["a"])
        assertEquals(DockPosition(0, 3), store.dockedAppPositions["d"])
        assertEquals(DockPosition(1, 0), store.dockedAppPositions["e"])
    }

    @Test
    fun movePersistsSparseGridPosition() {
        DockedAppStore(context).apply {
            dock("a", columnCount = 4)
            dock("b", columnCount = 4)
            dock("c", columnCount = 4)
            move("a", row = 1, column = 0, columnCount = 4, sortOrder = AppListSortOrder.Usage)
        }

        val reloaded = DockedAppStore(context)
        assertEquals(DockPosition(1, 0), reloaded.dockedAppPositions["a"])
        assertEquals(listOf("b", "c", "a"), reloaded.dockedAppIdsFor(AppListSortOrder.Usage, columnCount = 4))
    }

    @Test
    fun moveSwapsOccupiedGridPosition() {
        val store = DockedAppStore(context)
        store.dock("a", columnCount = 4)
        store.dock("b", columnCount = 4)

        store.move("a", row = 0, column = 1, columnCount = 4, sortOrder = AppListSortOrder.Usage)

        assertEquals(DockPosition(0, 1), store.dockedAppPositions["a"])
        assertEquals(DockPosition(0, 0), store.dockedAppPositions["b"])
    }

    @Test
    fun shouldShowAddButtonHintDefaultsToFalse() {
        assertFalse(DockedAppStore(context).shouldShowAddButtonHint)
    }

    @Test
    fun setShowAddButtonHintPersistsAcrossInstances() {
        DockedAppStore(context).setShowAddButtonHint(true)

        assertTrue(DockedAppStore(context).shouldShowAddButtonHint)
    }

    @Test
    fun dockClearsShowAddButtonHint() {
        val store = DockedAppStore(context)
        store.setShowAddButtonHint(true)

        store.dock("a")

        assertFalse(store.shouldShowAddButtonHint)
        // Survives across instances — the clear was persisted, not just held
        // in-process.
        assertFalse(DockedAppStore(context).shouldShowAddButtonHint)
    }

    @Test
    fun markPrefilledDoesNotAffectShowAddButtonHint() {
        val store = DockedAppStore(context)
        store.setShowAddButtonHint(true)

        store.markPrefilled()

        // The two flags share a SharedPreferences instance but live on
        // independent keys, so latching one must not touch the other.
        assertTrue(store.shouldShowAddButtonHint)
        assertTrue(store.hasBeenPrefilled)
    }

    @Test
    fun dockedAppIdsForReversedSortRanksBottomRightFirst() {
        val store = DockedAppStore(context)
        store.dock("a", columnCount = 4)
        store.dock("b", columnCount = 4)
        store.dock("c", columnCount = 4)
        store.move("a", row = 1, column = 0, columnCount = 4, sortOrder = AppListSortOrder.Usage)

        assertEquals(
            listOf("a", "c", "b"),
            store.dockedAppIdsFor(AppListSortOrder.UsageReversed, columnCount = 4),
        )
    }

    @Test
    fun notificationPullDownBehaviorDefaultsToBarBelow() {
        val store = DockSettingsStore(context)

        assertEquals(NotificationPullDownBehavior.BarBelow, store.notificationPullDownBehavior)
    }

    @Test
    fun notificationPullDownBehaviorMigratesLegacyEnabledToBarBelow() {
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("notifications_enabled", true)
            .commit()

        val store = DockSettingsStore(context)

        assertEquals(NotificationPullDownBehavior.BarBelow, store.notificationPullDownBehavior)
    }

    @Test
    fun notificationPullDownBehaviorMigratesOldLauncherEnumToBarBelow() {
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("notification_pull_down_behavior", "Launcher")
            .commit()

        val store = DockSettingsStore(context)

        assertEquals(NotificationPullDownBehavior.BarBelow, store.notificationPullDownBehavior)
    }

    @Test
    fun notificationPullDownBehaviorPersistsExplicitSelection() {
        DockSettingsStore(context).notificationPullDownBehavior = NotificationPullDownBehavior.None

        val reloaded = DockSettingsStore(context)

        assertEquals(NotificationPullDownBehavior.None, reloaded.notificationPullDownBehavior)
    }

    @Test
    fun themeModeDefaultsToSystem() {
        val store = DockSettingsStore(context)

        assertEquals(ThemeMode.System, store.themeMode)
    }

    @Test
    fun themeModePersistsExplicitSelection() {
        DockSettingsStore(context).themeMode = ThemeMode.Dark

        val reloaded = DockSettingsStore(context)

        assertEquals(ThemeMode.Dark, reloaded.themeMode)
    }

    @Test
    fun agendaEnabledDefaultsToTrue() {
        val store = DockSettingsStore(context)

        assertEquals(true, store.isAgendaEnabled)
    }

    @Test
    fun agendaEnabledPersistsExplicitSelection() {
        DockSettingsStore(context).isAgendaEnabled = false

        val reloaded = DockSettingsStore(context)

        assertEquals(false, reloaded.isAgendaEnabled)
    }

    @Test
    fun themeModeFallsBackToSystemForUnknownStoredValue() {
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", "Bogus")
            .commit()

        assertEquals(ThemeMode.System, DockSettingsStore(context).themeMode)
    }

    @Test
    fun keyboardReservationDefaultsToZeroWithNullFingerprint() {
        val store = DockSettingsStore(context)

        val reservation = store.keyboardReservation

        assertEquals(0, reservation.bottomPx)
        assertEquals(null, reservation.configFingerprint)
        assertEquals(KeyboardReservationSource.AnimationTarget, reservation.source)
    }

    @Test
    fun keyboardReservationRoundTripsAllFields() {
        val fingerprint = KeyboardReservationConfig(
            orientation = 1,
            screenWidthDp = 411,
            screenHeightDp = 914,
            densityDpi = 420,
            navBottomPx = 132,
        )
        DockSettingsStore(context).keyboardReservation = KeyboardReservation(
            bottomPx = 1234,
            configFingerprint = fingerprint,
            source = KeyboardReservationSource.VisibleIme,
        )

        val reloaded = DockSettingsStore(context).keyboardReservation

        assertEquals(1234, reloaded.bottomPx)
        assertEquals(fingerprint, reloaded.configFingerprint)
        assertEquals(KeyboardReservationSource.VisibleIme, reloaded.source)
    }

    @Test
    fun keyboardReservationLoadsLegacyBottomPxAsWildcardFingerprint() {
        // Pre-fingerprint installs only ever wrote `keyboard_reservation_bottom_px`.
        // The store must surface those as a null fingerprint (treated as a
        // wildcard by `KeyboardReservation.appliesUnder`) so upgrades still
        // benefit from the cached value on the first cold start; the next IME
        // observation overwrites it with a fresh fingerprinted value.
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("keyboard_reservation_bottom_px", 900)
            .commit()

        val reloaded = DockSettingsStore(context).keyboardReservation

        assertEquals(900, reloaded.bottomPx)
        assertEquals(null, reloaded.configFingerprint)
        assertEquals(KeyboardReservationSource.AnimationTarget, reloaded.source)
    }

    @Test
    fun keyboardReservationClearsFingerprintWhenWrittenAsNull() {
        // Round-trip: writing a fingerprinted value then a null-fingerprint
        // value must drop the per-property keys so the next read does not
        // resurrect a stale half-written fingerprint.
        val store = DockSettingsStore(context)
        store.keyboardReservation = KeyboardReservation(
            bottomPx = 900,
            configFingerprint = KeyboardReservationConfig(1, 411, 914, 420, 0),
            source = KeyboardReservationSource.VisibleIme,
        )
        store.keyboardReservation = KeyboardReservation(
            bottomPx = 800,
            configFingerprint = null,
            source = KeyboardReservationSource.AnimationTarget,
        )

        val reloaded = DockSettingsStore(context).keyboardReservation

        assertEquals(800, reloaded.bottomPx)
        assertEquals(null, reloaded.configFingerprint)
        assertEquals(KeyboardReservationSource.AnimationTarget, reloaded.source)
    }
}
