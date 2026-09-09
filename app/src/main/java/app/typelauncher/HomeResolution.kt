package app.typelauncher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import com.mikelward.androidlog.safe

/**
 * Records how the system currently resolves a Home press, and whether the
 * launcher still holds the Home role.
 *
 * These are two different questions, and the gap between them is the whole
 * point. Holding `ROLE_HOME` is what a user means by "Type Launcher is my
 * launcher", and the launcher already logs it on every resume. What it does
 * not say is what an actual Home press would *reach*: that is ordinary intent
 * resolution against every activity filtering for
 * [Intent.CATEGORY_HOME], and when it cannot pick one the system puts up its
 * chooser — the "which Home app do you want to use" sheet — instead. A report
 * has arrived showing exactly that combination: the sheet appeared, the user
 * picked Type Launcher, the role read back as held throughout, and the process
 * never died, so nothing was swapping the APK underneath it.
 *
 * So sample resolution alongside the role, at the two occasions that are worth
 * a line whatever they read: the start of a run, and a Home press the system
 * handed to us through its chooser. A report that carries both says whether the
 * sheet appeared while resolution was ambiguous or while it was perfectly
 * ordinary — the distinction that report could not supply. Cheap enough to sit
 * inside work that is already running: one `PackageManager` call and one
 * `RoleManager` call, both off the main thread at every call site.
 */
internal object HomeResolution {
    /**
     * Reads the current pair and logs it.
     *
     * Unconditional: both call sites mark an occasion rather than poll, so
     * there are two lines per run at most and no reading is worth suppressing.
     *
     * [moment] is a call-site literal (`processStart`, `homeChooser`), so it
     * names an occasion rather than anything of the user's. Pinned: a
     * resolution change is precisely the kind of thing noticed hours later, by
     * which time the ring buffer is long past it.
     *
     * Does IPC, so never call it from the main thread.
     */
    fun record(context: Context, moment: String) {
        LauncherDebugLog.pinnedEvent(
            "homeResolution moment=%s resolvesTo=%s roleHeld=%s",
            safe(moment),
            safe(resolveHomeTarget(context)),
            safe(homeRoleHeld(context)),
        )
    }

    /**
     * What a Home press would resolve to right now, as one of four fixed words:
     * `self`, `chooser` (the system could not pick, so it would ask),
     * `other` (another launcher), or `none`.
     *
     * Deliberately a category and not a package name. `self` versus `chooser`
     * is the entire diagnostic, and naming the other launcher on the user's
     * device would put their installed apps into a log they share — for nothing
     * this question needs.
     *
     * `MATCH_DEFAULT_ONLY` is what the framework's own home resolution uses, so
     * this reads the same answer the system would act on rather than a
     * differently-filtered one.
     */
    private fun resolveHomeTarget(context: Context): String {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val resolved = try {
            context.packageManager.resolveActivity(home, flags)
        } catch (exception: RuntimeException) {
            // A binder failure mid-package-change is exactly the moment this is
            // read about, so report it and keep the reading rather than letting
            // it escape into a reload or an activity start.
            LauncherDebugLog.failure(exception, "homeResolution: resolving the home intent failed")
            return "unknown"
        }
        // Null rather than empty on failure: an empty candidate list is a real
        // answer (nothing filters for Home) while a failed query is not, and
        // reading one as the other would call every resolution a chooser.
        val candidates = try {
            context.packageManager.queryIntentActivities(home, flags)
                .mapNotNull { it.activityInfo?.let { info -> "${info.packageName}/${info.name}" } }
                .toSet()
        } catch (exception: RuntimeException) {
            LauncherDebugLog.failure(exception, "homeResolution: listing the home candidates failed")
            null
        }
        val info = resolved?.activityInfo
        return homeTargetName(info?.packageName, info?.name, candidates, context.packageName)
    }

    /**
     * The naming half of [resolveHomeTarget], split out so it can be tested
     * without staging a package manager: which of the words a resolution maps
     * to is the part a reader acts on, and mislabelling the chooser as `other`
     * (or as `self`) would make the log confidently wrong.
     *
     * The chooser is told apart by *absence*, not by its package. It has no
     * `CATEGORY_HOME` filter of its own — the platform substitutes it when
     * resolution cannot pick among the real candidates — so a resolved
     * component that is not one of the activities [candidateComponents] lists
     * is the chooser, whoever ships it. Matching a package name instead was
     * wrong on any build that supplies the resolver from a Mainline or OEM
     * package rather than the framework's own, and wrong in the worst
     * direction: it would name another launcher as the target at precisely the
     * moment Android was in fact asking the user (Codex on PR #689).
     *
     * A null [candidateComponents] means that query failed rather than came
     * back empty, so the answer is `unknown` — the chooser test cannot be run,
     * and guessing would produce the alarming value from a failed read.
     */
    internal fun homeTargetName(
        resolvedPackage: String?,
        resolvedClass: String?,
        candidateComponents: Set<String>?,
        ownPackage: String,
    ): String = when {
        resolvedPackage == null -> "none"
        // Ahead of the chooser test: we are a real candidate, and this is the
        // one answer that needs no candidate list to be sure of.
        resolvedPackage == ownPackage -> "self"
        candidateComponents == null -> "unknown"
        "$resolvedPackage/$resolvedClass" !in candidateComponents -> "chooser"
        else -> "other"
    }

    /** `true` / `false` / `unknown` — the role as a word, so a failed read is not read as "no". */
    private fun homeRoleHeld(context: Context): String = try {
        val held = context.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_HOME)
        held?.toString() ?: "unknown"
    } catch (exception: RuntimeException) {
        LauncherDebugLog.failure(exception, "homeResolution: reading the home role failed")
        "unknown"
    }
}
