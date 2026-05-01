package app.typelauncher

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.ViewModelProvider

internal const val TEST_WORK_PACKAGES_EXTRA = "app.typelauncher.TEST_WORK_PACKAGES"
private const val APP_WIDGET_HOST_ID = 1024

class MainActivity : ComponentActivity() {
    internal lateinit var viewModel: LauncherViewModel
        private set
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var appWidgetManager: AppWidgetManager

    private val requestCalendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            LauncherDebugLog.event("requestCalendarPermission result granted=$it")
            viewModel.refreshAgenda()
        }
    private val bindWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val appWidgetId = result.data?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: pendingWidgetId
            if (result.resultCode == RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                configureOrAddWidget(appWidgetId)
            } else {
                deletePendingWidget(appWidgetId)
            }
            LauncherDebugLog.event(
                "bindWidget resultCode=${result.resultCode} appWidgetId=$appWidgetId pendingWidgetId=$pendingWidgetId",
            )
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    private val configureWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val appWidgetId = result.data?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: pendingWidgetId
            if (result.resultCode == RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                viewModel.addWidget(appWidgetId)
            } else {
                deletePendingWidget(appWidgetId)
            }
            LauncherDebugLog.event(
                "configureWidget resultCode=${result.resultCode} appWidgetId=$appWidgetId pendingWidgetId=$pendingWidgetId",
            )
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        LauncherDebugLog.activityCallback(this, "MainActivity.onCreate beforeSuper")
        LauncherDebugLog.event("onCreate savedInstanceState=${savedInstanceState.debugSummary()}")
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LauncherDebugLog.event("onCreate afterSuper window=${window.debugSummary()}")
        window.decorView.doOnPreDraw {
            LauncherDebugLog.event("MainActivity firstPreDraw window=${window.debugSummary()}")
        }
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this)
        LauncherDebugLog.event("AppWidgetHost initialized hostId=$APP_WIDGET_HOST_ID")
        viewModel = ViewModelProvider(
            this,
            LauncherViewModel.factory(
                app = application,
                workPackages = intent?.getStringArrayExtra(TEST_WORK_PACKAGES_EXTRA)?.toSet().orEmpty(),
            ),
        )[LauncherViewModel::class.java]
        LauncherDebugLog.event("ViewModel ready ${viewModel.uiState.value.debugSummary()}")
        LauncherDebugLog.event("setContent begin")
        setContent {
            LauncherDebugLog.event("setContent composing TypeLauncherTheme")
            TypeLauncherTheme {
                TypeLauncherApp(
                    viewModel = viewModel,
                    appWidgetHost = appWidgetHost,
                    appWidgetManager = appWidgetManager,
                    onAddWidget = viewModel::showWidgetPicker,
                    onDismissWidgetPicker = viewModel::hideWidgetPicker,
                    onSelectWidget = ::bindWidget,
                    onRemoveWidget = ::removeWidget,
                    onRequestCalendarPermission = {
                        requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    },
                )
            }
        }
        LauncherDebugLog.event("setContent returned")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LauncherDebugLog.activityCallback(this, "MainActivity.onNewIntent", intent)
    }

    override fun onRestart() {
        super.onRestart()
        LauncherDebugLog.activityCallback(this, "MainActivity.onRestart")
    }

    override fun onStart() {
        super.onStart()
        LauncherDebugLog.activityCallback(this, "MainActivity.onStart")
        appWidgetHost.startListening()
        LauncherDebugLog.event("AppWidgetHost.startListening")
    }

    override fun onResume() {
        super.onResume()
        LauncherDebugLog.activityCallback(this, "MainActivity.onResume")
        viewModel.refreshPermissionDrivenUi()
    }

    override fun onPostResume() {
        super.onPostResume()
        LauncherDebugLog.activityCallback(this, "MainActivity.onPostResume")
    }

    override fun onPause() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onPause")
        super.onPause()
    }

    override fun onStop() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onStop")
        appWidgetHost.stopListening()
        LauncherDebugLog.event("AppWidgetHost.stopListening")
        super.onStop()
    }

    override fun onDestroy() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onDestroy")
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        LauncherDebugLog.event("MainActivity.onSaveInstanceState beforeSuper outState=${outState.debugSummary()}")
        super.onSaveInstanceState(outState)
        LauncherDebugLog.event("MainActivity.onSaveInstanceState afterSuper outState=${outState.debugSummary()}")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        LauncherDebugLog.event(
            "MainActivity.onRestoreInstanceState beforeSuper savedInstanceState=${savedInstanceState.debugSummary()}",
        )
        super.onRestoreInstanceState(savedInstanceState)
        LauncherDebugLog.event(
            "MainActivity.onRestoreInstanceState afterSuper savedInstanceState=${savedInstanceState.debugSummary()}",
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LauncherDebugLog.event("MainActivity.onAttachedToWindow window=${window.debugSummary()}")
    }

    override fun onDetachedFromWindow() {
        LauncherDebugLog.event("MainActivity.onDetachedFromWindow window=${window.debugSummary()}")
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        LauncherDebugLog.event("MainActivity.onWindowFocusChanged hasFocus=$hasFocus window=${window.debugSummary()}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LauncherDebugLog.event(
            "MainActivity.onConfigurationChanged orientation=${newConfig.orientation} " +
                "keyboard=${newConfig.keyboard} keyboardHidden=${newConfig.keyboardHidden} " +
                "uiMode=0x${newConfig.uiMode.toString(16)} screenLayout=0x${newConfig.screenLayout.toString(16)}",
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LauncherDebugLog.event("MainActivity.onTrimMemory level=$level description=${level.trimMemoryDescription()}")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LauncherDebugLog.warning("MainActivity.onLowMemory")
    }

    override fun onUserLeaveHint() {
        LauncherDebugLog.activityCallback(this, "MainActivity.onUserLeaveHint")
        super.onUserLeaveHint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        LauncherDebugLog.event("MainActivity.onKeyDown keyCode=$keyCode event=${event.debugSummary()}")
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        LauncherDebugLog.event("MainActivity.onKeyUp keyCode=$keyCode event=${event.debugSummary()}")
        return super.onKeyUp(keyCode, event)
    }

    private fun bindWidget(provider: WidgetProvider) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = appWidgetId
        LauncherDebugLog.event(
            "bindWidget provider=${provider.componentName.flattenToShortString()} appWidgetId=$appWidgetId",
        )
        if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.profile, provider.componentName, null)) {
            LauncherDebugLog.event("bindWidget allowed appWidgetId=$appWidgetId")
            configureOrAddWidget(appWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return
        }

        val bindIntent: Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.componentName)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
        try {
            LauncherDebugLog.event("bindWidget launching system bind intent appWidgetId=$appWidgetId")
            bindWidgetLauncher.launch(bindIntent)
        } catch (exception: ActivityNotFoundException) {
            LauncherDebugLog.warning("bindWidget failed: picker unavailable", exception)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            deletePendingWidget(appWidgetId)
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("bindWidget failed: security exception", exception)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            deletePendingWidget(appWidgetId)
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrAddWidget(appWidgetId: Int) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (providerInfo?.configure != null) {
            pendingWidgetId = appWidgetId
            LauncherDebugLog.event(
                "configureOrAddWidget launching configure appWidgetId=$appWidgetId " +
                    "configure=${providerInfo.configure.flattenToShortString()}",
            )
            configureWidgetLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(providerInfo.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        } else {
            LauncherDebugLog.event("configureOrAddWidget adding appWidgetId=$appWidgetId hasProvider=${providerInfo != null}")
            viewModel.addWidget(appWidgetId)
        }
    }

    private fun deletePendingWidget(appWidgetId: Int) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            LauncherDebugLog.event("deletePendingWidget appWidgetId=$appWidgetId")
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
    }

    private fun removeWidget(appWidgetId: Int) {
        LauncherDebugLog.event("removeWidget appWidgetId=$appWidgetId")
        viewModel.removeWidget(appWidgetId)
        deletePendingWidget(appWidgetId)
    }
}

private fun Int.trimMemoryDescription(): String =
    when (this) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN"
    }
