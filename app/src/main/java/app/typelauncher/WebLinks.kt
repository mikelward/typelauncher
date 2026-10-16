package app.typelauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * The package every web link entry carries. Not a real app: links launch
 * their page with `ACTION_VIEW`, and a shared, fixed package keeps them out of
 * anything keyed on an installed package (the disambiguator skips a group of
 * one package; package events never name it).
 */
internal const val WEB_LINK_PACKAGE = "app.typelauncher.weblink"

/** A page the user added to the app list through Share → "Add to app list". */
internal data class WebLink(val id: String, val name: String, val url: String)

/** What a share offered: the page's address and a name to start the dialog with. */
internal data class SharedWebLink(val url: String, val name: String)

/**
 * The page in a share, or null when it holds no http(s) address. Browsers
 * share the address as the text — some with the title before it — and the
 * title as the subject; the dialog's name starts from the title, then any
 * other text, then the site's host.
 */
internal fun parseSharedWebLink(text: CharSequence?, subject: CharSequence?): SharedWebLink? {
    val body = text?.toString().orEmpty()
    val match = WEB_ADDRESS.find(body) ?: return null
    // An address on a line of its own is exactly what the sharing app sent —
    // a browser shares the page's address, alone or under its title — so it
    // is kept as is, punctuation and all (".../wiki/Yahoo!"). Only an address
    // inside other text may have picked up the sentence's punctuation.
    val standsAlone = body.lineSequence().any { line -> line.trim() == match.value }
    val url = if (standsAlone) match.value else trimTrailingPunctuation(match.value)
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    if (scheme != "http" && scheme != "https") return null
    val name = subject?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: body.removeRange(match.range).trim().takeIf { it.isNotEmpty() }
        ?: host.removePrefix("www.")
    return SharedWebLink(url = url, name = name.take(MAX_NAME_LENGTH))
}

// Sentence punctuation after an address in prose isn't part of it ("see https://a.b/c."),
// but a closing bracket the address itself opened is
// (".../wiki/Function_(mathematics)"), so a bracket goes only when unmatched.
private fun trimTrailingPunctuation(address: String): String {
    var end = address.length
    while (end > 0) {
        val last = address[end - 1]
        val opener = CLOSING_BRACKETS[last]
        val trailing = if (opener != null) {
            val body = address.substring(0, end)
            body.count { it == last } > body.count { it == opener }
        } else {
            last in TRAILING_PUNCTUATION
        }
        if (!trailing) break
        end--
    }
    return address.substring(0, end)
}

private val CLOSING_BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')
private val WEB_ADDRESS = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val TRAILING_PUNCTUATION = setOf('.', ',', ';', ':', '!', '?', '"', '\'')
private const val MAX_NAME_LENGTH = 100

/**
 * The entry key for a web link: a random id, never the address. Entry ids
 * reach the dock list a bug report shares, and which pages someone keeps is
 * theirs.
 */
internal fun webLinkEntryId(user: UserHandle, webLinkId: String): String = "${user.hashCode()}:link:$webLinkId"

/** [link] as an app-list entry. Personal profile only: the store is this profile's. */
internal fun webLinkEntry(link: WebLink, user: UserHandle = Process.myUserHandle()): InstalledApp = InstalledApp(
    name = link.name,
    packageName = WEB_LINK_PACKAGE,
    launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addCategory(Intent.CATEGORY_BROWSABLE),
    user = user,
    isWorkApp = false,
    launchWithLauncherApps = false,
    // "Remove" deletes the link — always possible.
    isUninstallable = true,
    webLinkId = link.id,
)

/**
 * A web link's icon: the first letter of its name on a colored plate, the
 * color picked from the name so a link keeps its color. An adaptive icon, so
 * the icon pipeline clips it to the launcher's shape like every other app's.
 */
internal fun letterTileIcon(name: String): Drawable =
    AdaptiveIconDrawable(ColorDrawable(letterTileColor(name)), LetterDrawable(letterTileLetter(name)))

/** The letter a tile shows: the name's first letter or digit, else "?". */
internal fun letterTileLetter(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

internal fun letterTileColor(name: String): Int =
    LETTER_TILE_COLORS[Math.floorMod(name.trim().lowercase().hashCode(), LETTER_TILE_COLORS.size)]

// Material 600-tone hues, each dark enough for white text.
private val LETTER_TILE_COLORS = intArrayOf(
    0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(),
    0xFFF4511E.toInt(), 0xFF00897B.toInt(), 0xFF3949AB.toInt(), 0xFF6D4C41.toInt(),
)

// An adaptive icon's foreground layer: 108 units across, of which the middle
// 72 show, so the letter is sized to read as half the visible tile.
private class LetterDrawable(private val letter: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        paint.textSize = bounds.width() / 3f
        val baseline = bounds.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, bounds.exactCenterX(), baseline, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The user's web links, on this device. Included in the launcher's backup like
 * the dock, since they are the user's own choices.
 *
 * Writes `commit()`: callers add and remove off the main thread and report a
 * failed write rather than one that quietly reverts on the next launch.
 */
internal class WebLinkStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun links(): List<WebLink> = synchronized(LOCK) { read() }

    /** Adds [url] under [name], or returns the link already holding [url]. Null when the write failed. */
    fun add(name: String, url: String): AddResult? = synchronized(LOCK) {
        val links = read()
        links.firstOrNull { link -> link.url == url }?.let { existing -> return@synchronized AddResult(existing, isNew = false) }
        val link = WebLink(id = UUID.randomUUID().toString(), name = name, url = url)
        if (write(links + link)) AddResult(link, isNew = true) else null
    }.also { result -> if (result?.isNew == true) changed.tryEmit(Unit) }

    /** Removes the link with [id]; false when the write failed. */
    fun remove(id: String): Boolean = synchronized(LOCK) {
        val links = read()
        val remaining = links.filterNot { link -> link.id == id }
        remaining.size == links.size || write(remaining).also { written -> if (written) changed.tryEmit(Unit) }
    }

    data class AddResult(val link: WebLink, val isNew: Boolean)

    private fun read(): List<WebLink> {
        val raw = sharedPreferences.getString(KEY_LINKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val url = obj.optString(KEY_URL).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                WebLink(id = id, name = obj.optString(KEY_NAME).ifEmpty { url }, url = url)
            }
        } catch (exception: JSONException) {
            // No content in the line: the addresses are the user's own.
            LauncherDebugLog.failure(exception, "WebLinkStore unreadable")
            emptyList()
        }
    }

    private fun write(links: List<WebLink>): Boolean {
        val array = JSONArray()
        links.forEach { link ->
            array.put(JSONObject().put(KEY_ID, link.id).put(KEY_NAME, link.name).put(KEY_URL, link.url))
        }
        return sharedPreferences.edit().putString(KEY_LINKS, array.toString()).commit()
    }

    companion object {
        const val PREFERENCES_NAME = "web_links"
        private const val KEY_LINKS = "links"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_URL = "url"

        private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Fires after each add or remove, from any component in the process,
         * so the launcher reloads its list. Not replayed: a view model
         * created later reads the store when it loads.
         */
        val changes: SharedFlow<Unit> = changed

        // Process-wide: the share activity and the launcher's view model each
        // build their own store, and an add racing a remove must not drop one.
        private val LOCK = Any()
    }
}
