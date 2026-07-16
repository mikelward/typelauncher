package app.typelauncher

/**
 * A contact surfaced by Home's typed-search contacts section. Deliberately
 * tiny — id + lookup key are exactly what `ContactsContract.Contacts.getLookupUri`
 * needs to open the contact card, and the display name is what search matches
 * and the row renders. The full index is held in memory so the per-keystroke
 * filter never touches the content resolver (see `LauncherViewModel`).
 *
 * [photoThumbnailUri] is the contact's `PHOTO_THUMBNAIL_URI` when the contact
 * has a photo, or null for the monogram fallback. Only the URI string lives in
 * the index — the bitmap itself is decoded lazily per rendered row (see
 * `ContactPhotoLoader`), so indexing a thousand contacts never reads a single
 * photo blob.
 */
internal data class ContactResult(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val photoThumbnailUri: String? = null,
)

/**
 * Filters the in-memory contact index down to the contacts section for [query].
 *
 * Matching is deliberately restricted to the precise tiers — prefix, anchored,
 * and substring — with the looser fallback tiers (fuzzy subsequence, digit-word,
 * package-brand) excluded: the anchored tier already covers the patterns people
 * use for humans (initials and cross-word abbreviations like `jd` → John Doe),
 * and a mid-word-skip tail of weakly-related people reads worse than the same
 * tail of apps. Within the section the precise tiers rank in the usual order;
 * the sort is stable, so the index's alphabetical pre-sort is the tie-break.
 * TODO: decide whether contacts should also admit the Fuzzy tier — revisit if
 *  precise-only leaves real names unreachable (mid-word abbreviations of
 *  concatenated names like "MacKenzie"). The tier ranking already sinks fuzzy
 *  below every precise match, so admitting it is a one-line change to the
 *  tier gate below.
 *
 * Blank queries return no results — content sections only exist while typing;
 * the empty-query browse list stays apps-only.
 */
internal fun List<ContactResult>.filterContactResults(query: String): List<ContactResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    return mapNotNull { contact ->
        val tier = contact.displayName.launcherMatchTier(trimmed) ?: return@mapNotNull null
        if (tier > LauncherMatchTier.Substring) return@mapNotNull null
        contact to tier
    }
        .sortedBy { (_, tier) -> tier }
        .map { (contact, _) -> contact }
}

/**
 * Filters the in-memory calendar-event index down to the events section for
 * [query]. Same precise-tier rule and blank-query contract as
 * [filterContactResults]; the stable sort keeps the index's chronological
 * ordering (all-day today first, then timed events by start) as the
 * within-tier tie-break, so equal-quality matches read soonest-first.
 */
internal fun List<AgendaEvent>.filterEventResults(query: String): List<AgendaEvent> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    return mapNotNull { event ->
        val tier = event.title.launcherMatchTier(trimmed) ?: return@mapNotNull null
        if (tier > LauncherMatchTier.Substring) return@mapNotNull null
        event to tier
    }
        .sortedBy { (_, tier) -> tier }
        .map { (event, _) -> event }
}
