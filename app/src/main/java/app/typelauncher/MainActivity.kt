package app.typelauncher

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appWidgetHost = AppWidgetHost(this, APP_WIDGET_HOST_ID)
        appWidgetManager = AppWidgetManager.getInstance(this)
        viewModel = ViewModelProvider(
            this,
            LauncherViewModel.factory(
                app = application,
                workPackages = intent?.getStringArrayExtra(TEST_WORK_PACKAGES_EXTRA)?.toSet().orEmpty(),
            ),
        )[LauncherViewModel::class.java]
        setContent {
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
    }

    override fun onStart() {
        super.onStart()
        appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionDrivenUi()
    }

    override fun onStop() {
        appWidgetHost.stopListening()
        super.onStop()
    }

    private fun bindWidget(provider: WidgetProvider) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = appWidgetId
        if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.profile, provider.componentName, null)) {
            configureOrAddWidget(appWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return
        }

        val bindIntent: Intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.componentName)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
        try {
            bindWidgetLauncher.launch(bindIntent)
        } catch (_: ActivityNotFoundException) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            deletePendingWidget(appWidgetId)
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            deletePendingWidget(appWidgetId)
            Toast.makeText(this, R.string.widgets_picker_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureOrAddWidget(appWidgetId: Int) {
        val providerInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (providerInfo?.configure != null) {
            pendingWidgetId = appWidgetId
            configureWidgetLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setComponent(providerInfo.configure)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        } else {
            viewModel.addWidget(appWidgetId)
        }
    }

    private fun deletePendingWidget(appWidgetId: Int) {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
    }

    private fun removeWidget(appWidgetId: Int) {
        viewModel.removeWidget(appWidgetId)
        deletePendingWidget(appWidgetId)
    }
}
