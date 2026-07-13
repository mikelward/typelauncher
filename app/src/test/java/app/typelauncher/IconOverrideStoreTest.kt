package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IconOverrideStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val directory = File(context.filesDir, "icon_overrides")

    @After
    fun cleanDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun setIconWritesFileAndIconFileForReturnsIt() {
        val store = IconOverrideStore(context)
        val saved = store.setIcon("0:com.example/Main", "PNG_BYTES".byteInputStream(), "png")

        assertTrue("override file should exist", saved.isFile)
        assertEquals("PNG_BYTES", saved.readText())
        assertEquals(saved.absolutePath, store.iconFileFor("0:com.example/Main")?.absolutePath)
    }

    @Test
    fun iconFileForUnknownIdIsNull() {
        assertNull(IconOverrideStore(context).iconFileFor("never-set"))
    }

    @Test
    fun setIconReplacesPreviousOverrideForSameIdEvenAcrossExtensions() {
        val store = IconOverrideStore(context)
        store.setIcon("0:com.example/Main", "old".byteInputStream(), "png")
        val replaced = store.setIcon("0:com.example/Main", "new".byteInputStream(), "svg")

        // The .png variant must be removed; the .svg variant is the only file
        // left behind for that id.
        val matching = directory.listFiles().orEmpty().filter { it.name.startsWith(replaced.nameWithoutExtension) }
        assertEquals(1, matching.size)
        assertEquals("svg", replaced.extension)
        assertEquals("new", replaced.readText())
    }

    @Test
    fun clearRemovesEveryFileForThatId() {
        val store = IconOverrideStore(context)
        store.setIcon("0:com.example/Main", "PNG".byteInputStream(), "png")
        store.clear("0:com.example/Main")
        assertNull(store.iconFileFor("0:com.example/Main"))
    }

    @Test
    fun clearMissingIdIsNoOp() {
        val store = IconOverrideStore(context)
        store.clear("never-set")
        assertNull(store.iconFileFor("never-set"))
    }

    @Test
    fun overriddenAppIdsRoundTripsEveryStoredId() {
        val store = IconOverrideStore(context)
        store.setIcon("0:com.alpha/A", "1".byteInputStream(), "png")
        store.setIcon("0:com.beta/B", "2".byteInputStream(), "svg")

        val ids = store.overriddenAppIds()
        assertEquals(setOf("0:com.alpha/A", "0:com.beta/B"), ids)
    }

    @Test
    fun indexedLookupsStayConsistentAcrossInterleavedMutations() {
        // The store answers `iconFileFor` / `overriddenAppIds` from an
        // in-memory index after the first directory scan (so per-keystroke
        // lookups don't hit the disk); every mutation after the seed must
        // keep that index in lockstep with the directory.
        val store = IconOverrideStore(context)
        store.setIcon("0:com.alpha/A", "1".byteInputStream(), "png")
        assertNotNull(store.iconFileFor("0:com.alpha/A"))

        store.setIcon("0:com.beta/B", "2".byteInputStream(), "svg")
        store.clear("0:com.alpha/A")

        assertNull(store.iconFileFor("0:com.alpha/A"))
        assertEquals(setOf("0:com.beta/B"), store.overriddenAppIds())
        assertEquals("2", store.iconFileFor("0:com.beta/B")!!.readText())
    }

    @Test
    fun iconVersionForMatchesFileTimestampAfterSetIcon() {
        // The cached version is what busts the AppIconLoader cache; it must
        // equal the override file's real `lastModified()` so a re-upload that
        // changes the file changes the version. `markVisibility` reads this
        // from the index instead of re-stat'ing on the main thread.
        val store = IconOverrideStore(context)
        val saved = store.setIcon("0:com.example/Main", "PNG_BYTES".byteInputStream(), "png")

        assertEquals(saved.lastModified(), store.iconVersionFor("0:com.example/Main"))
    }

    @Test
    fun iconVersionForUnknownIdIsZero() {
        assertEquals(0L, IconOverrideStore(context).iconVersionFor("never-set"))
    }

    @Test
    fun clearResetsIconVersionToZero() {
        val store = IconOverrideStore(context)
        store.setIcon("0:com.example/Main", "PNG".byteInputStream(), "png")
        store.clear("0:com.example/Main")
        assertEquals(0L, store.iconVersionFor("0:com.example/Main"))
    }

    @Test
    fun iconVersionForSurvivesProcessRestartFromDiskScan() {
        // A cold start rebuilds the index from the directory listing; the
        // version must be re-seeded from each file's on-disk timestamp so the
        // reloaded value still matches the file.
        val saved = IconOverrideStore(context).setIcon("0:com.example/Main", "BYTES".byteInputStream(), "png")
        val reloaded = IconOverrideStore(context)
        assertEquals(saved.lastModified(), reloaded.iconVersionFor("0:com.example/Main"))
    }

    @Test
    fun iconVersionForTracksReupload() {
        // Re-uploading a new icon for the same id commits a fresh file; the
        // cached version must follow that file's timestamp, not stay pinned to
        // the first upload's — otherwise the icon cache wouldn't bust.
        val store = IconOverrideStore(context)
        val appId = "0:com.example/Main"
        store.setIcon(appId, "old".byteInputStream(), "png")

        val replaced = store.setIcon(appId, "new".byteInputStream(), "svg")
        // Read straight from the index, the version reflects the freshly-
        // committed file (its on-disk timestamp, untouched since the commit).
        assertEquals(replaced.lastModified(), store.iconVersionFor(appId))
        assertEquals(replaced.absolutePath, store.iconFileFor(appId)?.absolutePath)
    }

    @Test
    fun setIconSurvivesProcessRestart() {
        IconOverrideStore(context).setIcon("0:com.example/Main", "BYTES".byteInputStream(), "png")
        val reloaded = IconOverrideStore(context).iconFileFor("0:com.example/Main")
        assertNotNull(reloaded)
        assertEquals("BYTES", reloaded!!.readText())
    }

    @Test
    fun temporaryWritesAreSwept() {
        // Simulate a half-written upload from a crashed prior run: a stray .tmp
        // file in the directory must not be returned by `iconFileFor` and must
        // not appear in `overriddenAppIds`.
        directory.mkdirs()
        val stray = File(directory, "garbage.png.tmp")
        stray.writeText("partial")
        val store = IconOverrideStore(context)

        assertNull(store.iconFileFor("anything"))
        assertFalse("tmp filenames should not surface as app ids", store.overriddenAppIds().any { it.contains("tmp") })
        // Nothing ever references the orphan again (a retried save writes its
        // own tmp), so the scan deletes it rather than leaving it to
        // accumulate across crashes for the install's lifetime.
        assertFalse("orphaned tmp files should be deleted by the scan", stray.exists())
    }

    @Test
    fun resetDuringInFlightSaveWins() {
        // The user picks a new icon (the save streams on the IO dispatcher)
        // and taps "Reset icon" while the copy is still in flight. The reset
        // must win: the save fails cleanly instead of committing a file the
        // store's index no longer knows about — which used to resurrect the
        // cleared override on the next cold start.
        val store = IconOverrideStore(context)
        val appId = "0:com.example/Main"
        store.setIcon(appId, "old".byteInputStream(), "png")

        val sourceStarted = java.util.concurrent.CountDownLatch(1)
        val resetDone = java.util.concurrent.CountDownLatch(1)
        // Blocks mid-copy until the main thread has cleared the override,
        // deterministically interleaving the reset before the save's commit.
        val blockedSource = object : java.io.InputStream() {
            private var emitted = false
            override fun read(): Int {
                if (!emitted) {
                    emitted = true
                    sourceStarted.countDown()
                    resetDone.await()
                    return 'x'.code
                }
                return -1
            }
        }
        var saveError: Throwable? = null
        val saver = Thread {
            try {
                store.setIcon(appId, blockedSource, "svg")
            } catch (t: Throwable) {
                saveError = t
            }
        }
        saver.start()
        sourceStarted.await()
        store.clear(appId)
        resetDone.countDown()
        saver.join()

        assertNotNull("the interrupted save must fail rather than commit", saveError)
        assertNull("the reset must win over the in-flight save", store.iconFileFor(appId))
        assertEquals(
            "no override file may survive for the cleared id",
            emptyList<File>(),
            directory.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") },
        )
        // A process restart must agree: nothing on disk to resurrect.
        assertNull(IconOverrideStore(context).iconFileFor(appId))
    }
}
