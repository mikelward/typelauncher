package app.typelauncher

/**
 * A summary built in two forms, one for each side of the debug log's boundary.
 *
 * **This is no longer a log argument.** The shared logger
 * (`mikelward/androidlog`) decides per argument what may leave the device, and
 * it has no tag for a value that renders differently on each side — that tag,
 * `either(full, reduced)`, is designed and deferred in the library's own
 * `TODO.md`. Until it lands, a call site logs [full] **untagged**: the
 * device's own copy keeps everything it has today, and the library withholds
 * the whole summary from anything leaving.
 *
 * So the type survives for **composition** — a bundle's summary folded into an
 * intent's, each side kept apart while it is assembled — and for the day
 * `either(...)` can carry both halves through. Deleting [mirrored] would mean
 * rebuilding [mirroredVocabulary] and [mirroredScheme] from scratch then, for
 * no gain now.
 *
 * See `TODO.md` under *Privacy → Decisions needing review* for why the untagged
 * form was chosen over `safe(mirrored)`: that alternative reduces the on-device
 * report too, which is the surface a bug report is actually read from.
 */
internal class LogSummary(
    /** Everything, for the device's own log. */
    val full: String,
    /**
     * The identifying fields removed — held for `either(...)`, and rendered
     * nowhere today.
     */
    val mirrored: String,
) {
    override fun toString(): String = full
}
