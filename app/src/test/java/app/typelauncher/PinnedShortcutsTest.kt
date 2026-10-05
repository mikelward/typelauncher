package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Pinned shortcuts — a browser's "Add to Home screen", or a PWA installed
 * without a WebAPK — as app-list entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedShortcutsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val personalUser: UserHandle = Process.myUserHandle()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata", "hidden_apps")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun aPinnedShortcutBecomesAnAppEntryKeyedByItsShortcut() {
        val entry = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Example Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()

        assertEquals("Example Docs", entry.name)
        assertEquals(BROWSER, entry.packageName)
        assertEquals("page-1", entry.shortcutId)
        assertTrue(entry.isShortcut)
        assertTrue("Remove is always offered for a shortcut", entry.isUninstallable)
        assertEquals(pinnedShortcutEntryId(personalUser, BROWSER, "page-1"), entry.id)
    }

    @Test
    fun aPublisherChosenIdCantBreakOutOfTheEntryId() {
        // The stores keep ids newline-separated; a raw newline in a shortcut
        // id would split one entry into two, the second one forged.
        val id = pinnedShortcutEntryId(personalUser, BROWSER, "page\n0:com.example.bank/.Main")

        assertFalse(id.contains('\n'))
        assertTrue(id.startsWith("${personalUser.hashCode()}:shortcut:$BROWSER/"))
    }

    @Test
    fun aPageUrlUsedAsTheShortcutIdNeverAppearsInTheEntryId() {
        // Browsers may use the page URL as the id; app ids reach the dock
        // list a bug report shares, so the id must not carry it, encoded or not.
        val url = "https://example.com/private/page?token=abc"
        val id = pinnedShortcutEntryId(personalUser, BROWSER, url)

        assertFalse(id.contains("example.com"))
        assertFalse(id.contains(android.util.Base64.encodeToString(url.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)))
        assertEquals("stable for the same page", id, pinnedShortcutEntryId(personalUser, BROWSER, url))
        assertFalse("distinct pages stay distinct", id == pinnedShortcutEntryId(personalUser, BROWSER, "$url-2"))
    }

    @Test
    fun aLoneSurrogateIdStaysDistinctFromItsReplacementCharacter() {
        // A charset encoder turns an unpaired surrogate into '?', which would
        // merge two different publisher ids into one entry.
        assertFalse(
            pinnedShortcutEntryId(personalUser, BROWSER, "\uD800") ==
                pinnedShortcutEntryId(personalUser, BROWSER, "?"),
        )
    }

    @Test
    fun aPublisherUpdateEvictsItsPagesIcons() {
        val page = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        AppIconLoader.put(page.iconCacheId, 4, bitmap.asImageBitmap())

        AppIconLoader.evict(BROWSER, personalUser)

        assertNull(AppIconLoader.cached(page.iconCacheId, 4))
    }

    @Test
    fun pageIconsAreRecognizedSoTheyStayOutOfTheBackedUpSnapshot() {
        val page = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()

        assertTrue(isPinnedShortcutCacheId(page.iconCacheId))
        assertFalse(isPinnedShortcutCacheId(appNamed("com.example.mail").iconCacheId))
    }

    @Test
    fun twoPagesFromOneBrowserAreDistinctEntries() {
        val entries = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs"), shortcut("page-2", "Mail")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        )

        assertEquals(2, entries.map { it.id }.toSet().size)
    }

    @Test
    fun disabledUnpinnedAndUnlabeledShortcutsAreLeftOut() {
        val entries = pinnedShortcutEntries(
            shortcuts = listOf(
                shortcut("disabled", "Gone", disabled = true),
                shortcut("dynamic", "Not pinned", pinned = false),
                shortcut("blank", " "),
                shortcut("kept", "Kept"),
            ),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        )

        assertEquals(listOf("kept"), entries.map { it.shortcutId })
    }

    @Test
    fun aWorkShortcutTakesTheWorkLabel() {
        val entry = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { true },
            isQuietMode = true,
            displayBase = { label, isWork -> if (isWork) "Work $label" else label },
        ).single()

        assertTrue(entry.isWorkApp)
        assertTrue(entry.isQuietMode)
        assertEquals("Work Docs", entry.displayName)
    }

    @Test
    fun onlyAChangeToThePinnedSetWarrantsAReload() {
        val shown = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        )

        assertFalse(
            "a republish of the app's dynamic shortcuts changes nothing shown",
            pinnedShortcutsChanged(
                shown,
                BROWSER,
                personalUser,
                listOf(shortcut("page-1", "Docs"), shortcut("recent", "Recent tab", pinned = false)),
            ),
        )
        assertTrue(
            "a newly pinned page does",
            pinnedShortcutsChanged(
                shown,
                BROWSER,
                personalUser,
                listOf(shortcut("page-1", "Docs"), shortcut("page-2", "Mail")),
            ),
        )
        assertTrue(
            "so does an unpin",
            pinnedShortcutsChanged(shown, BROWSER, personalUser, emptyList()),
        )
        assertTrue(
            "and an update to the page — a relabel or a new icon — which bumps its timestamp",
            pinnedShortcutsChanged(
                shown,
                BROWSER,
                personalUser,
                listOf(shortcut("page-1", "Docs", timestamp = 2L)),
            ),
        )
    }

    @Test
    fun theCallbacksKeyFieldOnlyShortcutsStillRegisterANewPin() {
        // onShortcutsChanged delivers key-field-only ShortcutInfos — no
        // labels. Read through the label-requiring mapper, a first pin for a
        // package compared empty against empty and never reloaded.
        val keyOnly = shortcut("page-1", "Docs").also { info ->
            ReflectionHelpers.setField(info, "mTitle", null)
            ReflectionHelpers.setField(info, "mText", null)
        }

        assertTrue(pinnedShortcutsChanged(emptyList(), BROWSER, personalUser, listOf(keyOnly)))
    }

    @Test
    fun aShortcutKeepsItsPublishersActivityForProfileAwareAppInfo() {
        // App info goes through LauncherApps.startAppDetailsActivity only when
        // the entry has a component; without one it resolves the package in
        // the personal profile, the wrong copy for a work-profile shortcut.
        val activity = ComponentName(BROWSER, "$BROWSER.Main")
        val withActivity = shortcut("page-1", "Docs").also { info ->
            ReflectionHelpers.setField(info, "mActivity", activity)
        }

        val entry = pinnedShortcutEntries(
            shortcuts = listOf(withActivity),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()

        assertEquals(activity, entry.launchIntent.component)
        assertEquals("the id stays the shortcut's", pinnedShortcutEntryId(personalUser, BROWSER, "page-1"), entry.id)
        val restored = AppMetadataStore(context).run {
            save(listOf(entry))
            load().single()
        }
        assertEquals(entry.id, restored.id)
        assertEquals(activity, restored.launchIntent.component)
    }

    @Test
    fun removeUnpinsOnlyTheChosenPage() {
        val pinned = listOf(shortcut("page-1", "Docs"), shortcut("page-2", "Mail"), shortcut("other", "Other"))

        assertEquals(listOf("page-2", "other"), remainingPinnedIds(pinned, BROWSER, "page-1"))
    }

    @Test
    fun removeWritesNothingWhenTheReadDoesNotShowTheShortcut() {
        // pinShortcuts replaces the package's whole pinned set, so writing
        // back a read that came back empty (a failed or partial read) would
        // unpin every page from that browser.
        assertNull(remainingPinnedIds(emptyList(), BROWSER, "page-1"))
        assertNull(remainingPinnedIds(listOf(shortcut("page-2", "Mail")), BROWSER, "page-1"))
    }

    @Test
    fun aFailedReadIsUnknownRatherThanEmpty() {
        assertNull(pinnedShortcutsOrNull { throw IllegalStateException("profile locked") })
    }

    @Test
    fun thePinActivityFinishesAtOnceOnAnIntentWithNoPinRequest() {
        val activity = org.robolectric.Robolectric.buildActivity(PinShortcutActivity::class.java, Intent())
            .create()
            .get()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun repinningAHiddenPageRevealsItInTheRunningLauncher() {
        // A repeat pin of an already-pinned shortcut changes nothing the
        // system reports, so the launcher must unhide the entry itself.
        seedApp("Mail", "com.example.mail")
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            queryShortcuts = { _, user ->
                if (user == personalUser) listOf(shortcut("page-1", "Example Docs")) else emptyList()
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val docs = viewModel.uiState.value.filteredApps.single { it.name == "Example Docs" }
        viewModel.hideApp(docs)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.uiState.value.filteredApps.none { it.name == "Example Docs" })

        PinnedShortcutReveals.reveal(context, pinnedShortcutEntryId(personalUser, BROWSER, "page-1"))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.uiState.value.filteredApps.any { it.name == "Example Docs" })
    }

    @Test
    fun aFailedShortcutReadKeepsThePagesTheListAlreadyHad() {
        seedApp("Mail", "com.example.mail")
        var failing = false
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            queryShortcuts = { _, user ->
                if (failing) throw IllegalStateException("binder failure")
                if (user == personalUser) listOf(shortcut("page-1", "Example Docs")) else emptyList()
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.uiState.value.filteredApps.any { it.name == "Example Docs" })

        failing = true
        viewModel.reloadInstalledAppsForTest()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))

        assertTrue(
            "a transient failure doesn't drop the page",
            viewModel.uiState.value.filteredApps.any { it.name == "Example Docs" },
        )
    }

    @Test
    fun regainingTheHomeRoleReloadsThePinnedShortcuts() {
        // Shortcuts are readable only while this launcher holds the home
        // role, and nothing but a resume notices the role coming back.
        seedApp("Mail", "com.example.mail")
        val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
        val shadowRoles = shadowOf(roleManager) as org.robolectric.shadows.ShadowRoleManager
        var holdsHome = false
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            queryShortcuts = { _, user ->
                if (holdsHome && user == personalUser) listOf(shortcut("page-1", "Example Docs")) else emptyList()
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.refreshPermissionDrivenUi()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.uiState.value.filteredApps.none { it.isShortcut })

        holdsHome = true
        shadowRoles.addHeldRole(android.app.role.RoleManager.ROLE_HOME)
        viewModel.refreshPermissionDrivenUi()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))

        assertTrue(viewModel.uiState.value.filteredApps.any { it.name == "Example Docs" })
    }

    @Test
    fun aDegradedFirstLoadKeepsCachedAppsButNotCachedShortcuts() {
        // Shortcuts depend on the home role, which may have moved since the
        // snapshot was written; a load that read nothing can't vouch for them.
        val cachedPage = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Example Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()
        AppMetadataStore(context).save(listOf(appNamed("com.example.mail"), cachedPage))
        // The PackageManager fallback must come back empty too, or the load
        // isn't the degraded-and-empty one: drop the test app's own entry.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )

        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            enumerateLauncherActivities = { _, _ -> throw android.os.BadParcelableException("truncated") },
            queryShortcuts = { _, _ -> emptyList() },
        )
        shadowOf(Looper.getMainLooper()).idle()

        val apps = viewModel.uiState.value.filteredApps
        assertTrue("the cached app stands", apps.any { it.packageName == "com.example.mail" })
        assertTrue("the cached page does not", apps.none { it.isShortcut })
    }

    @Test
    @Config(shadows = [RecordingAppDetailsLauncherApps::class])
    fun appInfoForAPageWithNoPublisherActivityStaysProfileAware() {
        // The publisher recorded no activity and none resolves in the
        // shortcut's profile: App info still goes through LauncherApps — the
        // profile-aware route — naming the shortcut's own user and package,
        // rather than the personal-profile package URI.
        RecordingAppDetailsLauncherApps.calls.clear()
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            queryShortcuts = { _, _ -> emptyList() },
        )
        shadowOf(Looper.getMainLooper()).idle()
        val page = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()
        assertNull(page.launchIntent.component)

        viewModel.openAppInfo(page)
        shadowOf(Looper.getMainLooper()).idle()

        val (component, user) = RecordingAppDetailsLauncherApps.calls.single()
        assertEquals(BROWSER, component.packageName)
        assertEquals(personalUser, user)
    }

    @Test
    fun repinningAHiddenPageWithNoLauncherRunningUnhidesItOnDisk() {
        val entryId = pinnedShortcutEntryId(personalUser, BROWSER, "page-1")
        HiddenAppStore(context).hide(entryId)

        assertTrue("a successful write reports success", PinnedShortcutReveals.reveal(context, entryId))

        assertFalse(HiddenAppStore(context).contains(entryId))
        assertTrue(
            "revealing a page that was never hidden is a success too",
            PinnedShortcutReveals.reveal(context, entryId),
        )
    }

    @Test
    fun theMenuOffersRemoveRatherThanUninstallForAShortcut() {
        val entry = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()

        assertEquals(R.string.app_menu_remove_shortcut, uninstallActionLabel(entry))
        assertEquals(R.string.app_menu_uninstall, uninstallActionLabel(appNamed("com.example.mail")))
    }

    @Test
    fun aShortcutRoundTripsThroughTheColdStartSnapshot() {
        val entry = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        ).single()
        AppMetadataStore(context).save(listOf(appNamed("com.example.mail"), entry))

        val restored = AppMetadataStore(context).load()

        assertEquals(listOf(appNamed("com.example.mail").id, entry.id), restored.map { it.id })
        val shortcut = restored.single { it.isShortcut }
        assertEquals("page-1", shortcut.shortcutId)
        assertNull("a shortcut has no component", shortcut.launchIntent.component)
    }

    @Test
    fun theFallbackKeepsThePersonalProfilesPinnedShortcuts() {
        val pinned = pinnedShortcutEntries(
            shortcuts = listOf(shortcut("page-1", "Docs")),
            user = personalUser,
            isWorkApp = { false },
            isQuietMode = false,
        )

        val attributed = attributeFallbackApps(
            inventories = mapOf(personalUser to ProfileInventory(pinned, isQuietModeKnown = true)),
            personalUser = personalUser,
            fallbackApps = listOf(appNamed("com.example.mail")),
            isPersonalQuietModeKnown = true,
        )

        assertEquals(
            setOf(appNamed("com.example.mail").id, pinned.single().id),
            attributed.inventories.getValue(personalUser).apps.map { it.id }.toSet(),
        )
    }

    @Test
    fun pinnedShortcutsJoinTheAppListBesideTheApps() {
        // Robolectric can't produce a launcher-activity read, so the apps come
        // from the PackageManager fallback — which a profile read holding only
        // shortcuts must still trigger.
        seedApp("Mail", "com.example.mail")
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            queryShortcuts = { _, user ->
                if (user == personalUser) listOf(shortcut("page-1", "Example Docs")) else emptyList()
            },
        )
        shadowOf(Looper.getMainLooper()).idle()

        val apps = viewModel.uiState.value.filteredApps
        assertTrue("the app read still stands", apps.any { it.name == "Mail" && !it.isShortcut })
        val docs = apps.single { it.name == "Example Docs" }
        assertTrue(docs.isShortcut)
        assertEquals("page-1", docs.shortcutId)
    }

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
     * A shortcut published by [BROWSER]. The pinned and disabled states are
     * system-owned flags with no public setter, so they're set the way the
     * system sets them.
     */
    private fun shortcut(
        id: String,
        label: String,
        pinned: Boolean = true,
        disabled: Boolean = false,
        timestamp: Long = 1L,
    ): ShortcutInfo {
        val browserContext = object : ContextWrapper(context) {
            override fun getPackageName(): String = BROWSER
        }
        val info = ShortcutInfo.Builder(browserContext, id)
            .setShortLabel(label)
            .setIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/$id")))
            .build()
        var flags = 0
        if (pinned) flags = flags or FLAG_PINNED
        if (disabled) flags = flags or FLAG_DISABLED
        ReflectionHelpers.callInstanceMethod<Unit>(info, "addFlags", ClassParameter.from(Int::class.javaPrimitiveType, flags))
        ReflectionHelpers.callInstanceMethod<Unit>(info, "setTimestamp", ClassParameter.from(Long::class.javaPrimitiveType, timestamp))
        return info
    }

    private companion object {
        const val BROWSER = "com.example.browser"

        // ShortcutInfo.FLAG_PINNED / FLAG_DISABLED, both @hide.
        const val FLAG_PINNED = 1 shl 1
        const val FLAG_DISABLED = 1 shl 6
    }
}

/** Records App info requests; Robolectric's own LauncherApps shadow throws for them. */
@org.robolectric.annotation.Implements(LauncherApps::class)
class RecordingAppDetailsLauncherApps : org.robolectric.shadows.ShadowLauncherApps() {
    @org.robolectric.annotation.Implementation
    override fun startAppDetailsActivity(
        component: ComponentName,
        user: UserHandle,
        sourceBounds: android.graphics.Rect?,
        opts: android.os.Bundle?,
    ) {
        calls += component to user
    }

    companion object {
        val calls = mutableListOf<Pair<ComponentName, UserHandle>>()
    }
}
