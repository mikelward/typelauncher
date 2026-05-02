package app.typelauncher

import android.content.Intent
import android.os.Process
import android.os.UserHandle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FilterNotifyingTest {
    private val personalUser: UserHandle = Process.myUserHandle()

    private fun fakeApp(name: String, packageName: String, user: UserHandle = personalUser): InstalledApp =
        InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent(),
            user = user,
            isWorkApp = user != personalUser,
            launchWithLauncherApps = false,
        )

    private fun notifyingKey(app: InstalledApp): String = "${app.user.hashCode()}:${app.packageName}"

    @Test
    fun emptyPackageMapReturnsEmptyList() {
        val apps = listOf(fakeApp("Mail", "com.example.mail"))

        assertEquals(emptyList<InstalledApp>(), apps.filterNotifying(emptyMap()))
    }

    @Test
    fun returnsOnlyAppsWhosePackageAndUserAreInTheNotifyingMap() {
        val mail = fakeApp("Mail", "com.example.mail")
        val chat = fakeApp("Chat", "com.example.chat")
        val games = fakeApp("Games", "com.example.games")
        val apps = listOf(mail, chat, games)

        val result = apps.filterNotifying(
            mapOf(
                notifyingKey(mail) to 100L,
                notifyingKey(games) to 200L,
            ),
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
                notifyingKey(older) to 100L,
                notifyingKey(middle) to 200L,
                notifyingKey(newest) to 300L,
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
                notifyingKey(zebra) to 100L,
                notifyingKey(apple) to 100L,
                notifyingKey(mango) to 100L,
            ),
        )

        assertEquals(listOf(apple, mango, zebra), result)
    }

    @Test
    fun workProfileEntryDoesNotMatchPersonalAppForSamePackage() {
        val personalApp = fakeApp("Mail", "com.example.mail", user = personalUser)
        // Simulate a work-profile UserHandle by using a different hashCode.
        // We verify that an entry keyed to a *different* user's hashCode does
        // not match the personalApp whose key uses personalUser.hashCode().
        val workUserHashCode = personalUser.hashCode() + 10
        val workKey = "$workUserHashCode:com.example.mail"

        val result = listOf(personalApp).filterNotifying(mapOf(workKey to 100L))

        assertEquals(emptyList<InstalledApp>(), result)
    }
}
