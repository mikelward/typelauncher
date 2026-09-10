package app.typelauncher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppStoreSearchIntentTest {
    @Test
    fun marketIntent_scopesToAppsAndEncodesQuery() {
        val intent = playStoreSearchIntent("photo editor")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("market://search?q=photo%20editor&c=apps", intent.data.toString())
    }

    @Test
    fun webFallback_scopesToAppsAndEncodesQuery() {
        val intent = playStoreSearchWebIntent("photo editor")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "https://play.google.com/store/search?q=photo%20editor&c=apps",
            intent.data.toString(),
        )
    }

    @Test
    fun query_reservedCharactersAreEncodedNotTreatedAsUriSyntax() {
        // An app name with '&' / '?' / '#' must not break the query out of the
        // `q=` parameter, so the whole thing is percent-encoded.
        val intent = playStoreSearchIntent("AT&T? #1")
        assertEquals("market://search?q=AT%26T%3F%20%231&c=apps", intent.data.toString())
    }
}
