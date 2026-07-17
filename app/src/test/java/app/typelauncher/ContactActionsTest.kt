package app.typelauncher

import android.accounts.AccountManager
import android.accounts.AuthenticatorDescription
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSubscriptionManager

/**
 * Unit coverage for [resolveContactChannels]: the contact → channels grouping,
 * default-first ordering, phone dedupe, and the account-type attribution that
 * surfaces real app integrations (WhatsApp-shaped rows) while dropping generic
 * data kinds an unrelated app merely declares a handler for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactActionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val contact = ContactResult(contactId = 42, lookupKey = "lookup-42", displayName = "Jess Ward")

    @Test
    fun groupsPhoneRowsIntoCallAndMessageChannels() {
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE, superPrimary = true),
            phone("+1-555-0199", Phone.TYPE_HOME),
        )

        val channels = resolveContactChannels(context, contact)

        assertEquals(listOf("call", "message"), channels.map { it.id })
        val call = channels.first { it.id == "call" }
        assertEquals(ContactChannelGlyph.Call, call.glyph)
        assertNull("Built-in channels carry a glyph, not an app icon", call.iconPackageName)
        assertEquals(
            listOf("+1-555-0100", "+1-555-0199"),
            call.actions.map { (it.kind as ContactActionKind.Call).number },
        )
        assertEquals("Super-primary number sorts to the top", "+1-555-0100", call.actions.first().detail)
        assertTrue(call.actions.first().isDefault)

        val message = channels.first { it.id == "message" }
        val firstSms = message.actions.first().kind as ContactActionKind.Launch
        assertEquals(Intent.ACTION_SENDTO, firstSms.intent.action)
        assertEquals("smsto", firstSms.intent.data?.scheme)
        // schemeSpecificPart, not toString: Uri.fromParts percent-encodes the
        // number in the string form but keeps it intact when decoded.
        assertEquals("+1-555-0100", firstSms.intent.data?.schemeSpecificPart)
    }

    @Test
    fun callChannelUsesTheDefaultDialerIconWhenOneIsSet() {
        installApp("com.android.dialer", "Phone")
        shadowOf(context.getSystemService(TelecomManager::class.java))
            .setDefaultDialer("com.android.dialer")
        registerDataProvider(phone("+1-555-0100", Phone.TYPE_MOBILE))

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals("com.android.dialer", call.iconPackageName)
        // The glyph is retained as the render-time fallback, not dropped.
        assertEquals(ContactChannelGlyph.Call, call.glyph)
    }

    @Test
    fun emailChannelUsesTheDefaultMailHandlerIcon() {
        installApp("com.example.mail", "Mail")
        registerHandler(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null)), "com.example.mail")
        registerDataProvider(email("jess@example.com", Email.TYPE_HOME))

        val emailChannel = resolveContactChannels(context, contact).first { it.id == "email" }

        assertEquals("com.example.mail", emailChannel.iconPackageName)
    }

    @Test
    fun emailChannelFallsBackToGlyphWhenTheHandlerIsTheSystemChooser() {
        // No user default: a package-less mailto resolves to the chooser
        // (package `android`), which is no single app — so the glyph shows.
        registerHandler(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null)), "android")
        registerDataProvider(email("jess@example.com", Email.TYPE_HOME))

        val emailChannel = resolveContactChannels(context, contact).first { it.id == "email" }

        assertNull(emailChannel.iconPackageName)
        assertEquals(ContactChannelGlyph.Email, emailChannel.glyph)
    }

    @Test
    fun sortsPrimaryNumberAheadWhenNoSuperPrimary() {
        registerDataProvider(
            phone("+1-555-0001", Phone.TYPE_WORK),
            phone("+1-555-0002", Phone.TYPE_MOBILE, primary = true),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals("Primary number leads when nothing is super-primary", "+1-555-0002", call.actions.first().detail)
    }

    @Test
    fun sortsLocalCountryNumbersAheadOfForeignOnes() {
        // A US SIM: the +1 number leads even though the resolver returned the
        // Australian number first.
        setDefaultSimCountry("us")
        registerDataProvider(
            phone("+61 3 9876 5432", Phone.TYPE_HOME),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals(listOf("+1-555-0100", "+61 3 9876 5432"), call.actions.map { it.detail })
    }

    @Test
    fun defaultNumberStillLeadsAheadOfALocalCountryMatch() {
        // The user's explicit default beats the country tiebreak.
        setDefaultSimCountry("us")
        registerDataProvider(
            phone("+61 3 9876 5432", Phone.TYPE_HOME, superPrimary = true),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals(listOf("+61 3 9876 5432", "+1-555-0100"), call.actions.map { it.detail })
    }

    @Test
    fun nationalFormatNumberRanksAsLocal() {
        setDefaultSimCountry("us")
        registerDataProvider(
            phone("+44 20 7946 0958", Phone.TYPE_WORK),
            phone("555-0100", Phone.TYPE_MOBILE),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals(listOf("555-0100", "+44 20 7946 0958"), call.actions.map { it.detail })
    }

    @Test
    fun messageChannelSortsByTheSmsSimCountryOnADualSimDevice() {
        // Dual SIM split defaults: calls go out on the US SIM (subscription 1),
        // texts on the AU SIM (subscription 2) — each picker leads with the
        // numbers its own SIM reaches domestically. The per-SIM countries are
        // injected because Robolectric's TelephonyManager shadow keeps one
        // process-global SIM country; the subscription choice itself (voice
        // default for Call, SMS default for Message) is the real code path.
        ShadowSubscriptionManager.setDefaultVoiceSubscriptionId(1)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(2)
        registerDataProvider(
            phone("+61 3 9876 5432", Phone.TYPE_HOME),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val channels = resolveContactChannels(context, contact) { subscriptionId ->
            when (subscriptionId) {
                1 -> "1"
                2 -> "61"
                else -> null
            }
        }

        val call = channels.first { it.id == "call" }
        assertEquals(listOf("+1-555-0100", "+61 3 9876 5432"), call.actions.map { it.detail })
        val message = channels.first { it.id == "message" }
        assertEquals(listOf("+61 3 9876 5432", "+1-555-0100"), message.actions.map { it.detail })
    }

    @Test
    fun brokenTelephonyLeavesNumberOrderAlone() {
        // Devices without FEATURE_TELEPHONY_SUBSCRIPTION (Wi-Fi-only tablets;
        // telephony is optional in the manifest) throw
        // UnsupportedOperationException from the subscription lookups on API
        // 34+; the sheet degrades to resolver order instead of crashing.
        registerDataProvider(
            phone("+61 3 9876 5432", Phone.TYPE_HOME),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val channels = resolveContactChannels(context, contact) {
            throw UnsupportedOperationException("no telephony")
        }

        val call = channels.first { it.id == "call" }
        assertEquals(listOf("+61 3 9876 5432", "+1-555-0100"), call.actions.map { it.detail })
    }

    @Test
    fun unknownSimCountryLeavesNumberOrderAlone() {
        // No default SIM and no SIM country (Robolectric's defaults — the same
        // shape as no SIM at all): the resolver order stands.
        registerDataProvider(
            phone("+61 3 9876 5432", Phone.TYPE_HOME),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals(listOf("+61 3 9876 5432", "+1-555-0100"), call.actions.map { it.detail })
    }

    @Test
    fun dedupesTheSameNumberSyncedAcrossAccounts() {
        // The same number often rides under several accounts (Google + WhatsApp
        // + Signal each carry a phone row); the Call/Message channels list it once.
        registerDataProvider(
            phone("+1 555 0100", Phone.TYPE_MOBILE, account = "com.google", superPrimary = true),
            phone("+15550100", Phone.TYPE_MOBILE, account = "com.whatsapp"),
            phone("+1-555-0100", Phone.TYPE_MOBILE, account = "org.thoughtcrime.securesms"),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals("The number appears once despite three synced rows", 1, call.actions.size)
        assertEquals("+1 555 0100", call.actions.single().detail)
    }

    @Test
    fun keepsNumbersThatDifferOnlyByDialingSyntax() {
        // Same leading digits but a different dial string (an extension / pause):
        // these are distinct numbers and must both survive the dedupe.
        registerDataProvider(
            phone("555-0100", Phone.TYPE_MOBILE, superPrimary = true),
            phone("555-0100,,99", Phone.TYPE_WORK),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals(2, call.actions.size)
        assertEquals(setOf("555-0100", "555-0100,,99"), call.actions.map { it.detail }.toSet())
    }

    @Test
    fun mapsAccountNamespaceToItsAuthenticatorPackage() {
        // An app whose account type is a namespace rather than its APK package:
        // the owning package is recovered from the registered authenticator.
        val accountType = "com.example.messenger.account"
        val pkg = "com.example.messenger"
        val mime = "vnd.android.cursor.item/vnd.com.example.messenger.call"
        shadowOf(AccountManager.get(context)).addAuthenticator(
            AuthenticatorDescription(accountType, pkg, 0, 0, 0, 0),
        )
        registerAppRow(pkg, "Messenger", dataId = 701, mimeType = mime)
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE),
            custom(dataId = 701, mimeType = mime, account = accountType, summary = "Call"),
        )

        val channels = resolveContactChannels(context, contact)

        val app = channels.first { it.id == pkg }
        assertEquals("Messenger", app.label)
        assertEquals(pkg, app.iconPackageName)
        assertEquals(pkg, (app.actions.single().kind as ContactActionKind.Launch).intent.`package`)
    }

    @Test
    fun surfacesInstalledAppActionsByAccountType() {
        val whatsappMessage = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        val whatsappCall = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        registerAppRow("com.whatsapp", "WhatsApp", dataId = 201, mimeType = whatsappMessage)
        registerAppRow("com.whatsapp", "WhatsApp", dataId = 202, mimeType = whatsappCall)
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE, superPrimary = true),
            custom(dataId = 201, mimeType = whatsappMessage, account = "com.whatsapp", summary = "Message"),
            custom(dataId = 202, mimeType = whatsappCall, account = "com.whatsapp", summary = "Voice call"),
        )

        val channels = resolveContactChannels(context, contact)

        val whatsapp = channels.first { it.id == "com.whatsapp" }
        assertEquals("WhatsApp", whatsapp.label)
        assertEquals("com.whatsapp", whatsapp.iconPackageName)
        assertNull("App channels carry an icon package, not a built-in glyph", whatsapp.glyph)
        assertEquals(listOf("Message", "Voice call"), whatsapp.actions.map { it.label })
        val firstAction = whatsapp.actions.first().kind as ContactActionKind.Launch
        assertEquals(Intent.ACTION_VIEW, firstAction.intent.action)
        assertEquals(whatsappMessage, firstAction.intent.type)
        // The intent is targeted at the account's package so it launches the real
        // app instead of the system chooser.
        assertEquals("com.whatsapp", firstAction.intent.`package`)
        // Phone/Message first, the app channel after them.
        assertEquals(listOf("call", "message", "com.whatsapp"), channels.map { it.id })
    }

    @Test
    fun labelsAppActionsFromTheActivityWhenTheSummaryIsBlank() {
        // Meet's audio and video rows leave DATA3 empty, so they'd both fall back
        // to the app name "Meet"; the resolved activity's own label distinguishes
        // them into two usable entries.
        val pkg = "com.google.android.apps.tachyon"
        val audio = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet.audio"
        val video = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet"
        installApp(pkg, "Meet")
        registerAppRow(pkg, "Audio meeting", 801, audio)
        registerAppRow(pkg, "Video meeting", 802, video)
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE),
            custom(801, audio, account = pkg),
            custom(802, video, account = pkg),
        )

        val meet = resolveContactChannels(context, contact).first { it.id == pkg }

        assertEquals("Meet", meet.label)
        assertEquals(listOf("Audio meeting", "Video meeting"), meet.actions.map { it.label })
    }

    @Test
    fun collapsesAppActionsThatRenderTheIdenticalLabel() {
        // When neither DATA3 nor the activity label distinguishes two rows — both
        // just say "Meet" — the indistinguishable entries collapse to one, and the
        // single-action channel then fires on tap.
        val pkg = "com.google.android.apps.tachyon"
        val audio = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet.audio"
        val video = "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet"
        installApp(pkg, "Meet")
        registerAppRow(pkg, "Meet", 801, audio)
        registerAppRow(pkg, "Meet", 802, video)
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE),
            custom(801, audio, account = pkg),
            custom(802, video, account = pkg),
        )

        val meet = resolveContactChannels(context, contact).first { it.id == pkg }

        assertEquals(1, meet.actions.size)
        assertEquals("Meet", meet.actions.single().label)
    }

    @Test
    fun keepsSeparateAppsAsSeparateChannels() {
        // Regression for the shipped bug: WhatsApp, Signal and Meet all resolved
        // to the system chooser (package "android") and collapsed into one
        // channel. Attributing by account type keeps them distinct.
        registerAppRow("com.whatsapp", "WhatsApp", 301, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
        registerAppRow("org.thoughtcrime.securesms", "Signal", 302, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call")
        registerAppRow("com.google.android.apps.tachyon", "Meet", 303, "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet")
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE),
            custom(301, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "com.whatsapp", "Voice call"),
            custom(302, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call", "org.thoughtcrime.securesms", "Signal call"),
            custom(303, "vnd.android.cursor.item/com.google.android.apps.tachyon.phone.meet", "com.google.android.apps.tachyon", "Meet"),
        )

        val ids = resolveContactChannels(context, contact).map { it.id }

        assertEquals(
            listOf("call", "message", "com.whatsapp", "org.thoughtcrime.securesms", "com.google.android.apps.tachyon"),
            ids,
        )
    }

    @Test
    fun dropsGenericDataKindOnAPlainAccount() {
        // The eufyMake case: an unrelated app registers a handler for a generic
        // `calling_card` row that lives on a plain `com.google` account. Because
        // the row is attributed to its account (com.google, not the handler's
        // package), targeting the intent there resolves to nothing and it drops —
        // no junk channel, even though a package-less resolve would have matched.
        registerAppRow("com.oceanwing.fdmprint", "eufyMake", dataId = 401, mimeType = "vnd.android.cursor.item/calling_card")
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE),
            custom(dataId = 401, mimeType = "vnd.android.cursor.item/calling_card", account = "com.google", summary = "eufyMake"),
        )

        val ids = resolveContactChannels(context, contact).map { it.id }

        assertEquals("No junk channel from the generic com.google row", listOf("call", "message"), ids)
    }

    @Test
    fun ordersChannelsPhoneMessageAppsThenEmail() {
        registerAppRow("com.whatsapp", "WhatsApp", dataId = 501, mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile")
        registerDataProvider(
            email("jess@example.com", Email.TYPE_HOME),
            custom(dataId = 501, mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile", account = "com.whatsapp", summary = "Message"),
            phone("+1-555-0100", Phone.TYPE_MOBILE),
        )

        val channels = resolveContactChannels(context, contact)

        assertEquals(listOf("call", "message", "com.whatsapp", "email"), channels.map { it.id })
        val email = channels.first { it.id == "email" }
        val launch = email.actions.single().kind as ContactActionKind.Launch
        assertEquals("mailto", launch.intent.data?.scheme)
        assertEquals("jess@example.com", launch.intent.data?.schemeSpecificPart)
    }

    @Test
    fun nameOnlyContactHasNoChannels() {
        registerDataProvider(
            row(mimeType = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, data1 = "Jess Ward"),
        )

        assertTrue(resolveContactChannels(context, contact).isEmpty())
    }

    @Test
    fun ignoresUnresolvableCustomRows() {
        // A custom mimetype whose app isn't installed resolves to nothing and is
        // dropped rather than surfaced as a dead button.
        registerDataProvider(
            custom(
                dataId = 601,
                mimeType = "vnd.android.cursor.item/vnd.com.uninstalled.chat",
                account = "com.uninstalled",
                summary = "Chat",
            ),
        )

        assertTrue(resolveContactChannels(context, contact).isEmpty())
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Simulates a single-SIM device in [countryIso]: the one subscription is
     * the default for both calls and texts, and the default `TelephonyManager`
     * carries its country (`createForSubscriptionId` is unregistered in the
     * shadow, so the lookup falls back to the default manager — the same SIM).
     */
    private fun setDefaultSimCountry(countryIso: String) {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso(countryIso)
        ShadowSubscriptionManager.setDefaultVoiceSubscriptionId(1)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
    }

    /** Registers a resolver for the exact intent the resolver builds: ACTION_VIEW + data + type, targeted at [packageName]. */
    private fun registerAppRow(packageName: String, label: String, dataId: Long, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId), mimeType)
            .setPackage(packageName)
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.ContactActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    private fun registerDataProvider(vararg rows: Map<String, Any?>) {
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<String>?,
                selection: String?,
                selectionArgs: Array<String>?,
                sortOrder: String?,
            ): Cursor {
                val columns = projection ?: DATA_COLUMNS
                val cursor = MatrixCursor(columns)
                rows.forEach { r -> cursor.addRow(columns.map { r[it] }) }
                return cursor
            }

            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
        }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
    }

    private fun phone(
        number: String,
        type: Int,
        account: String = "com.google",
        superPrimary: Boolean = false,
        primary: Boolean = false,
    ): Map<String, Any?> = row(
        mimeType = Phone.CONTENT_ITEM_TYPE,
        data1 = number,
        data2 = type,
        account = account,
        superPrimary = superPrimary,
        primary = primary,
    )

    private fun email(address: String, type: Int): Map<String, Any?> =
        row(mimeType = Email.CONTENT_ITEM_TYPE, data1 = address, data2 = type)

    private fun custom(dataId: Long, mimeType: String, account: String, summary: String? = null): Map<String, Any?> =
        row(id = dataId, mimeType = mimeType, data3 = summary, account = account)

    /** Registers [packageName] as the resolver for [intent] — the default-handler lookup the built-in channels use. */
    private fun registerHandler(intent: Intent, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.HandlerActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    /** Installs a package so its application label resolves (device apps have one; Robolectric's don't by default). */
    private fun installApp(packageName: String, label: String) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun row(
        id: Long = 1,
        mimeType: String,
        data1: String? = null,
        data2: Int? = null,
        data3: String? = null,
        account: String = "com.google",
        superPrimary: Boolean = false,
        primary: Boolean = false,
    ): Map<String, Any?> = mapOf(
        ContactsContract.Data._ID to id,
        ContactsContract.Data.MIMETYPE to mimeType,
        ContactsContract.Data.DATA1 to data1,
        ContactsContract.Data.DATA2 to data2,
        ContactsContract.Data.DATA3 to data3,
        ContactsContract.Data.IS_SUPER_PRIMARY to if (superPrimary) 1 else 0,
        ContactsContract.Data.IS_PRIMARY to if (primary) 1 else 0,
        ContactsContract.RawContacts.ACCOUNT_TYPE to account,
    )

    private companion object {
        val DATA_COLUMNS = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
            ContactsContract.Data.IS_SUPER_PRIMARY,
            ContactsContract.Data.IS_PRIMARY,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
        )
    }
}
