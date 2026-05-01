package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AppIconLoader {
    private const val MAX_CACHED_ICONS = 256
    private val cache = LruCache<String, Drawable>(MAX_CACHED_ICONS)

    fun cached(id: String): Drawable? = cache.get(id)

    suspend fun load(context: Context, app: InstalledApp): Drawable? {
        cache.get(app.id)?.let { return it }
        val drawable = withContext(Dispatchers.IO) { resolve(context, app) }
        if (drawable != null) {
            cache.put(app.id, drawable)
        }
        return drawable
    }

    private fun resolve(context: Context, app: InstalledApp): Drawable? {
        val component = app.launchIntent.component ?: return null
        return if (app.launchWithLauncherApps) {
            context.getSystemService<LauncherApps>()
                ?.getActivityList(component.packageName, app.user)
                ?.firstOrNull { activity -> activity.componentName == component }
                ?.getIcon(0)
        } else {
            try {
                context.packageManager.getActivityIcon(component)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

@Composable
internal fun rememberAppIconDrawable(app: InstalledApp): Drawable? {
    val context = LocalContext.current.applicationContext
    var drawable by remember(app.id) { mutableStateOf(AppIconLoader.cached(app.id)) }
    LaunchedEffect(app.id) {
        if (drawable == null) {
            drawable = AppIconLoader.load(context, app)
        }
    }
    return drawable
}
