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
    fun dockingAfterClearingTopRowDoesNotDisplaceSurvivors() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c", "d", "e", "f", "g", "h")
            .forEach { id -> store.dock(id, columnCount = 4) }
        // a-d fill row 0, e-h fill row 1. Clearing the top row strands e-h at
        // row 1 in the persisted map though they now render on row 0.
        listOf("a", "b", "c", "d").forEach { id -> store.undock(id) }

        store.dock("i", columnCount = 4)

        val resolved = resolvedDockPositions(store.dockedAppIds, store.dockedAppPositions, columnCount = 4)
        listOf("e", "f", "g", "h").forEach { id ->
            assertEquals(0, resolved.getValue(id).row)
        }
        assertEquals(DockPosition(1, 0), resolved.getValue("i"))
    }

    @Test
    fun moveAfterUndockDoesNotStrandBystanderOnStaleRow() {
        // Regression test for `move` mutating the sparse persisted map with
        // compacted-space coordinates. Seed a sparse map by filling three
        // 2-column rows and undocking the middle row: survivors e and f stay
        // persisted at row 2 while rendering at row 1. Dragging a onto f's
        // rendered slot (1,1) must swap a and f within the compacted space.
        // Before the fix, the swap wrote a=(1,1) and f=(0,0) into the sparse
        // map where e still sat at (2,0) — rows 0, 1, and 2 all occupied, so
        // the row-collapse pass could not repair it and e was persisted alone
        // on row 2 with a hole at (1,0).
        val store = DockedAppStore(context)
        listOf("a", "b", "c", "d", "e", "f")
            .forEach { id -> store.dock(id, columnCount = 2) }
        listOf("c", "d").forEach { id -> store.undock(id) }

        store.move("a", row = 1, column = 1, columnCount = 2, sortOrder = AppListSortOrder.Usage)

        val resolved = resolvedDockPositions(store.dockedAppIds, store.dockedAppPositions, columnCount = 2)
        assertEquals(DockPosition(1, 1), resolved.getValue("a"))
        assertEquals(DockPosition(0, 0), resolved.getValue("f"))
        assertEquals(DockPosition(1, 0), resolved.getValue("e"))
        assertEquals(DockPosition(0, 1), resolved.getValue("b"))
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
    fun concurrentMutationAndReadsStayConsistent() {
        // Regression test for the unsynchronized read-modify-write in dock /
        // undock / move. Before the fix, hammering the store from
        // several threads reliably threw a ConcurrentModificationException out
        // of dockedAppIds.toList() / save()'s joinToString while another thread
        // mutated the LinkedHashSet. The lock makes each operation atomic and
        // each getter return a consistent copy, so no read crashes and no
        // write is lost. (Inherently nondeterministic — the thread and
        // iteration counts are sized to trip the race on every run.)
        //
        // Note: we deliberately do NOT cross-check the two getters against
        // each other. dockedAppIds and dockedAppPositions are independent lock
        // acquisitions, so a mutation landing between them legitimately makes
        // the two snapshots disagree — the lock only guarantees consistency
        // *within* a single getter, never *across* two.
        val store = DockedAppStore(context)
        val ids = (0 until 50).map { index -> "app$index" }
        ids.forEach { id -> store.dock(id, columnCount = 4) }

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map { threadIndex ->
            Thread {
                try {
                    repeat(300) { iteration ->
                        val id = ids[(threadIndex * 31 + iteration) % ids.size]
                        when (iteration % 4) {
                            0 -> store.dock("extra-$threadIndex-$iteration", columnCount = 4)
                            1 -> store.undock(id)
                            2 -> store.move(id, row = iteration % 3, column = iteration % 4, columnCount = 4, sortOrder = AppListSortOrder.Usage)
                            else -> {
                                // Exercise concurrent snapshot reads: each
                                // getter must copy the backing collection under
                                // the lock without throwing while other threads
                                // mutate it. The copies are consumed so the
                                // reads can't be optimized away.
                                check(store.dockedAppIds.size >= 0)
                                check(store.dockedAppPositions.size >= 0)
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }
        }
        threads.forEach { thread -> thread.start() }
        threads.forEach { thread -> thread.join() }

        assertEquals(null, failure.get())
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
    fun dockedAppIdsForReversedSortRanksBottomLeftFirst() {
        val store = DockedAppStore(context)
        store.dock("a", columnCount = 4)
        store.dock("b", columnCount = 4)
        store.dock("c", columnCount = 4)
        store.move("a", row = 1, column = 0, columnCount = 4, sortOrder = AppListSortOrder.Usage)

        // After the move the dock is:
        //   .  b  c  .   (row 0)
        //   a  .  .  .   (row 1)
        // A reversed sort renders the list bottom-up, so the dock's bottom row
        // ranks first and each row reads left-to-right: a (bottom-left) leads,
        // then b, then c. The bottom-left dock icon lands in the list's
        // bottom-left corner when the dock is hidden.
        assertEquals(
            listOf("a", "b", "c"),
            store.dockedAppIdsFor(AppListSortOrder.UsageReversed, columnCount = 4),
        )
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
    fun iconShapeDefaultsToSystem() {
        assertEquals(IconShape.System, DockSettingsStore(context).iconShape)
    }

    @Test
    fun iconShapePersistsExplicitSelection() {
        DockSettingsStore(context).iconShape = IconShape.Squircle

        assertEquals(IconShape.Squircle, DockSettingsStore(context).iconShape)
    }

    @Test
    fun iconShapeFallsBackToSystemForUnknownStoredValue() {
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("icon_shape", "Bogus")
            .commit()

        assertEquals(IconShape.System, DockSettingsStore(context).iconShape)
    }

    @Test
    fun iconThemeDefaultsToDefault() {
        assertEquals(IconTheme.Default, DockSettingsStore(context).iconTheme)
    }

    @Test
    fun iconThemePersistsExplicitSelection() {
        DockSettingsStore(context).iconTheme = IconTheme.Monochrome

        assertEquals(IconTheme.Monochrome, DockSettingsStore(context).iconTheme)
    }

    @Test
    fun iconThemeFallsBackToDefaultForUnknownStoredValue() {
        context.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("icon_theme", "Bogus")
            .commit()

        assertEquals(IconTheme.Default, DockSettingsStore(context).iconTheme)
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
