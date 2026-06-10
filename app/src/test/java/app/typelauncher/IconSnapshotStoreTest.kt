package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IconSnapshotStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.filesDir, "icon_snapshots_v9")
    private val legacyDirectories = listOf(
        File(context.filesDir, "icon_snapshots"),
        File(context.filesDir, "icon_snapshots_v2"),
        File(context.filesDir, "icon_snapshots_v3"),
        File(context.filesDir, "icon_snapshots_v4"),
        File(context.filesDir, "icon_snapshots_v5"),
        File(context.filesDir, "icon_snapshots_v6"),
        File(context.filesDir, "icon_snapshots_v7"),
        File(context.filesDir, "icon_snapshots_v8"),
    )

    @After
    fun cleanDirectory() {
        directory.deleteRecursively()
        legacyDirectories.forEach { it.deleteRecursively() }
    }

    @Test
    fun loadReturnsEmptyWhenNothingSaved() {
        assertEquals(emptyList<IconSnapshotStore.Snapshot>(), IconSnapshotStore(context).load())
    }

    @Test
    fun roundTripsBitmapPixels() {
        val store = IconSnapshotStore(context)
        val bitmap = solidColorBitmap(width = 8, height = 8, color = Color.RED)
        val original = IconSnapshotStore.Snapshot(
            id = "0:com.example/com.example.LaunchActivity",
            sizePx = 8,
            bitmap = bitmap.asImageBitmap(),
        )

        store.save(listOf(original))

        val loaded = IconSnapshotStore(context).load().single()
        assertEquals(original.id, loaded.id)
        assertEquals(original.sizePx, loaded.sizePx)
        val androidBitmap = loaded.bitmap.asAndroidBitmap()
        assertEquals(8, androidBitmap.width)
        assertEquals(8, androidBitmap.height)
        // Spot-check a center pixel matches the source colour. Robolectric's Bitmap shadow
        // preserves raw ARGB_8888 buffers, which is the fast path the production loader
        // relies on.
        assertEquals(Color.RED, androidBitmap.getPixel(4, 4))
    }

    @Test
    fun saveReplacesPreviousSnapshotAndPrunesOrphans() {
        val store = IconSnapshotStore(context)
        val first = IconSnapshotStore.Snapshot(
            id = "0:app.first/.Launch",
            sizePx = 16,
            bitmap = solidColorBitmap(16, 16, Color.GREEN).asImageBitmap(),
        )
        val second = IconSnapshotStore.Snapshot(
            id = "0:app.second/.Launch",
            sizePx = 16,
            bitmap = solidColorBitmap(16, 16, Color.BLUE).asImageBitmap(),
        )
        store.save(listOf(first))
        assertEquals(1, directory.listFiles().orEmpty().size)
        store.save(listOf(second))

        val files = directory.listFiles().orEmpty().map { it.name }
        assertEquals(1, files.size)
        val loaded = IconSnapshotStore(context).load()
        assertEquals(listOf("0:app.second/.Launch"), loaded.map { it.id })
    }

    @Test
    fun emptySaveDeletesAllExistingFiles() {
        val store = IconSnapshotStore(context)
        store.save(
            listOf(
                IconSnapshotStore.Snapshot(
                    id = "0:app.first/.Launch",
                    sizePx = 16,
                    bitmap = solidColorBitmap(16, 16, Color.GREEN).asImageBitmap(),
                ),
            ),
        )
        assertTrue(directory.listFiles().orEmpty().isNotEmpty())

        store.save(emptyList())

        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun corruptFileIsSilentlySkipped() {
        directory.mkdirs()
        File(directory, "this-is-not-a-snapshot.bin").writeBytes(byteArrayOf(0, 1, 2))
        File(directory, "missing-extension").writeBytes(byteArrayOf(0, 1, 2))
        File(directory, "valid_8.bin.tmp").writeBytes(byteArrayOf(0, 1, 2))

        val store = IconSnapshotStore(context)
        // Add one valid snapshot alongside the junk so we can confirm the loader doesn't
        // bail out when one file is bad.
        store.save(
            listOf(
                IconSnapshotStore.Snapshot(
                    id = "0:app.valid/.Launch",
                    sizePx = 16,
                    bitmap = solidColorBitmap(16, 16, Color.MAGENTA).asImageBitmap(),
                ),
            ),
        )

        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("0:app.valid/.Launch", loaded.single().id)
        // Save with the same single snapshot should also clean the leftover .tmp file.
        assertFalse(File(directory, "valid_8.bin.tmp").exists())
    }

    @Test
    fun concurrentSavesLeaveAConsistentSnapshotSet() {
        // Two rapid onStops each launch a save on Dispatchers.IO; without
        // serialization their prune + per-file writes interleave and can leave
        // a half-written or wrongly-pruned set on disk. Hammer the same set from
        // many threads and assert the directory always reloads to exactly that
        // set with intact pixels and no leftover .tmp files.
        val store = IconSnapshotStore(context)
        val snapshots = listOf(
            IconSnapshotStore.Snapshot("0:app.a/.Launch", 16, solidColorBitmap(16, 16, Color.RED).asImageBitmap()),
            IconSnapshotStore.Snapshot("0:app.b/.Launch", 16, solidColorBitmap(16, 16, Color.GREEN).asImageBitmap()),
            IconSnapshotStore.Snapshot("0:app.c/.Launch", 16, solidColorBitmap(16, 16, Color.BLUE).asImageBitmap()),
        )

        val threads = (0 until 8).map {
            Thread { repeat(25) { store.save(snapshots) } }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val loaded = IconSnapshotStore(context).load()
        assertEquals(snapshots.map { it.id }.toSet(), loaded.map { it.id }.toSet())
        loaded.forEach { assertNotNull(it.bitmap) }
        assertTrue(
            "no leftover .tmp files",
            directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun loadPurgesEarlierVersionedDirectoriesAndRestoresNothingFromThem() {
        // Simulate an upgrade from an earlier renderer: every prior snapshot
        // directory (the original unversioned one, `_v2`, and `_v3`) holds a
        // saved bitmap. The new store must not restore any of them (a stale
        // _v3 work bitmap would carry a baked-in badge under the new overlay)
        // and must delete them so they don't linger.
        legacyDirectories.forEach { dir ->
            dir.mkdirs()
            File(dir, "anything_8.bin").writeBytes(ByteArray(8 + 8 * 8 * 4))
        }

        val loaded = IconSnapshotStore(context).load()

        assertEquals(emptyList<IconSnapshotStore.Snapshot>(), loaded)
        legacyDirectories.forEach { assertFalse(it.exists()) }
    }

    @Test
    fun roundTripsIdsContainingFilesystemSensitiveCharacters() {
        // InstalledApp.id includes a colon and a slash via ComponentName.flattenToString,
        // so the on-disk encoding has to keep the round-trip lossless.
        val store = IconSnapshotStore(context)
        val tricky = IconSnapshotStore.Snapshot(
            id = "0:com.example.app/com.example.app.SomeActivity",
            sizePx = 12,
            bitmap = solidColorBitmap(12, 12, Color.YELLOW).asImageBitmap(),
        )

        store.save(listOf(tricky))
        val loaded = IconSnapshotStore(context).load().single()
        assertEquals(tricky.id, loaded.id)
        assertEquals(tricky.sizePx, loaded.sizePx)
        assertNotNull(loaded.bitmap)
    }

    private fun solidColorBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }
}
