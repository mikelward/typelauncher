package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Decode/cache behavior of [ContactPhotoLoader]: thumbnails decode to the
 * requested square size (center-cropped from non-square sources), land in the
 * cache, degrade to null (the monogram) for photo-less contacts and unreadable
 * blobs, and [ContactPhotoLoader.evictAll] both empties the cache and bumps
 * the compose re-key generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactPhotoLoaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun reset() {
        // The loader is a process-wide singleton; don't leak photos between tests.
        ContactPhotoLoader.evictAll()
    }

    private fun contact(photoUri: String?) = ContactResult(
        contactId = 1,
        lookupKey = "lookup-1",
        displayName = "Maria Lopez",
        photoThumbnailUri = photoUri,
    )

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.RED) }
        return ByteArrayOutputStream()
            .also { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            .toByteArray()
    }

    private fun registerPhoto(uri: String, width: Int, height: Int) {
        shadowOf(context.contentResolver)
            .registerInputStream(Uri.parse(uri), ByteArrayInputStream(pngBytes(width, height)))
    }

    @Test
    fun decodesNonSquarePhotoToRequestedSquareSizeAndCaches() {
        registerPhoto(PHOTO_URI, width = 96, height = 64)
        runBlocking {
            val loaded = ContactPhotoLoader.load(context, contact(PHOTO_URI), sizePx = 40)
            assertNotNull("registered photo must decode", loaded)
            assertEquals(40, loaded!!.width)
            assertEquals(40, loaded.height)
            // Second lookup is a cache hit — same instance, no second decode
            // (the registered stream is single-use, so a re-decode would fail).
            assertSame(loaded, ContactPhotoLoader.cached(contact(PHOTO_URI), sizePx = 40))
            assertSame(loaded, ContactPhotoLoader.load(context, contact(PHOTO_URI), sizePx = 40))
        }
    }

    @Test
    fun photolessContactResolvesToNull() {
        runBlocking {
            assertNull(ContactPhotoLoader.load(context, contact(null), sizePx = 40))
        }
        assertNull(ContactPhotoLoader.cached(contact(null), sizePx = 40))
    }

    @Test
    fun unreadablePhotoUriDegradesToNull() {
        // Nothing registered for the URI: the shadow resolver's stream throws
        // from read(), and the loader must degrade to the monogram, never
        // crash. Robolectric's native BitmapFactory path prints the created
        // exception to the console even though the loader catches it — the
        // stray `Exception in thread "DefaultDispatcher-worker-…"` line in the
        // test output is that print, not an uncaught crash (verified: a
        // default uncaught-exception handler installed around this load never
        // fires).
        runBlocking {
            assertNull(ContactPhotoLoader.load(context, contact(PHOTO_URI), sizePx = 40))
        }
    }

    @Test
    fun evictAllDuringInFlightDecodeWinsOverTheLatePut() {
        // An eviction that lands while a decode is in flight (contact search
        // disabled, index reloaded) must not be undone by the decode's cache
        // put — the disabled-source "holds nothing in memory" contract has no
        // in-flight loophole. The registered stream fires evictAll() from its
        // first read(), deterministically interleaving the eviction between
        // load's generation snapshot and its put.
        val delegate = ByteArrayInputStream(pngBytes(width = 64, height = 64))
        var fired = false
        val evictingStream = object : java.io.InputStream() {
            private fun fireOnce() {
                if (!fired) {
                    fired = true
                    ContactPhotoLoader.evictAll()
                }
            }

            override fun read(): Int {
                fireOnce()
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                fireOnce()
                return delegate.read(b, off, len)
            }
        }
        shadowOf(context.contentResolver)
            .registerInputStream(Uri.parse(PHOTO_URI), evictingStream)

        val loaded = runBlocking { ContactPhotoLoader.load(context, contact(PHOTO_URI), sizePx = 40) }

        assertTrue("eviction must have fired mid-decode", fired)
        // The already-composed row still receives the bitmap this composition…
        assertNotNull(loaded)
        // …but nothing retains it: the post-eviction cache stays empty.
        assertNull(ContactPhotoLoader.cached(contact(PHOTO_URI), sizePx = 40))
    }

    @Test
    fun evictAllDropsCacheAndBumpsGeneration() {
        registerPhoto(PHOTO_URI, width = 64, height = 64)
        runBlocking { ContactPhotoLoader.load(context, contact(PHOTO_URI), sizePx = 40) }
        assertNotNull(ContactPhotoLoader.cached(contact(PHOTO_URI), sizePx = 40))
        val generationBefore = ContactPhotoLoader.cacheGenerationValue

        ContactPhotoLoader.evictAll()

        assertNull(ContactPhotoLoader.cached(contact(PHOTO_URI), sizePx = 40))
        assertEquals(generationBefore + 1, ContactPhotoLoader.cacheGenerationValue)
    }

    private companion object {
        const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
    }
}
