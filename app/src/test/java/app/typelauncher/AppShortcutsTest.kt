package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Apps' own shortcuts at the top of their long-press menus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val personalUser: UserHandle = Process.myUserHandle()
    private val workUser: UserHandle = UserHandle.getUserHandleForUid(10 * 100_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun clearLaunches() {
        RecordingShortcutLauncherApps.launches.clear()
    }

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata", "hidden_apps")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun theMenuShowsStaticShortcutsFirstThenDynamicOnesInRankOrder() {
        val shown = menuShortcuts(
            listOf(
                shortcut(MAIL, "recent-2", "Alex", rank = 1),
                shortcut(MAIL, "compose", "Compose", rank = 1, manifest = true),
                shortcut(MAIL, "recent-1", "Sam", rank = 0),
                shortcut(MAIL, "search", "Search", rank = 0, manifest = true),
            ),
            personalUser,
        ).getValue(AppShortcutKey(MAIL, personalUser))

        assertEquals(listOf("Search", "Compose", "Sam", "Alex"), shown.map { it.label })
    }

    @Test
    fun theMenuShowsAtMostFourShortcutsPerApp() {
        val shown = menuShortcutsFor(
            appNamed(MAIL),
            menuShortcuts(
                (0 until 6).map { index -> shortcut(MAIL, "s$index", "Shortcut $index", rank = index) },
                personalUser,
            ),
        )

        assertEquals(MAX_MENU_SHORTCUTS, shown.size)
        assertEquals("Shortcut 0", shown.first().label)
    }

    @Test
    fun fourStaticShortcutsStillLeaveRoomForTwoDynamicOnes() {
        // Launcher3's rule: contextual (dynamic) shortcuts replace the
        // lowest-ranked static ones, up to two of them.
        val shown = menuShortcutsFor(
            appNamed(MAIL),
            menuShortcuts(
                (0 until 5).map { index -> shortcut(MAIL, "s$index", "Static $index", rank = index, manifest = true) } +
                    (0 until 3).map { index -> shortcut(MAIL, "d$index", "Dynamic $index", rank = index) },
                personalUser,
            ),
        )

        assertEquals(listOf("Static 0", "Static 1", "Dynamic 0", "Dynamic 1"), shown.map { it.label })
    }

    @Test
    fun eachIconOfAnAppOffersOnlyItsOwnShortcuts() {
        // One package, two launcher icons: a shortcut belongs to one of them.
        val dialer = ComponentName(MAIL, "$MAIL.LaunchActivity")
        val contacts = ComponentName(MAIL, "$MAIL.ContactsActivity")
        val byPackage = menuShortcuts(
            listOf(
                shortcut(MAIL, "call", "Call voicemail", activity = dialer),
                shortcut(MAIL, "add", "New contact", activity = contacts),
            ),
            personalUser,
        )

        assertEquals(listOf("Call voicemail"), menuShortcutsFor(appNamed(MAIL), byPackage).map { it.label })
        val contactsIcon = appNamed(MAIL).copy(launchIntent = Intent.makeMainActivity(contacts))
        assertEquals(listOf("New contact"), menuShortcutsFor(contactsIcon, byPackage).map { it.label })
    }

    @Test
    fun eachAppGetsItsOwnShortcutsAndUnlabeledOnesAreLeftOut() {
        val byApp = menuShortcuts(
            listOf(
                shortcut(MAIL, "compose", "Compose"),
                shortcut(BROWSER, "incognito", "New incognito tab"),
                shortcut(BROWSER, "blank", " "),
            ),
            personalUser,
        )

        assertEquals(listOf("Compose"), byApp.getValue(AppShortcutKey(MAIL, personalUser)).map { it.label })
        assertEquals(
            listOf("New incognito tab"),
            byApp.getValue(AppShortcutKey(BROWSER, personalUser)).map { it.label },
        )
    }

    @Test
    fun aDisabledShortcutIsLeftOut() {
        val byApp = menuShortcuts(
            listOf(
                shortcut(MAIL, "compose", "Compose", disabled = true),
                shortcut(MAIL, "inbox", "Inbox", rank = 1),
            ),
            personalUser,
        )

        assertEquals(listOf("Inbox"), byApp.getValue(AppShortcutKey(MAIL, personalUser)).map { it.label })
    }

    @Test
    fun theCacheReadsEveryProfileAndShowsNoneForAPausedOne() {
        val reads = mutableListOf<Pair<UserHandle, String?>>()
        val cache = AppShortcutCache(scope, Dispatchers.Unconfined) { user, packageName ->
            reads += user to packageName
            if (user == personalUser) listOf(shortcut(MAIL, "compose", "Compose")) else listOf(shortcut(MAIL, "w", "Work"))
        }

        cache.refreshAll(mapOf(personalUser to false, workUser to true))

        assertEquals(listOf<Pair<UserHandle, String?>>(personalUser to null), reads)
        assertEquals(setOf(AppShortcutKey(MAIL, personalUser)), cache.shortcuts.value.keys)
    }

    @Test
    fun aFailedReadKeepsTheShortcutsTheCacheHad() {
        var failing = false
        val cache = AppShortcutCache(scope, Dispatchers.Unconfined) { _, _ ->
            if (failing) throw IllegalStateException("binder failure")
            listOf(shortcut(MAIL, "compose", "Compose"))
        }
        cache.refreshAll(mapOf(personalUser to false))

        failing = true
        cache.refreshAll(mapOf(personalUser to false))
        cache.refreshPackage(MAIL, personalUser)

        assertEquals(
            listOf("Compose"),
            cache.shortcuts.value.getValue(AppShortcutKey(MAIL, personalUser)).map { it.label },
        )
    }

    @Test
    fun aRepublishRereadsOnlyThatApp() {
        var mailShortcuts = listOf(shortcut(MAIL, "compose", "Compose"))
        val reads = mutableListOf<String?>()
        val cache = AppShortcutCache(scope, Dispatchers.Unconfined) { _, packageName ->
            reads += packageName
            when (packageName) {
                MAIL -> mailShortcuts
                else -> mailShortcuts + shortcut(BROWSER, "incognito", "New incognito tab")
            }
        }
        cache.refreshAll(mapOf(personalUser to false))

        mailShortcuts = listOf(shortcut(MAIL, "compose", "Compose"), shortcut(MAIL, "inbox", "Inbox", rank = 1))
        cache.refreshPackage(MAIL, personalUser)

        assertEquals(listOf(null, MAIL), reads)
        assertEquals(
            listOf("Compose", "Inbox"),
            cache.shortcuts.value.getValue(AppShortcutKey(MAIL, personalUser)).map { it.label },
        )
        assertTrue(AppShortcutKey(BROWSER, personalUser) in cache.shortcuts.value)

        mailShortcuts = emptyList()
        cache.refreshPackage(MAIL, personalUser)
        assertFalse("an app that withdrew them all shows none", AppShortcutKey(MAIL, personalUser) in cache.shortcuts.value)
    }

    @Test
    fun aProfileThatIsGoneDropsItsShortcuts() {
        val cache = AppShortcutCache(scope, Dispatchers.Unconfined) { _, _ -> listOf(shortcut(MAIL, "compose", "Compose")) }
        cache.refreshAll(mapOf(personalUser to false, workUser to false))
        assertTrue(AppShortcutKey(MAIL, workUser) in cache.shortcuts.value)

        cache.refreshAll(mapOf(personalUser to false))

        assertFalse(AppShortcutKey(MAIL, workUser) in cache.shortcuts.value)
    }

    @Test
    fun theLauncherLoadsEachAppsShortcutsWithItsList() {
        seedApp("Mail", MAIL)
        val viewModel = viewModel { user, _ ->
            if (user == personalUser) listOf(shortcut(MAIL, "compose", "Compose")) else emptyList()
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            listOf("Compose"),
            viewModel.appShortcutMenu.shortcuts.value[AppShortcutKey(MAIL, personalUser)]?.map { it.label },
        )
    }

    @Test
    fun aNewLanguageRereadsTheShortcutsLabels() {
        // Static shortcut labels are resolved when read, in the language of
        // the moment; the view model outlives the activity a change recreates.
        seedApp("Mail", MAIL)
        var reads = 0
        val viewModel = viewModel { _, _ -> reads++; emptyList() }
        shadowOf(Looper.getMainLooper()).idle()
        val english = android.content.res.Configuration().apply { setLocales(android.os.LocaleList.forLanguageTags("en-US")) }
        val french = android.content.res.Configuration().apply { setLocales(android.os.LocaleList.forLanguageTags("fr-FR")) }
        viewModel.onActivityConfiguration(english)
        val afterLoad = reads

        viewModel.onActivityConfiguration(english)
        assertEquals("the same language rereads nothing", afterLoad, reads)
        viewModel.onActivityConfiguration(french)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("a new language rereads them", reads > afterLoad)
    }

    @Test
    fun aShortcutIconIsRedrawnAfterAConfigurationChange() = kotlinx.coroutines.test.runTest {
        // A -night, -land or -ldrtl icon resource only takes effect if the
        // bitmap drawn under the old configuration is dropped.
        var draws = 0
        val loader = AppShortcutIconLoader(Dispatchers.Unconfined) {
            draws++
            android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)
        }
        val shortcut = menuShortcuts(listOf(shortcut(MAIL, "compose", "Compose")), personalUser)
            .getValue(AppShortcutKey(MAIL, personalUser)).single()

        loader.load(shortcut, 48)
        loader.load(shortcut, 48)
        assertEquals("cached while nothing changes", 1, draws)
        loader.clear()
        loader.load(shortcut, 48)

        assertEquals(2, draws)
    }

    @Test
    @Config(shadows = [RecordingShortcutLauncherApps::class])
    fun tappingAShortcutOpensItAndCountsAsUsingTheApp() {
        seedApp("Mail", MAIL)
        val viewModel = viewModel { _, _ -> emptyList() }
        shadowOf(Looper.getMainLooper()).idle()
        val mail = viewModel.uiState.value.filteredApps.single { it.packageName == MAIL }
        viewModel.setQuery("ma")

        viewModel.launchAppShortcut(mail, AppShortcut("compose", MAIL, personalUser, "Compose"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Triple(MAIL, "compose", personalUser)), RecordingShortcutLauncherApps.launches)
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.recentApps.any { it.packageName == MAIL })
    }

    private fun viewModel(query: (UserHandle, String?) -> List<ShortcutInfo>) = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
        queryShortcuts = { _, _ -> emptyList() },
        queryAppShortcuts = { _, user, packageName -> query(user, packageName) },
    )

    private fun appNamed(packageName: String) = InstalledApp(
        name = packageName,
        packageName = packageName,
        launchIntent = Intent.makeMainActivity(ComponentName(packageName, "$packageName.LaunchActivity")),
        user = personalUser,
        isWorkApp = false,
        launchWithLauncherApps = true,
    )

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = android.content.pm.ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo,
        )
    }

    /**
     * A shortcut published by [packageName]. Manifest and disabled are
     * system-owned flags with no public setter, so they're set the way the
     * system sets them.
     */
    private fun shortcut(
        packageName: String,
        id: String,
        label: String,
        rank: Int = 0,
        manifest: Boolean = false,
        disabled: Boolean = false,
        activity: ComponentName? = null,
    ): ShortcutInfo {
        val publisher = object : ContextWrapper(context) {
            override fun getPackageName(): String = packageName
        }
        val builder = ShortcutInfo.Builder(publisher, id)
            .setShortLabel(label)
            .setRank(rank)
            .setIntent(Intent(Intent.ACTION_VIEW))
        if (activity != null) builder.setActivity(activity)
        val info = builder.build()
        var flags = if (manifest) FLAG_MANIFEST else FLAG_DYNAMIC
        if (disabled) flags = flags or FLAG_DISABLED
        ReflectionHelpers.callInstanceMethod<Unit>(info, "addFlags", ClassParameter.from(Int::class.javaPrimitiveType, flags))
        return info
    }

    private companion object {
        const val MAIL = "com.example.mail"
        const val BROWSER = "com.example.browser"

        // ShortcutInfo.FLAG_DYNAMIC / FLAG_MANIFEST / FLAG_DISABLED, all @hide.
        const val FLAG_DYNAMIC = 1
        const val FLAG_MANIFEST = 1 shl 5
        const val FLAG_DISABLED = 1 shl 6
    }
}

/** Records shortcut launches, which Robolectric's own LauncherApps shadow doesn't support. */
@org.robolectric.annotation.Implements(LauncherApps::class)
class RecordingShortcutLauncherApps : org.robolectric.shadows.ShadowLauncherApps() {
    @org.robolectric.annotation.Implementation
    override fun startShortcut(
        packageName: String,
        shortcutId: String,
        sourceBounds: android.graphics.Rect?,
        startActivityOptions: android.os.Bundle?,
        user: UserHandle,
    ) {
        launches += Triple(packageName, shortcutId, user)
    }

    companion object {
        val launches = mutableListOf<Triple<String, String, UserHandle>>()
    }
}
