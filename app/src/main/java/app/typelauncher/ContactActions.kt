package app.typelauncher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.compose.runtime.Immutable

/**
 * The compact quick-actions sheet's model of one contact: the ways to reach
 * them, grouped into [channels] the way the sheet's first step lists them —
 * Phone (call), Message, then whatever apps have registered contact
 * integrations (WhatsApp, Signal, Meet, …), then Email. Built off the main
 * thread by [resolveContactChannels] from the same contact the search index
 * already surfaced; the sheet renders step one from [channels] and only shows a
 * second step when a channel offers more than one [ContactAction].
 *
 * Empty [channels] means the contact has no reachable data at all (a name-only
 * card) — the sheet then offers just the "Open contact" escape hatch.
 */
@Immutable
internal data class ContactActions(
    val contact: ContactResult,
    val channels: List<ContactChannel>,
)

/**
 * One row of the first step: a way to reach the contact, grouping the concrete
 * [actions] it offers. [iconPackageName], when set, is the app whose launcher
 * icon represents the channel — the app that owns a third-party integration
 * (WhatsApp, Signal, Meet), or the phone's default handler for a built-in
 * channel (the dialer for Phone, the messaging app for Message, the mail app for
 * Email) so those read as the real apps too instead of generic glyphs. [glyph]
 * is the fallback the Compose layer paints when [iconPackageName] is null or its
 * icon can't be loaded, so a built-in channel always has something to show.
 */
@Immutable
internal data class ContactChannel(
    val id: String,
    val label: String,
    val iconPackageName: String?,
    val glyph: ContactChannelGlyph?,
    val actions: List<ContactAction>,
)

/** The built-in channels' fallback glyphs, painted when no real app icon resolves. */
internal enum class ContactChannelGlyph { Call, Message, Email }

/**
 * A concrete thing the sheet can do — a leaf of the second step, or fired
 * immediately when its channel has only one. [isDefault] marks the contact's
 * primary number/address (`IS_SUPER_PRIMARY` / `IS_PRIMARY`), which the resolver
 * sorts to the top so the default sits first in the number picker.
 */
@Immutable
internal data class ContactAction(
    val label: String,
    val detail: String?,
    val isDefault: Boolean,
    val kind: ContactActionKind,
)

/**
 * How an action is dispatched. [Call] is special: it is placed with
 * `ACTION_CALL` after a one-time `CALL_PHONE` grant (falling back to the dialer
 * when the permission is refused), so it carries the raw [number] rather than a
 * pre-built intent — the permission decision lives at dispatch time, not here.
 * Everything else — SMS, email, and every third-party contact row — is already
 * a fully-formed [Launch] intent.
 */
internal sealed interface ContactActionKind {
    data class Call(val number: String) : ContactActionKind
    data class Launch(val intent: Intent) : ContactActionKind
}

// Standard data kinds handled explicitly (Phone, Email) or deliberately not
// surfaced as quick actions (names, postal addresses, notes, photos, group
// membership, …). Every *other* mimetype on the contact is treated as a
// candidate third-party action and offered only if it resolves to an activity —
// that "resolve, don't hardcode" rule is what lets WhatsApp / Signal / Meet /
// Teams and anything future appear without per-app code.
private val NON_ACTION_MIME_TYPES: Set<String> = setOf(
    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
    ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
)

/**
 * Reads the contact's data rows and groups them into the sheet's channels,
 * default-first within each. Runs a single content-resolver query plus one
 * `PackageManager` resolution per distinct third-party mimetype, so it must be
 * called off the main thread (on tap, never per keystroke). A missing
 * permission or a provider/resolver failure degrades to empty channels rather
 * than throwing — the sheet then offers only "Open contact", the same graceful
 * floor the search index uses.
 *
 * [simCallingCode] maps a subscription id to that SIM's country calling code
 * and defaults to the real telephony lookup; it is a parameter because
 * Robolectric's `TelephonyManager` shadow keeps one process-global SIM country,
 * so a dual-SIM device (different voice and SMS SIMs) is only testable by
 * injection.
 */
internal fun resolveContactChannels(
    context: Context,
    contact: ContactResult,
    simCallingCode: (subscriptionId: Int) -> String? = { simCountryCallingCode(context, it) },
): List<ContactChannel> {
    val phones = mutableListOf<PhoneRow>()
    val emails = mutableListOf<EmailRow>()
    // Third-party rows keyed by their owning app's package, preserving first-seen
    // order.
    val appRows = LinkedHashMap<String, MutableList<AppRow>>()
    val appLabels = HashMap<String, String>()

    // Maps a contact account type to the package of the app that registered its
    // authenticator. An account type is usually the app's own package, but when
    // it's an account *namespace* instead this recovers the real owning package
    // so the action still resolves. Built once; a failure degrades to empty.
    val authenticatorPackages: Map<String, String> = runCatching {
        android.accounts.AccountManager.get(context).authenticatorTypes
            .associate { it.type to it.packageName }
    }.getOrDefault(emptyMap())

    val projection = arrayOf(
        ContactsContract.Data._ID,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.Data.DATA1,
        ContactsContract.Data.DATA2,
        ContactsContract.Data.DATA3,
        ContactsContract.Data.IS_SUPER_PRIMARY,
        ContactsContract.Data.IS_PRIMARY,
        // The account type is the app that contributed the row — `com.whatsapp`,
        // `org.thoughtcrime.securesms`, `com.google.android.apps.tachyon` — and
        // (for the messaging integrations) equals that app's package. It's how a
        // custom action row is attributed to the right app instead of guessing
        // from a package-less resolveActivity, which returns the system chooser.
        ContactsContract.RawContacts.ACCOUNT_TYPE,
    )
    try {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contact.contactId.toString()),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val data1Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val data2Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
            val data3Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
            val superPrimaryIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.IS_SUPER_PRIMARY)
            val primaryIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.IS_PRIMARY)
            val accountTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mimeIndex) ?: continue
                val superPrimary = cursor.getInt(superPrimaryIndex) != 0
                val primary = cursor.getInt(primaryIndex) != 0
                val accountType = cursor.getString(accountTypeIndex)
                when {
                    mimeType == Phone.CONTENT_ITEM_TYPE -> {
                        val number = cursor.getString(data1Index)?.takeIf { it.isNotBlank() } ?: continue
                        phones += PhoneRow(
                            number = number,
                            type = cursor.getInt(data2Index),
                            typeLabel = cursor.getString(data3Index),
                            superPrimary = superPrimary,
                            primary = primary,
                        )
                    }
                    mimeType == Email.CONTENT_ITEM_TYPE -> {
                        val address = cursor.getString(data1Index)?.takeIf { it.isNotBlank() } ?: continue
                        emails += EmailRow(
                            address = address,
                            type = cursor.getInt(data2Index),
                            typeLabel = cursor.getString(data3Index),
                            superPrimary = superPrimary,
                            primary = primary,
                        )
                    }
                    mimeType in NON_ACTION_MIME_TYPES -> Unit
                    else -> {
                        // Attribute the row to the app that contributed it and
                        // target the action intent at that package. The candidate
                        // is the account type (== the package for a real
                        // integration), falling back to the authenticator's
                        // package when the type is a namespace. Two things fall
                        // out: WhatsApp / Signal / Meet resolve to the real app (a
                        // package-less resolveActivity returns the system chooser
                        // instead, collapsing them into one "android" channel);
                        // and generic data kinds an unrelated app merely declares
                        // a handler for — a `calling_card` / `bestie` row on a
                        // plain `com.google` account — drop out, because that
                        // account isn't an app that owns the mimetype.
                        if (accountType.isNullOrBlank()) continue
                        val dataId = cursor.getLong(idIndex)
                        val dataUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId)
                        val candidates = buildList {
                            add(accountType)
                            authenticatorPackages[accountType]?.let { if (it != accountType) add(it) }
                        }
                        val owner = resolveContactActionOwner(
                            context.packageManager, dataUri, mimeType, candidates,
                        ) ?: continue
                        appLabels.getOrPut(owner.packageName) {
                            runCatching {
                                context.packageManager.getApplicationLabel(
                                    context.packageManager.getApplicationInfo(owner.packageName, 0),
                                ).toString()
                            }.getOrNull()
                                ?: owner.resolveInfo.loadLabel(context.packageManager)?.toString()
                                ?: owner.packageName
                        }
                        appRows.getOrPut(owner.packageName) { mutableListOf() } += AppRow(
                            // The summary column (DATA3) is the app's own action
                            // label — "Message", "Voice call", "Video call".
                            label = cursor.getString(data3Index)?.takeIf { it.isNotBlank() },
                            // The resolved activity's own label is the secondary
                            // distinguisher when DATA3 is empty: Meet contributes an
                            // audio and a video row that both fall back to the app
                            // name "Meet" otherwise, but their activities can carry
                            // distinct labels ("Audio meeting" / "Video meeting").
                            activityLabel = owner.resolveInfo.loadLabel(context.packageManager)
                                ?.toString()?.takeIf { it.isNotBlank() },
                            intent = owner.intent,
                            superPrimary = superPrimary,
                            primary = primary,
                        )
                    }
                }
            }
        }
    } catch (exception: SecurityException) {
        LauncherDebugLog.warning("resolveContactChannels denied contactId=${contact.contactId}", exception)
        return emptyList()
    } catch (exception: RuntimeException) {
        LauncherDebugLog.warning("resolveContactChannels failed contactId=${contact.contactId}", exception)
        return emptyList()
    }

    val resources = context.resources
    val channels = mutableListOf<ContactChannel>()

    // The same number is often synced under several accounts (Google + WhatsApp
    // + Signal + Meet each carry a phone row), so dedupe by digits before
    // building the Call / Message channels — otherwise the number picker lists
    // the same line four times. Sort default-first so the retained copy is the
    // primary one, then local-country numbers ahead of foreign ones: the Call
    // picker keys off the default voice SIM's country and the Message picker
    // off the default SMS SIM's, since a dual-SIM user routinely calls on one
    // SIM and texts on the other. With no known SIM country the order is
    // unchanged.
    if (phones.isNotEmpty()) {
        // runCatching because the static subscription-id lookups throw
        // UnsupportedOperationException on devices without
        // FEATURE_TELEPHONY_SUBSCRIPTION (the manifest keeps telephony
        // optional) — a Wi-Fi-only tablet degrades to the resolver order.
        val callCallingCode = runCatching {
            simCallingCode(SubscriptionManager.getDefaultVoiceSubscriptionId())
        }.getOrNull()
        val smsCallingCode = runCatching {
            simCallingCode(SubscriptionManager.getDefaultSmsSubscriptionId())
        }.getOrNull()
        fun orderedPhones(localCallingCode: String?): List<PhoneRow> = phones
            .sortedWith(
                defaultFirst<PhoneRow> { it.superPrimary to it.primary }
                    .thenBy { isForeignPhoneNumber(it.number, localCallingCode) },
            )
            .distinctBy { phone -> phone.number.dedupeKey() }
        val callPhones = orderedPhones(callCallingCode)
        val smsPhones =
            if (smsCallingCode == callCallingCode) callPhones else orderedPhones(smsCallingCode)
        channels += ContactChannel(
            id = "call",
            label = context.getString(R.string.contact_action_call),
            iconPackageName = defaultDialerPackage(context),
            glyph = ContactChannelGlyph.Call,
            actions = callPhones.map { phone ->
                ContactAction(
                    label = Phone.getTypeLabel(resources, phone.type, phone.typeLabel).toString(),
                    detail = phone.number,
                    isDefault = phone.superPrimary || phone.primary,
                    kind = ContactActionKind.Call(phone.number),
                )
            },
        )
        channels += ContactChannel(
            id = "message",
            label = context.getString(R.string.contact_action_message),
            iconPackageName = defaultSmsPackage(context),
            glyph = ContactChannelGlyph.Message,
            actions = smsPhones.map { phone ->
                ContactAction(
                    label = Phone.getTypeLabel(resources, phone.type, phone.typeLabel).toString(),
                    detail = phone.number,
                    isDefault = phone.superPrimary || phone.primary,
                    kind = ContactActionKind.Launch(
                        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone.number, null)),
                    ),
                )
            },
        )
    }

    appRows.forEach { (packageName, rows) ->
        val orderedRows = rows.sortedWith(defaultFirst { it.superPrimary to it.primary })
        val appLabel = appLabels[packageName] ?: packageName
        channels += ContactChannel(
            id = packageName,
            label = appLabel,
            iconPackageName = packageName,
            glyph = null,
            // Label each row from its own summary (DATA3), falling back to the
            // resolved activity's label before the app name so distinct actions
            // stay distinct: Meet's audio and video rows leave DATA3 empty but
            // their activities can carry "Audio meeting" / "Video meeting". Only
            // rows that still render the identical label collapse — two entries a
            // user can't tell apart are worse than one, and a lone survivor lets
            // the whole channel fire on tap instead of drilling into a picker.
            actions = orderedRows.map { row ->
                ContactAction(
                    label = row.label
                        ?: row.activityLabel?.takeIf { it != appLabel }
                        ?: appLabel,
                    detail = null,
                    isDefault = row.superPrimary || row.primary,
                    kind = ContactActionKind.Launch(row.intent),
                )
            }.distinctBy { it.label },
        )
    }

    val orderedEmails = emails.sortedWith(defaultFirst { it.superPrimary to it.primary })
    if (orderedEmails.isNotEmpty()) {
        channels += ContactChannel(
            id = "email",
            label = context.getString(R.string.contact_action_email),
            iconPackageName = defaultMailPackage(context),
            glyph = ContactChannelGlyph.Email,
            actions = orderedEmails.map { email ->
                ContactAction(
                    label = Email.getTypeLabel(resources, email.type, email.typeLabel).toString(),
                    detail = email.address,
                    isDefault = email.superPrimary || email.primary,
                    kind = ContactActionKind.Launch(
                        Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email.address, null)),
                    ),
                )
            },
        )
    }

    return channels
}

/**
 * Orders rows default-first: super-primary ahead of primary ahead of the rest,
 * with the content resolver's own order preserved as the stable tie-break so
 * equal rows keep their natural sequence.
 */
private class ResolvedContactAction(
    val packageName: String,
    val intent: Intent,
    val resolveInfo: android.content.pm.ResolveInfo,
)

/**
 * Resolves the first [candidatePackages] entry whose app declares an activity
 * for the contact action ([mimeType] on [dataUri]); the intent is targeted at
 * that package so it launches the app directly rather than the system chooser.
 * Returns the owning package, the ready-to-fire intent, and the resolve info
 * (for a label fallback), or null when none of the candidates own the action.
 */
private fun resolveContactActionOwner(
    packageManager: PackageManager,
    dataUri: Uri,
    mimeType: String,
    candidatePackages: List<String>,
): ResolvedContactAction? {
    for (pkg in candidatePackages) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(dataUri, mimeType).setPackage(pkg)
        val resolved = packageManager.resolveActivity(intent, 0)
        if (resolved?.activityInfo?.packageName == pkg) {
            return ResolvedContactAction(pkg, intent, resolved)
        }
    }
    return null
}

/**
 * The phone's default dialer package, so the Call channel shows the real dialer
 * icon. Null (→ the [ContactChannelGlyph.Call] fallback) on a device with no
 * telephony or when the package isn't visible/loadable. `getDefaultDialerPackage`
 * carries no permission requirement.
 */
private fun defaultDialerPackage(context: Context): String? =
    runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }
        .getOrNull()
        ?.takeIf { it.hasLoadableIcon(context.packageManager) }

/** The phone's default SMS package for the Message channel icon; null → the glyph fallback. */
private fun defaultSmsPackage(context: Context): String? =
    runCatching { Telephony.Sms.getDefaultSmsPackage(context) }
        .getOrNull()
        ?.takeIf { it.hasLoadableIcon(context.packageManager) }

/**
 * The app that would handle a fresh email for the Email channel icon. A
 * package-less `mailto` resolve returns the system chooser (package `android`)
 * when the user hasn't picked a default — there's no single app to represent
 * then, so that resolves to null and the glyph shows instead.
 */
private fun defaultMailPackage(context: Context): String? {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", "", null))
    val resolved = context.packageManager.resolveActivity(intent, 0) ?: return null
    val pkg = resolved.activityInfo?.packageName ?: return null
    return pkg.takeIf { it != "android" && it.hasLoadableIcon(context.packageManager) }
}

/** True when [this] package is installed and its icon can be loaded — the gate before naming it as a channel's icon source. */
private fun String.hasLoadableIcon(packageManager: PackageManager): Boolean =
    runCatching { packageManager.getApplicationInfo(this, 0) }.isSuccess

/** Dedupe key that ignores formatting but preserves dialing syntax; falls back to the raw number if it's all separators. */
private fun String.dedupeKey(): String = filterNot { it in PHONE_SEPARATOR_CHARS }.ifBlank { this }

private inline fun <T> defaultFirst(crossinline rank: (T) -> Pair<Boolean, Boolean>): Comparator<T> =
    compareByDescending<T> { rank(it).first }.thenByDescending { rank(it).second }

private data class PhoneRow(
    val number: String,
    val type: Int,
    val typeLabel: String?,
    val superPrimary: Boolean,
    val primary: Boolean,
)

private data class EmailRow(
    val address: String,
    val type: Int,
    val typeLabel: String?,
    val superPrimary: Boolean,
    val primary: Boolean,
)

private data class AppRow(
    val label: String?,
    val activityLabel: String?,
    val intent: Intent,
    val superPrimary: Boolean,
    val primary: Boolean,
)
