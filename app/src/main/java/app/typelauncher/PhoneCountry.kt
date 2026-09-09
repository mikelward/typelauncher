package app.typelauncher

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Country-aware phone number ordering for the contact sheet's number pickers:
 * within the same default tier, numbers the current SIM can reach domestically
 * sort ahead of foreign-country ones. A number in international `+` format is
 * foreign when its country calling code differs from the SIM country's; a
 * national-format number (no `+`) is dialable as-is on the local network, so it
 * counts as local. Countries sharing a calling code (the +1 NANP family, +7
 * Russia/Kazakhstan) are indistinguishable by prefix and all rank as local —
 * a coarse but safe over-match, since those numbers dial without an
 * international prefix anyway.
 *
 * The SIM country comes from [TelephonyManager.getSimCountryIso], which needs
 * no permission; the calling-code table below is the ITU E.164 assignment list
 * keyed by ISO 3166-1 alpha-2, matching the lowercase codes telephony returns.
 */

/**
 * The country calling code of the SIM behind [subscriptionId], or null when no
 * SIM country is known — callers then leave number order alone. An invalid
 * [subscriptionId] means the channel has no default SIM (a dual-SIM user set
 * calls or texts to "ask every time"), so there is no country to sort by and
 * the answer is null rather than whichever SIM happens to be the device
 * default. A valid subscription whose country is unreadable falls back to the
 * default SIM's country — on the single-SIM devices that path serves, they are
 * the same SIM. Performs a system-service IPC; call off the main thread.
 */
internal fun simCountryCallingCode(context: Context, subscriptionId: Int): String? {
    if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) return null
    val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
    val subscriptionIso = runCatching {
        telephony.createForSubscriptionId(subscriptionId)?.simCountryIso
    }.getOrNull()
    val iso = subscriptionIso?.takeIf { it.isNotBlank() }
        ?: runCatching { telephony.simCountryIso }.getOrNull()
    return countryCallingCodeForIso(iso)
}

/** Maps an ISO 3166-1 alpha-2 country code (any case) to its E.164 calling code, or null when unknown/blank. */
internal fun countryCallingCodeForIso(countryIso: String?): String? =
    countryIso?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)?.let { COUNTRY_CALLING_CODES[it] }

/**
 * True when [number] is in international format for a country other than the
 * one behind [localCallingCode]. Calling codes are prefix-free by ITU design,
 * so a simple prefix test is unambiguous. With no known local code (null),
 * nothing is foreign — the caller's existing order stands.
 */
internal fun isForeignPhoneNumber(number: String, localCallingCode: String?): Boolean {
    if (localCallingCode == null) return false
    val dialable = number.filterNot { it in PHONE_SEPARATOR_CHARS }
    if (!dialable.startsWith("+")) return false
    return !dialable.startsWith("+$localCallingCode")
}

// Visual phone-number separators, stripped before comparing numbers (the
// cross-account dedupe and the country-prefix test). Dialing-significant
// characters (`+`, `#`, `*`, and the pause / wait `,` `;`) are deliberately
// kept, so a number with an extension or USSD suffix is not merged with its
// bare-digits sibling.
internal val PHONE_SEPARATOR_CHARS = "\u0020\u00A0-().\u2013\u2014/".toSet()

// ITU E.164 country calling codes keyed by ISO 3166-1 alpha-2, lowercase to
// match TelephonyManager.getSimCountryIso.
private val COUNTRY_CALLING_CODES: Map<String, String> = buildMap {
    fun code(callingCode: String, vararg isoCountries: String) {
        isoCountries.forEach { put(it, callingCode) }
    }
    // Zone 1 — NANP.
    code(
        "1",
        "us", "ca", "ag", "ai", "as", "bb", "bm", "bs", "dm", "do", "gd", "gu", "jm", "kn",
        "ky", "lc", "mp", "ms", "pr", "sx", "tc", "tt", "vc", "vg", "vi",
    )
    // Zone 2 — Africa and some Atlantic islands.
    code("20", "eg"); code("211", "ss"); code("212", "ma", "eh"); code("213", "dz")
    code("216", "tn"); code("218", "ly"); code("220", "gm"); code("221", "sn")
    code("222", "mr"); code("223", "ml"); code("224", "gn"); code("225", "ci")
    code("226", "bf"); code("227", "ne"); code("228", "tg"); code("229", "bj")
    code("230", "mu"); code("231", "lr"); code("232", "sl"); code("233", "gh")
    code("234", "ng"); code("235", "td"); code("236", "cf"); code("237", "cm")
    code("238", "cv"); code("239", "st"); code("240", "gq"); code("241", "ga")
    code("242", "cg"); code("243", "cd"); code("244", "ao"); code("245", "gw")
    code("246", "io"); code("248", "sc"); code("249", "sd"); code("250", "rw")
    code("251", "et"); code("252", "so"); code("253", "dj"); code("254", "ke")
    code("255", "tz"); code("256", "ug"); code("257", "bi"); code("258", "mz")
    code("260", "zm"); code("261", "mg"); code("262", "re", "yt"); code("263", "zw")
    code("264", "na"); code("265", "mw"); code("266", "ls"); code("267", "bw")
    code("268", "sz"); code("269", "km"); code("27", "za"); code("290", "sh")
    code("291", "er"); code("297", "aw"); code("298", "fo"); code("299", "gl")
    // Zones 3 & 4 — Europe.
    code("30", "gr"); code("31", "nl"); code("32", "be"); code("33", "fr")
    code("34", "es"); code("350", "gi"); code("351", "pt"); code("352", "lu")
    code("353", "ie"); code("354", "is"); code("355", "al"); code("356", "mt")
    code("357", "cy"); code("358", "fi", "ax"); code("359", "bg"); code("36", "hu")
    code("370", "lt"); code("371", "lv"); code("372", "ee"); code("373", "md")
    code("374", "am"); code("375", "by"); code("376", "ad"); code("377", "mc")
    code("378", "sm"); code("380", "ua"); code("381", "rs"); code("382", "me")
    code("383", "xk"); code("385", "hr"); code("386", "si"); code("387", "ba")
    code("389", "mk")
    // Vatican numbers dial on the Italian plan (+39); its assigned 379 is unused.
    code("39", "it", "va")
    code("40", "ro"); code("41", "ch"); code("420", "cz"); code("421", "sk")
    code("423", "li"); code("43", "at"); code("44", "gb", "gg", "im", "je")
    code("45", "dk"); code("46", "se"); code("47", "no", "sj"); code("48", "pl")
    code("49", "de")
    // Zone 5 — Central / South America and the Caribbean outside the NANP.
    code("500", "fk"); code("501", "bz"); code("502", "gt"); code("503", "sv")
    code("504", "hn"); code("505", "ni"); code("506", "cr"); code("507", "pa")
    code("508", "pm"); code("509", "ht"); code("51", "pe"); code("52", "mx")
    code("53", "cu"); code("54", "ar"); code("55", "br"); code("56", "cl")
    code("57", "co"); code("58", "ve"); code("590", "gp", "bl", "mf")
    code("591", "bo"); code("592", "gy"); code("593", "ec"); code("594", "gf")
    code("595", "py"); code("596", "mq"); code("597", "sr"); code("598", "uy")
    code("599", "cw", "bq")
    // Zone 6 — Southeast Asia and Oceania.
    code("60", "my"); code("61", "au", "cx", "cc"); code("62", "id"); code("63", "ph")
    code("64", "nz", "pn"); code("65", "sg"); code("66", "th"); code("670", "tl")
    code("672", "nf"); code("673", "bn"); code("674", "nr"); code("675", "pg")
    code("676", "to"); code("677", "sb"); code("678", "vu"); code("679", "fj")
    code("680", "pw"); code("681", "wf"); code("682", "ck"); code("683", "nu")
    code("685", "ws"); code("686", "ki"); code("687", "nc"); code("688", "tv")
    code("689", "pf"); code("690", "tk"); code("691", "fm"); code("692", "mh")
    // Zone 7.
    code("7", "ru", "kz")
    // Zone 8 — East Asia.
    code("81", "jp"); code("82", "kr"); code("84", "vn"); code("850", "kp")
    code("852", "hk"); code("853", "mo"); code("855", "kh"); code("856", "la")
    code("86", "cn"); code("880", "bd"); code("886", "tw")
    // Zone 9 — West, Central, and South Asia.
    code("90", "tr"); code("91", "in"); code("92", "pk"); code("93", "af")
    code("94", "lk"); code("95", "mm"); code("960", "mv"); code("961", "lb")
    code("962", "jo"); code("963", "sy"); code("964", "iq"); code("965", "kw")
    code("966", "sa"); code("967", "ye"); code("968", "om"); code("970", "ps")
    code("971", "ae"); code("972", "il"); code("973", "bh"); code("974", "qa")
    code("975", "bt"); code("976", "mn"); code("977", "np"); code("98", "ir")
    code("992", "tj"); code("993", "tm"); code("994", "az"); code("995", "ge")
    code("996", "kg"); code("998", "uz")
}
