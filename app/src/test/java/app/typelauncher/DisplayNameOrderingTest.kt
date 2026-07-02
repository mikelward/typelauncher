package app.typelauncher

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the comparator-contract guarantee of [displayNameOrder]: the returned
 * comparator is bound to one collator snapshot, so a default-locale change
 * concurrent with an in-progress sort cannot swap tailorings mid-stream. The
 * old singleton comparator re-resolved `Locale.getDefault()` on every
 * `compare`, so early and late comparisons within one sort could disagree — a
 * contract violation TimSort escalates to "Comparison method violates its
 * general contract!" on the app-list load path.
 */
class DisplayNameOrderingTest {
    private lateinit var originalDefault: Locale

    @Before
    fun rememberDefaultLocale() {
        originalDefault = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun comparatorKeepsItsTailoringWhenTheDefaultLocaleChangesMidSort() {
        // Swedish tailoring places "ö" after "z"; German places it with "o",
        // well before "z" — the pair flips between the two locales.
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))
        val comparator = displayNameOrder()
        assertTrue(
            "Swedish collation must put ö after z",
            comparator.compare("Öl", "Zebra") > 0,
        )

        // The system language changes while a sort using this comparator is
        // still running…
        Locale.setDefault(Locale.GERMANY)

        // …and the comparator must keep answering with the tailoring it was
        // created under, not silently switch to German mid-sort.
        assertTrue(
            "comparator must stay bound to its creation-time collator",
            comparator.compare("Öl", "Zebra") > 0,
        )
    }

    @Test
    fun freshComparatorPicksUpTheNewDefaultLocale() {
        // The per-sort snapshot must not turn into a permanent freeze: the
        // next reload's comparator follows the new locale.
        Locale.setDefault(Locale.forLanguageTag("sv-SE"))
        assertTrue(displayNameOrder().compare("Öl", "Zebra") > 0)

        Locale.setDefault(Locale.GERMANY)
        assertTrue(
            "a comparator created after the change must use German tailoring",
            displayNameOrder().compare("Öl", "Zebra") < 0,
        )
    }

    @Test
    fun foldsCaseAtSecondaryStrength() {
        val comparator = displayNameOrder()
        assertTrue(comparator.compare("chrome", "Chrome") == 0)
    }
}
