package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the typed-search content-section filters: precise tiers
 * only (prefix / anchored / substring — never fuzzy, digit-word, or
 * package-brand), tier-then-index-order ranking, and the blank-query contract
 * (sections only exist while typing).
 */
class ContentSearchFilterTest {
    private val contacts = listOf(
        contact(1, "Alice Baker"),
        contact(2, "Bob Oates"),
        contact(3, "John Doe"),
        contact(4, "Maria Lopez"),
        contact(5, "Mark Chen"),
        contact(6, "Miguel Hernandez"),
        contact(7, "Omar Marlow"),
    )

    @Test
    fun blankQueryReturnsNoContacts() {
        assertEquals(emptyList<ContactResult>(), contacts.filterContactResults(""))
        assertEquals(emptyList<ContactResult>(), contacts.filterContactResults("   "))
    }

    @Test
    fun prefixOutranksAnchored() {
        // Maria and Mark are prefix matches; "Marlow" anchors at its capital M
        // so Omar Marlow lands in the anchored tier below them (it also
        // contains "mar" as a substring of "Omar", but the best tier wins).
        assertEquals(
            listOf("Maria Lopez", "Mark Chen", "Omar Marlow"),
            contacts.filterContactResults("mar").map { it.displayName },
        )
    }

    @Test
    fun anchoredInitialsMatchAcrossWords() {
        // "jd" anchors J on John and lands d on the Doe word boundary — the
        // initials pattern the precise tiers already cover.
        assertEquals(
            listOf("John Doe"),
            contacts.filterContactResults("jd").map { it.displayName },
        )
    }

    @Test
    fun fuzzyMidWordSkipIsExcluded() {
        // "mgl" reaches "Miguel" only via the fuzzy tier (the g/l are mid-word
        // skips) — precise-only must reject it even though the app-list
        // matcher would admit it in the Fuzzy tier.
        assertTrue(contacts.filterContactResults("mgl").isEmpty())
    }

    @Test
    fun withinTierKeepsIndexOrder() {
        // Maria/Mark/Miguel are prefix matches for "m" and keep the index's
        // (alphabetical) order because the tier sort is stable; Omar Marlow
        // anchors at Marlow's capital M and ranks below the prefix bucket.
        val results = contacts.filterContactResults("m").map { it.displayName }
        assertEquals(
            listOf("Maria Lopez", "Mark Chen", "Miguel Hernandez", "Omar Marlow"),
            results,
        )
    }

    @Test
    fun starredContactRanksAboveNonStarredWithinTier() {
        // filterContactResults never inspects `starred` itself — it only
        // preserves whatever order it's handed (the tier sort is stable). The
        // starred-first ranking is entirely a property of the index's
        // pre-sort (see LauncherViewModel.loadContactIndex): starred contacts
        // first, alphabetical within each starred/non-starred group. This
        // list is built in exactly that pre-sorted shape — Miguel (starred)
        // ahead of alphabetically-earlier Maria and Mark — to confirm the
        // filter carries it through unchanged for a same-tier match.
        val preSorted = listOf(
            contact(6, "Miguel Hernandez", starred = true),
            contact(4, "Maria Lopez"),
            contact(5, "Mark Chen"),
        )
        assertEquals(
            listOf("Miguel Hernandez", "Maria Lopez", "Mark Chen"),
            preSorted.filterContactResults("m").map { it.displayName },
        )
    }

    @Test
    fun eventFilterMatchesPreciseTiersOnly() {
        val events = listOf(
            event(1, "Dentist appointment"),
            event(2, "Team standup"),
            event(3, "Dinner with Dana"),
        )
        // Dentist / Dinner are prefix matches (chronological within the tier);
        // "Team standup" only carries a mid-word "d", so it trails as a
        // substring match.
        assertEquals(
            listOf("Dentist appointment", "Dinner with Dana", "Team standup"),
            events.filterEventResults("d").map { it.title },
        )
        // "dst" would need mid-word skips (fuzzy) — excluded.
        assertTrue(events.filterEventResults("dst").isEmpty())
        assertTrue(events.filterEventResults("").isEmpty())
    }

    @Test
    fun eventTierOutranksChronology() {
        // "Team Standup" anchors at its capital S; "Withstand gala" only
        // contains "stand" mid-word — anchored ranks first even though the
        // substring event is chronologically earlier.
        val events = listOf(
            event(1, "Withstand gala"),
            event(2, "Team Standup"),
        )
        assertEquals(
            listOf("Team Standup", "Withstand gala"),
            events.filterEventResults("stand").map { it.title },
        )
    }

    @Test
    fun inlineSuggestionFallsBackToContentName() {
        // No app match: the suggestion previews the first content result (the
        // Enter target), bolding the matched run.
        val suggestion = searchInlineSuggestion(query = "mar", topMatch = null, fallbackName = "Maria Lopez")
        assertEquals("Maria Lopez", suggestion?.name)
        assertEquals(listOf(0, 1, 2), suggestion?.boldIndices)
        assertNull(searchInlineSuggestion(query = "mar", topMatch = null, fallbackName = null))
        assertNull(searchInlineSuggestion(query = "", topMatch = null, fallbackName = "Maria Lopez"))
    }

    private fun contact(id: Long, name: String, starred: Boolean = false) = ContactResult(
        contactId = id,
        lookupKey = "lookup-$id",
        displayName = name,
        starred = starred,
    )

    private fun event(id: Long, title: String) = AgendaEvent(
        title = title,
        beginMillis = id * 1_000_000L,
        endMillis = id * 1_000_000L + 3_600_000L,
        isAllDay = false,
        displayTime = "9:00 AM",
        eventId = id,
    )
}
