package app.typelauncher

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers how the landscape letter strip files apps and where each letter jumps. */
class AppListLetterTest {
    private val english = Collator.getInstance(Locale.US)
    private val swedish = Collator.getInstance(Locale.forLanguageTag("sv-SE"))

    @Test
    fun readsTheFirstLetterUppercased() {
        assertEquals("C", appListLetter("Calendar"))
        assertEquals("C", appListLetter("calculator"))
        assertEquals("M", appListLetter("  Maps"))
        assertEquals("É", appListLetter("éclair"))
    }

    @Test
    fun nonLettersReadAsHash() {
        assertEquals("#", appListLetter("1Password"))
        assertEquals("#", appListLetter("@Home"))
        assertEquals("#", appListLetter(""))
    }

    @Test
    fun sectionsListEachLetterOnceInListOrderWithItsFirstIndex() {
        val names = listOf("1Password", "Apple", "Atlas", "Browser", "Calendar", "Clock", "Zebra")
        assertEquals(
            listOf("#" to 0, "A" to 1, "B" to 3, "C" to 4, "Z" to 6),
            appListLetterSections(names, english),
        )
    }

    @Test
    fun accentsJoinTheirBaseLetterWhereTheLocaleSortsThemThere() {
        // English sorts É among the Es: one E section, labeled E, from Eagle.
        val names = listOf("Eagle", "Éclair", "Edge", "Files")
        assertEquals(listOf("E" to 0, "F" to 3), appListLetterSections(names, english))
        // A run that starts with the accented letter still reads as its base.
        assertEquals(listOf("E" to 0), appListLetterSections(listOf("École", "Edge"), english))
    }

    @Test
    fun accentsStayTheirOwnLetterWhereTheLocaleSortsThemApart() {
        // Swedish sorts Å after Z: A still jumps to Apple, and Å is its own
        // letter at the end, as the Swedish-sorted list has it.
        val names = listOf("Apple", "Browser", "Zebra", "Ångström")
        assertEquals(
            listOf("A" to 0, "B" to 1, "Z" to 2, "Å" to 3),
            appListLetterSections(names, swedish),
        )
    }

    @Test
    fun aLetterInTwoRunsJumpsToItsLastRun() {
        // A dock-hidden list floats docked apps ("Zoom", "Camera") to the top;
        // their letters jump into the name-sorted body below, not the top.
        val names = listOf("Zoom", "Camera", "Apple", "Browser", "Calendar", "Zebra")
        assertEquals(
            listOf("A" to 2, "B" to 3, "C" to 4, "Z" to 5),
            appListLetterSections(names, english),
        )
    }

    @Test
    fun emptyListHasNoSections() {
        assertEquals(emptyList<Pair<String, Int>>(), appListLetterSections(emptyList(), english))
    }
}
