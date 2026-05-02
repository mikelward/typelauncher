package app.typelauncher

import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterNotifyingTest {
    private fun fakeApp(name: String, packageName: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    @Test
    fun emptyPackageMapReturnsEmptyList() {
        val apps = listOf(fakeApp("Mail", "com.example.mail"))

        assertEquals(emptyList<InstalledApp>(), apps.filterNotifying(emptyMap()))
    }

    @Test
    fun returnsOnlyAppsWhosePackagesAreInTheNotifyingMap() {
        val mail = fakeApp("Mail", "com.example.mail")
        val chat = fakeApp("Chat", "com.example.chat")
        val games = fakeApp("Games", "com.example.games")
        val apps = listOf(mail, chat, games)

        val result = apps.filterNotifying(
            mapOf("com.example.mail" to 100L, "com.example.games" to 200L),
        )

        // Oldest first; newest (Games at 200) lands on the right edge of the row.
        assertEquals(listOf(mail, games), result)
    }

    @Test
    fun ordersByMostRecentPostTimeAscendingSoNewestSitsOnTheRight() {
        val older = fakeApp("Older", "com.example.older")
        val middle = fakeApp("Middle", "com.example.middle")
        val newest = fakeApp("Newest", "com.example.newest")
        val apps = listOf(newest, older, middle)

        val result = apps.filterNotifying(
            mapOf(
                "com.example.older" to 100L,
                "com.example.middle" to 200L,
                "com.example.newest" to 300L,
            ),
        )

        assertEquals(listOf(older, middle, newest), result)
    }

    @Test
    fun fallsBackToAlphabeticalWhenPostTimesAreEqual() {
        val zebra = fakeApp("Zebra", "com.example.z")
        val apple = fakeApp("Apple", "com.example.a")
        val mango = fakeApp("Mango", "com.example.m")
        val apps = listOf(zebra, mango, apple)

        val result = apps.filterNotifying(
            mapOf(
                "com.example.z" to 100L,
                "com.example.a" to 100L,
                "com.example.m" to 100L,
            ),
        )

        assertEquals(listOf(apple, mango, zebra), result)
    }
}
