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
        File(directory, "garbage.png.tmp").writeText("partial")
        val store = IconOverrideStore(context)

        assertNull(store.iconFileFor("anything"))
        assertFalse("tmp filenames should not surface as app ids", store.overriddenAppIds().any { it.contains("tmp") })
    }
}
