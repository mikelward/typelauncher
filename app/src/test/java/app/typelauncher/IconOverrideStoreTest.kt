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

    @Test
    fun overlappingSavesForSameAppDoNotCorruptTheCommittedIcon() {
        // Two saves for the same app and extension overlap — e.g. the user
        // picks a PNG from a slow content:// provider, then reopens the dialog
        // and picks another PNG before the first stream finishes. Each save
        // must stream into its own tmp file; a shared tmp interleaves their
        // bytes and commits an undecodable image. The winner's file must be
        // exactly one of the two inputs, byte-for-byte — never a mix.
        val store = IconOverrideStore(context)
        val appId = "0:com.example/Main"
        // Model the real re-pick: the first save is already in flight (and has
        // created the overrides directory) when the second starts, so seed the
        // directory here. This also keeps the two saves off the first-ever
        // mkdirs path, so neither can throw before streaming and strand the
        // `bothStreaming` latch — the awaits below are still bounded as a
        // belt-and-suspenders against any future regression that does.
        directory.mkdirs()

        val bothStreaming = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        // Emits a distinct payload for the whole save, but parks mid-stream
        // until both saves are provably in their copy loop at once, so a
        // shared tmp would have interleaved by the time either commits.
        fun blockingSource(payload: String) = object : java.io.InputStream() {
            private val bytes = payload.encodeToByteArray()
            private var i = 0
            override fun read(): Int {
                if (i == 1) {
                    bothStreaming.countDown()
                    release.await()
                }
                return if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
            }
        }

        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val a = Thread {
            try {
                store.setIcon(appId, blockingSource("AAAAAAAA"), "png")
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        val b = Thread {
            try {
                store.setIcon(appId, blockingSource("BBBBBBBB"), "png")
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        a.start()
        b.start()
        // Bounded waits so a regression that stops a save from streaming fails
        // the test fast instead of hanging the whole `gradle test` job.
        assertTrue(
            "both saves must reach their streaming park",
            bothStreaming.await(30, java.util.concurrent.TimeUnit.SECONDS),
        )
        release.countDown()
        a.join(30_000)
        b.join(30_000)

        val committed = store.iconFileFor(appId)
        assertNotNull("one save must win and leave a committed override", committed)
        val contents = committed!!.readText()
        assertTrue(
            "committed icon must be one input verbatim, not a byte-interleaved mix (was: $contents)",
            contents == "AAAAAAAA" || contents == "BBBBBBBB",
        )
        // No stray tmp files left behind, and the surviving on-disk override
        // agrees after a process restart.
        assertEquals(
            "no tmp files may survive the overlapping saves",
            emptyList<File>(),
            directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") },
        )
        assertEquals(contents, IconOverrideStore(context).iconFileFor(appId)?.readText())
    }
}
