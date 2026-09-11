package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

/**
 * `widgetProfileKind` is the one classification every side of the restore
 * path shares, so what it says when the platform can't answer matters as
 * much as what it says when it can: a transient failure reading a valid
 * work profile must come back *unknown*, never as a private space — the
 * sweep would otherwise read the persisted `WORK` against a certain `OTHER`
 * and release a valid binding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [WidgetProfileKindTest.ScriptedShadowLauncherApps::class])
class WidgetProfileKindTest {
    @Implements(LauncherApps::class)
    class ScriptedShadowLauncherApps : ShadowLauncherApps() {
        @Implementation
        protected fun getLauncherUserInfo(user: UserHandle): LauncherUserInfo? {
            if (user in noInfoFor) return null
            val userType = userTypes[user] ?: throw IllegalStateException("user info unavailable")
            // LauncherUserInfo.Builder is not in the public SDK stubs, but the
            // framework class Robolectric runs against has it.
            val builderClass = Class.forName("android.content.pm.LauncherUserInfo\$Builder")
            val builder = builderClass
                .getConstructor(String::class.java, Int::class.javaPrimitiveType)
                .newInstance(userType, user.hashCode())
            return builderClass.getMethod("build").invoke(builder) as LauncherUserInfo
        }

        companion object {
            val userTypes = mutableMapOf<UserHandle, String>()
            val noInfoFor = mutableSetOf<UserHandle>()
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherApps = context.getSystemService<LauncherApps>()
    private val personal = Process.myUserHandle()
    private val work = UserHandle.getUserHandleForUid(10 * 100_000 + 10_000)
    private val private = UserHandle.getUserHandleForUid(11 * 100_000 + 10_000)

    @Test
    fun classifiesPersonalManagedAndOtherProfiles() {
        ScriptedShadowLauncherApps.userTypes.clear()
        ScriptedShadowLauncherApps.userTypes[work] = UserManager.USER_TYPE_PROFILE_MANAGED
        ScriptedShadowLauncherApps.userTypes[private] = "android.os.usertype.profile.PRIVATE"

        assertEquals(WidgetProfileKind.PERSONAL, launcherApps.widgetProfileKind(personal, personal))
        assertEquals(WidgetProfileKind.WORK, launcherApps.widgetProfileKind(work, personal))
        assertEquals(WidgetProfileKind.OTHER, launcherApps.widgetProfileKind(private, personal))
    }

    @Test
    fun aProfileWhoseInfoCannotBeReadIsUnknownNotOther() {
        ScriptedShadowLauncherApps.userTypes.clear()
        ScriptedShadowLauncherApps.noInfoFor.clear()

        assertNull(launcherApps.isManagedProfile(work))
        assertNull(launcherApps.widgetProfileKind(work, personal))
        // The personal user needs no lookup, so it is never unknown.
        assertEquals(WidgetProfileKind.PERSONAL, launcherApps.widgetProfileKind(personal, personal))
    }

    @Test
    @Config(sdk = [34])
    fun beforeApi35EveryNonPersonalProfileIsNonPersonalNotWork() {
        // No lookup can tell the kinds apart, so the record says exactly
        // that — never a "work" a later device would take as certain.
        ScriptedShadowLauncherApps.userTypes.clear()
        ScriptedShadowLauncherApps.noInfoFor.clear()

        assertEquals(WidgetProfileKind.NON_PERSONAL, launcherApps.widgetProfileKind(work, personal))
        assertEquals(WidgetProfileKind.PERSONAL, launcherApps.widgetProfileKind(personal, personal))
        // The cosmetic side still dresses every non-personal profile as work.
        assertEquals(true, launcherApps.isManagedProfile(work))
    }

    @Test
    fun aProfileWithNoInfoOrNoServiceIsUnknownNotOther() {
        // The lookup can also come back null without throwing (a profile
        // mid-removal), and the service itself can be missing; neither is a
        // "not managed" the sweep may act on.
        ScriptedShadowLauncherApps.userTypes.clear()
        ScriptedShadowLauncherApps.noInfoFor.clear()
        ScriptedShadowLauncherApps.noInfoFor += work

        assertNull(launcherApps.isManagedProfile(work))
        assertNull(launcherApps.widgetProfileKind(work, personal))
        val noService: LauncherApps? = null
        assertNull(noService.isManagedProfile(work))
        assertNull(noService.widgetProfileKind(work, personal))
    }
}
