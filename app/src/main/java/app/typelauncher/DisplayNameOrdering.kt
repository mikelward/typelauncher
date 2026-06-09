package app.typelauncher

import java.text.Collator
import java.util.Locale

/**
 * Locale-aware, case-insensitive ordering for user-visible app and widget
 * names. `String.CASE_INSENSITIVE_ORDER` compares case-folded UTF-16 code
 * units, so accented Latin labels ("École", "Über") sorted after "Zoom" and
 * non-Latin scripts sorted by raw code point. A SECONDARY-strength collator
 * folds case while keeping accents next to their base letter, and follows
 * the device locale's tailoring (ö within o for German, after z for
 * Swedish).
 *
 * Collator instances are neither cheap to create nor thread-safe, so they
 * are cached per thread and re-created when the default locale changes (the
 * launcher has no locale-change hook, but any list reload after the change —
 * package event, process restart — picks up the new ordering).
 */
internal val DISPLAY_NAME_ORDER: Comparator<String> = Comparator { left, right ->
    displayNameCollator().compare(left, right)
}

private val collatorCache = ThreadLocal<Pair<Locale, Collator>>()

private fun displayNameCollator(): Collator {
    val locale = Locale.getDefault()
    collatorCache.get()?.let { (cachedLocale, collator) ->
        if (cachedLocale == locale) return collator
    }
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    collatorCache.set(locale to collator)
    return collator
}
