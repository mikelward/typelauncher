package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
    private val directory = File(context.filesDir, "icon_snapshots")

    @After
    fun cleanDirectory() {
        directory.deleteRecursively()
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
        val androidBitmap = loaded.bitmap.let { it as androidx.compose.ui.graphics.AndroidImageBitmap }.bitmap
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
