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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AppIconLoader {
    private const val MAX_CACHED_ICONS = 256
    private const val ICON_BITMAP_SIZE_PX = 96
    private val cache = LruCache<String, ImageBitmap>(MAX_CACHED_ICONS)

    fun cached(id: String): ImageBitmap? = cache.get(id)

    suspend fun load(context: Context, app: InstalledApp): ImageBitmap? {
        cache.get(app.id)?.let { return it }
        val drawable = withContext(Dispatchers.IO) { resolve(context, app) } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            drawable.toBitmap(width = ICON_BITMAP_SIZE_PX, height = ICON_BITMAP_SIZE_PX).asImageBitmap()
        }
        cache.put(app.id, bitmap)
        return bitmap
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
internal fun rememberAppIconBitmap(app: InstalledApp): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var bitmap by remember(app.id) { mutableStateOf(AppIconLoader.cached(app.id)) }
    LaunchedEffect(app.id) {
        if (bitmap == null) {
            bitmap = AppIconLoader.load(context, app)
        }
    }
    return bitmap
}
