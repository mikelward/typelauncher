package app.typelauncher

import java.util.concurrent.atomic.AtomicReference

/**
 * Strips app identifiers out of a debug-log line on its way to Crashlytics.
 *
 * The launcher logs package names deliberately — "which app failed to launch",
 * "which dock entry moved" — and those lines are the diagnostic value of the
 * on-device log and the bug report the user reviews before sending. What they
 * must not do is ride along to Crashlytics, which uploads silently: together
 * they amount to the user's installed-app inventory, which `AGENTS.md` keeps
 * off the machine and `PRIVACY.md` promises the breadcrumbs don't carry.
 *
 * Redaction is **exact, not heuristic**: the launcher already holds the
 * installed-app list, so [rememberPackages] hands it over and a token is
 * redacted when it *is* a package the launcher has seen, not when it looks like
 * one. That covers every existing call site and any added later, in whatever
 * format — `package=`, the `user:package/Class` form of `InstalledApp.id`, a
 * bare mention — without anyone having to remember. It also leaves ordinary
 * dotted tokens (`java.lang.SecurityException`, `1.2.3`) untouched, which a
 * pattern-matching filter would mangle.
 *
 * The set **accumulates and is never pruned**, which is what makes it correct
 * across a package's lifetime rather than only while it is installed. Two log
 * lines sit outside any "currently installed" window and would otherwise leak:
 * `scheduleReload reason=packageAdded:<pkg>` names a package *before* the
 * reload that would add it, and the matching `scheduleReload complete
 * reason=…` repeats the reason *after* a removed package has dropped out. So
 * the `LauncherApps` callbacks remember the name before scheduling, and nothing
 * ever forgets one — an uninstalled app staying redacted is the outcome we
 * want anyway.
 *
 * A package the launcher has never seen is the remaining gap, along with every
 * category that isn't a package name. That is the argument for the structural
 * fix tracked in `TODO.md`; this is the filter that closes the leak now.
 */
internal object TelemetryRedaction {

    /** Stands in for a redacted identifier; deliberately unmistakable in a report. */
    const val PLACEHOLDER = "<app>"

    /**
     * Ceiling on the accumulated set. Reached only by a device that installs and
     * uninstalls thousands of distinct packages in one process lifetime, so it
     * is a runaway-growth backstop rather than a working limit. Once full the
     * set stops growing, which fails *open* for the next new package — noted
     * against the structural fix in `TODO.md` rather than silently accepted.
     */
    private const val MAX_REMEMBERED = 4_096

    // Swapped wholesale rather than mutated, so a log call on any thread reads
    // one consistent set without locking.
    private val knownPackages = AtomicReference<Set<String>>(emptySet())

    /**
     * Adds [packages] to the set matched against. Called with each app load and
     * with the package name carried by a `LauncherApps` callback, before the
     * event is logged.
     *
     * Accumulates rather than replaces: a package must stay redacted after it
     * is uninstalled, because the reload it triggers logs its name on the way
     * out. Until the first call nothing is redacted *because nothing is known* —
     * safe, since a package name can only be logged once the launcher has seen
     * the package.
     */
    fun rememberPackages(packages: Collection<String>) {
        if (packages.isEmpty()) return
        while (true) {
            val current = knownPackages.get()
            if (current.containsAll(packages)) return
            if (current.size >= MAX_REMEMBERED) return
            val merged = buildSet(current.size + packages.size) {
                addAll(current)
                addAll(packages)
            }
            if (knownPackages.compareAndSet(current, merged)) return
        }
    }

    /** Convenience for the single-package `LauncherApps` callbacks. */
    fun rememberPackage(packageName: String) = rememberPackages(listOf(packageName))

    /** Test-only: drops the published set so tests don't leak into each other. */
    internal fun clearForTest() {
        knownPackages.set(emptySet())
    }

    /**
     * [message] with every installed package name replaced by [PLACEHOLDER].
     *
     * Splits on the characters that can't appear in a package name, so an
     * identifier is found wherever it sits — after `package=`, inside
     * `appId=0:com.example.app/.Main`, or on its own. The candidate is compared
     * against the set as a whole and after dropping a trailing `/Class`, since
     * `InstalledApp.id` glues the two together.
     */
    fun redact(message: String): String {
        val packages = knownPackages.get()
        if (packages.isEmpty() || message.isEmpty()) return message

        val out = StringBuilder(message.length)
        var index = 0
        while (index < message.length) {
            if (!message[index].isTokenChar()) {
                out.append(message[index])
                index++
                continue
            }
            var end = index
            while (end < message.length && message[end].isTokenChar()) end++
            out.append(redactToken(message.substring(index, end), packages))
            index = end
        }
        return out.toString()
    }

    private fun redactToken(token: String, packages: Set<String>): String {
        // A package name always contains a dot, so skip the overwhelming
        // majority of tokens (keys, numbers, enum names) without a set lookup.
        if ('.' !in token) return token
        // The **whole** token goes, not the matching part of it. An
        // `InstalledApp.id` is "<userHash>:<package>/<class>" with `class`
        // *fully qualified*, so redacting only the package left
        // `10:/com.example.mail.MainActivity` — the package still spelled out
        // in the class name, plus the profile identifier. A component or an app
        // id is one identifier; it is redacted as one.
        val parts = token.split(':', '/')
        if (parts.none { it.namesAKnownPackage(packages) }) return token
        // A component or an app id — anything with a `/` — is one identifier
        // and goes whole. `InstalledApp.id` is "<userHash>:<package>/<class>"
        // with the class *fully qualified*, so redacting only the package left
        // the package spelled out again in the class name, plus the profile
        // hash. Everything else keeps its non-package parts, because those
        // carry the diagnosis: `packageAdded:<app>` still says what happened.
        if ('/' in token) return PLACEHOLDER
        val out = StringBuilder(token.length)
        var index = 0
        for (part in parts) {
            if (index > 0) out.append(token[index - 1])
            out.append(if (part.namesAKnownPackage(packages)) PLACEHOLDER else part)
            index += part.length + 1
        }
        return out.toString()
    }

    /**
     * Whether [this] is a known package or lies inside one — a fully qualified
     * class name starts with its package, so `com.example.mail.MainActivity`
     * names `com.example.mail`.
     */
    private fun String.namesAKnownPackage(packages: Set<String>): Boolean =
        isNotEmpty() && packages.any { pkg -> this == pkg || startsWith("$pkg.") }

    // Everything a package name, an `InstalledApp.id`, or a flattened component
    // can be built from. `=` and whitespace are separators, so a `key=value`
    // pair splits into its two halves.
    private fun Char.isTokenChar(): Boolean =
        isLetterOrDigit() || this == '.' || this == '_' || this == '/' || this == ':' || this == '$'
}
