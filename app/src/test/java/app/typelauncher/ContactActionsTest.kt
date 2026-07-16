package app.typelauncher

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
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

/**
 * Unit coverage for [resolveContactChannels]: the contact → channels grouping,
 * default-first ordering within a channel, and the generic "resolve, don't
 * hardcode" surfacing of third-party actions (a WhatsApp-shaped custom mimetype
 * stands in for any installed integration).
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
    fun sortsPrimaryNumberAheadWhenNoSuperPrimary() {
        registerDataProvider(
            phone("+1-555-0001", Phone.TYPE_WORK),
            phone("+1-555-0002", Phone.TYPE_MOBILE, primary = true),
        )

        val call = resolveContactChannels(context, contact).first { it.id == "call" }

        assertEquals("Primary number leads when nothing is super-primary", "+1-555-0002", call.actions.first().detail)
    }

    @Test
    fun surfacesInstalledAppActionsGenerically() {
        val whatsappMessage = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        val whatsappCall = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        registerResolver("com.whatsapp", "WhatsApp", dataId = 201, mimeType = whatsappMessage)
        registerResolver("com.whatsapp", "WhatsApp", dataId = 202, mimeType = whatsappCall)
        registerDataProvider(
            phone("+1-555-0100", Phone.TYPE_MOBILE, superPrimary = true),
            custom(dataId = 201, mimeType = whatsappMessage, summary = "Message"),
            custom(dataId = 202, mimeType = whatsappCall, summary = "Voice call"),
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
        // Phone/Message come first, the app channel after them.
        assertEquals(listOf("call", "message", "com.whatsapp"), channels.map { it.id })
    }

    @Test
    fun ordersChannelsPhoneMessageAppsThenEmail() {
        registerResolver("com.whatsapp", "WhatsApp", dataId = 301, mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile")
        registerDataProvider(
            email("jess@example.com", Email.TYPE_HOME),
            custom(dataId = 301, mimeType = "vnd.android.cursor.item/vnd.com.whatsapp.profile", summary = "Message"),
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
            custom(dataId = 401, mimeType = "vnd.android.cursor.item/vnd.com.uninstalled.chat", summary = "Chat"),
        )

        assertTrue(resolveContactChannels(context, contact).isEmpty())
    }

    // --- helpers -------------------------------------------------------------

    private fun registerResolver(packageName: String, label: String, dataId: Long, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId),
            mimeType,
        )
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
        superPrimary: Boolean = false,
        primary: Boolean = false,
    ): Map<String, Any?> = row(
        mimeType = Phone.CONTENT_ITEM_TYPE,
        data1 = number,
        data2 = type,
        superPrimary = superPrimary,
        primary = primary,
    )

    private fun email(address: String, type: Int): Map<String, Any?> =
        row(mimeType = Email.CONTENT_ITEM_TYPE, data1 = address, data2 = type)

    private fun custom(dataId: Long, mimeType: String, summary: String): Map<String, Any?> =
        row(id = dataId, mimeType = mimeType, data3 = summary)

    private fun row(
        id: Long = 1,
        mimeType: String,
        data1: String? = null,
        data2: Int? = null,
        data3: String? = null,
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
        )
    }
}
