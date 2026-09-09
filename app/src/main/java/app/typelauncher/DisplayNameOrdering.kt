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
 *
 * Exposed as a function returning a comparator bound to one collator
 * snapshot, not as a singleton comparator that re-resolves the default
 * locale on every `compare`: a system-language change concurrent with an
 * in-progress sort used to swap the collator mid-stream, making early and
 * late comparisons within one sort disagree — a comparator-contract
 * violation TimSort can escalate to "Comparison method violates its general
 * contract!" on the app-list load path. Call once per sort. The returned
 * comparator must stay on the calling thread (the underlying collator is
 * the thread-cached instance), which synchronous `sortedWith` callsites
 * naturally satisfy.
 */
internal fun displayNameOrder(): Comparator<String> {
    val collator = displayNameCollator()
    return Comparator { left, right -> collator.compare(left, right) }
}

private val collatorCache = ThreadLocal<Pair<Locale, Collator>>()

private fun displayNameCollator(): Collator {
    val locale = Locale.getDefault()
    collatorCache.get()?.let { (cachedLocale, collator) ->
        if (cachedLocale == locale) return collator
    }
    val collator = secondaryStrengthCollator(locale)
    collatorCache.set(locale to collator)
    return collator
}

/**
 * The launcher's one collation rule for user-visible names: locale-tailored,
 * SECONDARY strength so case folds while accents keep their base-letter
 * position. Callers own caching — Collators are neither cheap to create nor
 * thread-safe, so the returned instance must stay on the calling thread.
 */
internal fun secondaryStrengthCollator(locale: Locale): Collator =
    Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
