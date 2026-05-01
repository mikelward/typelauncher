package app.typelauncher

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateUtils
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.LinkedHashSet

internal const val TEST_WORK_PACKAGES_EXTRA = "app.typelauncher.TEST_WORK_PACKAGES"

class MainActivity : ComponentActivity() {
    internal lateinit var viewModel: LauncherViewModel
        private set

    private val requestCalendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshAgenda()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                    onRequestCalendarPermission = {
                        requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissionDrivenUi()
    }
}

@Composable
private fun TypeLauncherApp(
    viewModel: LauncherViewModel,
    onRequestCalendarPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionDrivenUi()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TypeLauncherApp(
        state = state,
        onQueryChanged = viewModel::setQuery,
        onClearQuery = { viewModel.setQuery("") },
        onLaunchActiveApp = viewModel::launchActiveApp,
        onLaunchApp = viewModel::launchApp,
        onOpenAppInfo = viewModel::openAppInfo,
        onToggleDock = viewModel::toggleDock,
        onResetRank = viewModel::resetRank,
        onShowAgenda = viewModel::showAgenda,
        onShowHome = viewModel::showHome,
        onRequestCalendarPermission = onRequestCalendarPermission,
    )
}

@Composable
private fun TypeLauncherApp(
    state: LauncherUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onShowAgenda: () -> Unit,
    onShowHome: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime),
    ) { innerPadding ->
        SwipeNavigationBox(
            screen = state.screen,
            onShowAgenda = onShowAgenda,
            onShowHome = onShowHome,
        ) { pageScreen ->
            when (pageScreen) {
                LauncherScreen.Home -> HomeScreen(
                    state = state,
                    innerPadding = innerPadding,
                    onQueryChanged = onQueryChanged,
                    onClearQuery = onClearQuery,
                    onLaunchActiveApp = onLaunchActiveApp,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                )
                LauncherScreen.Agenda -> AgendaScreen(
                    agenda = state.agenda,
                    innerPadding = innerPadding,
                    onRequestCalendarPermission = onRequestCalendarPermission,
                )
            }
        }
    }
}

@Composable
private fun SwipeNavigationBox(
    screen: LauncherScreen,
    onShowAgenda: () -> Unit,
    onShowHome: () -> Unit,
    content: @Composable (LauncherScreen) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = screen.carouselPage,
        pageCount = { LauncherScreen.carouselScreens.size },
    )
    val settledPage = pagerState.settledPage

    LaunchedEffect(screen) {
        if (pagerState.currentPage != screen.carouselPage) {
            pagerState.animateScrollToPage(screen.carouselPage)
        }
    }
    LaunchedEffect(settledPage) {
        when (LauncherScreen.fromCarouselPage(settledPage)) {
            LauncherScreen.Agenda -> if (screen != LauncherScreen.Agenda) onShowAgenda()
            LauncherScreen.Home -> if (screen != LauncherScreen.Home) onShowHome()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        content(LauncherScreen.fromCarouselPage(page))
    }
}

@Composable
private fun HomeScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val dockCapacity = ((configuration.screenWidthDp - 32) / DOCK_APP_ICON_SIZE_DP).coerceAtLeast(MIN_DOCKED_APPS)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(HOME_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchCard(
            query = state.query,
            onQueryChanged = onQueryChanged,
            onClearQuery = onClearQuery,
            onLaunchActiveApp = onLaunchActiveApp,
        )
        AppsCard(
            apps = state.filteredApps,
            dockCapacity = dockCapacity,
            modifier = Modifier.weight(1f),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
        DockCard(
            dockedApps = state.dockedApps,
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    SectionCard {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        onLaunchActiveApp()
                        true
                    } else {
                        false
                    }
                }
                .testTag(SEARCH_FIELD_TAG),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.app_search_clear_button_description))
                    }
                }
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.app_search_hint)) },
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus(force = false)
                    onLaunchActiveApp()
                },
            ),
        )
    }
}

@Composable
private fun DockCard(
    dockedApps: List<InstalledApp>,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    SectionCard(Modifier.testTag(DOCK_CARD_TAG)) {
        if (dockedApps.isEmpty()) {
            Text(
                text = stringResource(R.string.dock_apps_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag(DOCK_HINT_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag(DOCK_LIST_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dockedApps.forEach { app ->
                    DockedAppButton(
                        app = app,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    dockCapacity: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    SectionCard(modifier.testTag(APPS_CARD_TAG)) {
        if (apps.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(APPS_LIST_TAG),
            ) {
                items(apps, key = { app -> app.id }) { app ->
                    AppRow(
                        app = app,
                        isActive = app == apps.first(),
                        dockCapacity = dockCapacity,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    isActive: Boolean,
    dockCapacity: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    val rowColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor, MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = { onLaunchApp(app) },
                    onLongClick = { menuExpanded = true },
                )
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .testTag("$APP_ROW_TAG:${app.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(app = app, size = 40.dp)
            Text(
                app.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        AppActionsMenu(
            expanded = menuExpanded,
            app = app,
            dockCapacity = dockCapacity,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun AppActionsMenu(
    expanded: Boolean,
    app: InstalledApp,
    dockCapacity: Int,
    onDismiss: () -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onOpenAppInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (app.isDocked) R.string.app_menu_undock else R.string.app_menu_dock)) },
            modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onToggleDock(app, dockCapacity)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_reset_rank)) },
            modifier = Modifier.testTag("$RESET_RANK_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onResetRank(app)
            },
        )
    }
}

@Composable
private fun DockedAppButton(
    app: InstalledApp,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.name }
                .padding(4.dp)
                .testTag("$DOCK_APP_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = 56.dp)
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onLaunchApp(app) },
                    onLongClick = { menuExpanded = true },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = app.name
                },
        )
        AppActionsMenu(
            expanded = menuExpanded,
            app = app,
            dockCapacity = Int.MAX_VALUE,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun AppIcon(app: InstalledApp, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(app.id, app.icon) {
        app.icon?.toBitmap(width = 96, height = 96)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(size)
            .testTag("$APP_ICON_TAG:${app.name}"),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (app.isWorkApp) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .testTag("$WORK_APP_BADGE_TAG:${app.name}"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun AgendaScreen(
    agenda: AgendaUiState,
    innerPadding: PaddingValues,
    onRequestCalendarPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(AGENDA_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (agenda) {
            AgendaUiState.PermissionRequired -> PermissionCard(onRequestCalendarPermission)
            AgendaUiState.Empty -> EmptyAgendaCard()
            is AgendaUiState.Events -> AgendaEventsCard(agenda.events, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PermissionCard(onRequestCalendarPermission: () -> Unit) {
    SectionCard(
        modifier = Modifier.testTag(AGENDA_PERMISSION_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(stringResource(R.string.agenda_permission_message), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onRequestCalendarPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.agenda_permission_button))
        }
    }
}

@Composable
private fun EmptyAgendaCard() {
    SectionCard(Modifier.testTag(AGENDA_EMPTY_TAG)) {
        EmptyState(
            icon = Icons.Filled.EventBusy,
            title = stringResource(R.string.agenda_empty_title),
            body = stringResource(R.string.agenda_empty_state),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgendaEventsCard(events: List<AgendaEvent>, modifier: Modifier = Modifier) {
    SectionCard(modifier.testTag(AGENDA_EVENTS_TAG)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            event.displayTime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun TypeLauncherTheme(
    darkTheme: Boolean = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkScheme
        else -> lightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

internal class LauncherViewModel(
    private val app: Application,
    private val workPackages: Set<String>,
) : ViewModel() {
    private val dockedAppStore = DockedAppStore(app)
    private val appLaunchStatsStore = AppLaunchStatsStore(app)
    private val settingsLaunchGate = SettingsLaunchGate()
    private var installedApps: List<InstalledApp> = loadInstalledApps()
    private val _uiState = MutableStateFlow(
        LauncherUiState(
            filteredApps = installedApps.filterByName("", appLaunchStatsStore).markDocked(),
            dockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, "").markDocked(),
            agenda = loadAgendaState(),
        ),
    )
    val uiState: StateFlow<LauncherUiState> = _uiState.asStateFlow()

    fun setQuery(query: String) {
        _uiState.update { state -> state.copy(query = query) }
        refreshLists()
    }

    fun showAgenda() {
        _uiState.update { it.copy(screen = LauncherScreen.Agenda, agenda = loadAgendaState()) }
    }

    fun showHome() {
        _uiState.update { it.copy(screen = LauncherScreen.Home) }
    }

    fun refreshPermissionDrivenUi() {
        if (_uiState.value.screen == LauncherScreen.Agenda) {
            refreshAgenda()
        }
    }

    fun refreshAgenda() {
        _uiState.update { it.copy(agenda = loadAgendaState()) }
    }

    fun launchActiveApp() {
        val query = _uiState.value.query
        if (query.trim().equals(SETTINGS_QUERY, ignoreCase = true)) {
            startActivity(Intent(Settings.ACTION_SETTINGS).asLauncherTaskIntent())
            setQuery("")
            return
        }
        _uiState.value.filteredApps.firstOrNull()?.let(::launchApp)
    }

    fun launchApp(app: InstalledApp) {
        val component = app.launchIntent.component
        if (app.launchWithLauncherApps && component != null) {
            this.app.getSystemService<LauncherApps>()?.startMainActivity(component, app.user, null, null)
        } else {
            startActivity(app.launchIntent.asLauncherTaskIntent())
        }
        appLaunchStatsStore.recordLaunch(app.id)
        setQuery("")
    }

    fun openAppInfo(app: InstalledApp) {
        startActivity(app.appInfoIntent)
    }

    fun toggleDock(app: InstalledApp, maxDockedApps: Int) {
        if (app.isDocked) {
            dockedAppStore.undock(app.id)
        } else if (!dockedAppStore.dock(app.id, maxDockedApps)) {
            Toast.makeText(
                this.app,
                this.app.getString(R.string.docked_apps_limit_message, maxDockedApps),
                Toast.LENGTH_SHORT,
            ).show()
        }
        refreshLists()
    }

    fun resetRank(app: InstalledApp) {
        appLaunchStatsStore.resetLaunchCount(app.id)
        refreshLists()
    }

    private fun refreshLists() {
        val query = _uiState.value.query.trim()
        _uiState.update { state ->
            state.copy(
                filteredApps = installedApps.filterByName(query, appLaunchStatsStore).markDocked(),
                dockedApps = installedApps.filterDockedByName(dockedAppStore.dockedAppIds, query).markDocked(),
            )
        }
    }

    private fun startActivity(intent: Intent) {
        app.startActivity(intent.asLauncherTaskIntent())
    }

    private fun loadAgendaState(): AgendaUiState {
        if (!hasCalendarPermission()) {
            return AgendaUiState.PermissionRequired
        }
        val events = loadAgendaEvents()
        return if (events.isEmpty()) AgendaUiState.Empty else AgendaUiState.Events(events)
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun loadAgendaEvents(nowMillis: Long = System.currentTimeMillis()): List<AgendaEvent> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val utcTodayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val utcTomorrowStart = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val queryEnd = today.plusDays(AGENDA_LOOKAHEAD_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        val instanceUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startOfDay)
            ContentUris.appendId(this, queryEnd)
        }.build()
        val events = mutableListOf<AgendaEvent>()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )

        try {
            app.contentResolver.query(
                instanceUri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext()) {
                    events += AgendaEvent(
                        title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() }
                            ?: app.getString(R.string.agenda_event_untitled),
                        beginMillis = cursor.getLong(beginIndex),
                        endMillis = cursor.getLong(endIndex),
                        isAllDay = cursor.getInt(allDayIndex) == 1,
                        displayTime = "",
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }

        return AgendaEventOrganizer.forNow(
            events = events,
            nowMillis = nowMillis,
            utcTodayStartMillis = utcTodayStart,
            utcTomorrowStartMillis = utcTomorrowStart,
        ).map { event -> event.copy(displayTime = event.formatTime(app)) }
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val personalUser = Process.myUserHandle()
        return app.getSystemService<LauncherApps>()
            ?.profiles
            .orEmpty()
            .flatMap { user ->
                app.getSystemService<LauncherApps>()
                    ?.getActivityList(null, user)
                    .orEmpty()
                    .map { activity ->
                        InstalledApp(
                            name = activity.label.toString(),
                            packageName = activity.applicationInfo.packageName,
                            launchIntent = Intent.makeMainActivity(activity.componentName),
                            icon = activity.getIcon(0),
                            user = user,
                            isWorkApp = user != personalUser || activity.applicationInfo.packageName in workPackages,
                            launchWithLauncherApps = true,
                        )
                    }
            }
            .ifEmpty {
                app.packageManager.queryIntentActivities(launcherIntent, 0)
                    .map { resolveInfo ->
                        val activityInfo = resolveInfo.activityInfo
                        InstalledApp(
                            name = resolveInfo.loadLabel(app.packageManager).toString(),
                            packageName = activityInfo.packageName,
                            launchIntent = Intent.makeMainActivity(
                                ComponentName(activityInfo.packageName, activityInfo.name),
                            ),
                            icon = resolveInfo.loadIcon(app.packageManager),
                            user = personalUser,
                            isWorkApp = activityInfo.packageName in workPackages,
                            launchWithLauncherApps = false,
                        )
                    }
            }
            .distinctBy { launcherApp -> launcherApp.name.lowercase() to launcherApp.isWorkApp }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { launcherApp -> launcherApp.name })
    }

    private fun List<InstalledApp>.markDocked(): List<InstalledApp> =
        map { launcherApp ->
            val storedApp = installedApps.firstOrNull { installedApp -> installedApp.id == launcherApp.id } ?: launcherApp
            launcherApp.copy(
                isDocked = dockedAppStore.contains(launcherApp.id),
                isWorkApp = storedApp.isWorkApp,
            )
        }

    companion object {
        fun factory(app: Application, workPackages: Set<String>): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    LauncherViewModel(app, workPackages) as T
            }
    }
}

internal data class LauncherUiState(
    val screen: LauncherScreen = LauncherScreen.Home,
    val query: String = "",
    val filteredApps: List<InstalledApp> = emptyList(),
    val dockedApps: List<InstalledApp> = emptyList(),
    val agenda: AgendaUiState = AgendaUiState.PermissionRequired,
)

internal enum class LauncherScreen {
    Agenda,
    Home,
    ;

    val carouselPage: Int
        get() = carouselScreens.indexOf(this)

    companion object {
        val carouselScreens = entries.toList()

        fun fromCarouselPage(page: Int): LauncherScreen = carouselScreens[page]
    }
}

internal sealed interface AgendaUiState {
    data object PermissionRequired : AgendaUiState
    data object Empty : AgendaUiState
    data class Events(val events: List<AgendaEvent>) : AgendaUiState
}

internal data class InstalledApp(
    val name: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: Drawable?,
    val user: UserHandle,
    val isWorkApp: Boolean,
    val launchWithLauncherApps: Boolean,
    val isDocked: Boolean = false,
) {
    val id: String
        get() = "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

    val appInfoIntent: Intent
        get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .asLauncherTaskIntent()

    override fun toString(): String = name
}

internal data class AgendaEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val displayTime: String = "",
)

internal fun List<InstalledApp>.filterByName(query: String, appLaunchStatsStore: AppLaunchStatsStore): List<InstalledApp> =
    if (query.isEmpty()) {
        sortedWith(
            compareByDescending<InstalledApp> { app -> appLaunchStatsStore.launchCount(app.id) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
        )
    } else {
        filter { app -> app.name.contains(query, ignoreCase = true) }
    }

internal fun List<InstalledApp>.filterDockedByName(dockedAppIds: List<String>, query: String): List<InstalledApp> =
    filter { app -> app.id in dockedAppIds }
        .let { dockedApps ->
            if (query.isEmpty()) {
                dockedApps
            } else {
                dockedApps.filter { app -> app.name.contains(query, ignoreCase = true) }
            }
        }
        .sortedBy { app -> dockedAppIds.indexOf(app.id) }

internal fun Intent.asLauncherTaskIntent(): Intent =
    Intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

private fun AgendaEvent.formatTime(context: Context): String {
    if (isAllDay) {
        return context.getString(R.string.agenda_all_day_label)
    }
    val zone = ZoneId.systemDefault()
    val eventDay = Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    val flags = if (eventDay == today) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateRange(context, beginMillis, endMillis, flags).toString()
}

internal class DockedAppStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var dockedIds = sharedPreferences.getString(KEY_DOCKED_APP_IDS, "").orEmpty()
        .split(DOCKED_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())

    val dockedAppIds: List<String>
        get() = dockedIds.toList()

    fun contains(appId: String): Boolean = appId in dockedIds

    fun dock(appId: String, maxDockedApps: Int): Boolean {
        if (appId in dockedIds) {
            return true
        }
        if (dockedIds.size >= maxDockedApps) {
            return false
        }
        dockedIds.add(appId)
        save()
        return true
    }

    fun undock(appId: String) {
        if (dockedIds.remove(appId)) {
            save()
        }
    }

    private fun save() {
        sharedPreferences.edit()
            .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "docked_apps"
        const val KEY_DOCKED_APP_IDS = "docked_app_ids"
        const val DOCKED_APP_ID_SEPARATOR = "\n"
    }
}

internal class AppLaunchStatsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun launchCount(appId: String): Int =
        sharedPreferences.getInt(appId.toLaunchCountKey(), 0)

    fun recordLaunch(appId: String) {
        sharedPreferences.edit()
            .putInt(appId.toLaunchCountKey(), launchCount(appId) + 1)
            .apply()
    }

    fun resetLaunchCount(appId: String) {
        sharedPreferences.edit()
            .remove(appId.toLaunchCountKey())
            .apply()
    }

    private fun String.toLaunchCountKey(): String = "$KEY_LAUNCH_COUNT_PREFIX$this"

    private companion object {
        const val PREFERENCES_NAME = "app_launch_stats"
        const val KEY_LAUNCH_COUNT_PREFIX = "launch_count:"
    }
}

internal class SettingsLaunchGate {
    private var lastHandledEnterDownTime = NO_DOWN_TIME

    fun shouldLaunch(action: Int?, keyCode: Int?, repeatCount: Int, downTime: Long?): Boolean {
        if (action == null || keyCode == null) {
            lastHandledEnterDownTime = NO_DOWN_TIME
            return true
        }

        if (keyCode != KeyEvent.KEYCODE_ENTER) {
            lastHandledEnterDownTime = NO_DOWN_TIME
            return true
        }

        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) {
                    false
                } else {
                    lastHandledEnterDownTime = downTime ?: NO_DOWN_TIME
                    true
                }
            }
            KeyEvent.ACTION_UP -> {
                val wasHandledOnDown = downTime != null && downTime == lastHandledEnterDownTime
                if (wasHandledOnDown) {
                    lastHandledEnterDownTime = NO_DOWN_TIME
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }

    private companion object {
        const val NO_DOWN_TIME = Long.MIN_VALUE
    }
}

internal object AgendaEventOrganizer {
    fun forNow(
        events: List<AgendaEvent>,
        nowMillis: Long,
        utcTodayStartMillis: Long,
        utcTomorrowStartMillis: Long,
    ): List<AgendaEvent> {
        val todayAllDayEvents = events.asSequence()
            .filter { event ->
                event.isAllDay &&
                    event.beginMillis < utcTomorrowStartMillis &&
                    event.endMillis > utcTodayStartMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        val intersectingOrUpcoming = events.asSequence()
            .filter { event ->
                !event.isAllDay &&
                    event.endMillis >= nowMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        return todayAllDayEvents + intersectingOrUpcoming
    }
}

private val lightScheme = lightColorScheme(
    primary = Color(0xFF1E6FFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1B1C1F),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E6E),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE3E3E6),
)

internal const val HOME_SCREEN_TAG = "home_screen"
internal const val AGENDA_SCREEN_TAG = "agenda_screen"
internal const val SEARCH_FIELD_TAG = "search_field"
internal const val APPS_CARD_TAG = "apps_card"
internal const val APPS_LIST_TAG = "apps_list"
internal const val APP_ROW_TAG = "app_row"
internal const val APP_ICON_TAG = "app_icon"
internal const val WORK_APP_BADGE_TAG = "work_app_badge"
internal const val APP_INFO_ACTION_TAG = "app_info_action"
internal const val TOGGLE_DOCK_ACTION_TAG = "toggle_dock_action"
internal const val RESET_RANK_ACTION_TAG = "reset_rank_action"
internal const val DOCK_CARD_TAG = "dock_card"
internal const val DOCK_LIST_TAG = "dock_list"
internal const val DOCK_HINT_TAG = "dock_hint"
internal const val DOCK_APP_TAG = "dock_app"
internal const val AGENDA_PERMISSION_TAG = "agenda_permission"
internal const val AGENDA_EMPTY_TAG = "agenda_empty"
internal const val AGENDA_EVENTS_TAG = "agenda_events"
private const val SETTINGS_QUERY = "settings"
private const val MIN_DOCKED_APPS = 1
private const val DOCK_APP_ICON_SIZE_DP = 56
private const val AGENDA_LOOKAHEAD_DAYS = 7L

@Preview(name = "Home empty")
@Composable
private fun HomeEmptyPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(filteredApps = emptyList()),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onShowAgenda = {},
            onShowHome = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Home running", fontScale = 1.3f)
@Composable
private fun HomeRunningLargeFontPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(
                filteredApps = previewApps,
                dockedApps = previewApps.take(2).map { it.copy(isDocked = true) },
            ),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onShowAgenda = {},
            onShowHome = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda permission", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AgendaPermissionDarkPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(screen = LauncherScreen.Agenda, agenda = AgendaUiState.PermissionRequired),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onShowAgenda = {},
            onShowHome = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda empty RTL", locale = "ar")
@Composable
private fun AgendaEmptyRtlPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(screen = LauncherScreen.Agenda, agenda = AgendaUiState.Empty),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onShowAgenda = {},
            onShowHome = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda events")
@Composable
private fun AgendaEventsPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(
                screen = LauncherScreen.Agenda,
                agenda = AgendaUiState.Events(
                    listOf(
                        AgendaEvent("Planning", 0L, 1L, false, "10:00 AM"),
                        AgendaEvent("Launch day", 0L, 1L, true, "All day"),
                    ),
                ),
            ),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onShowAgenda = {},
            onShowHome = {},
            onRequestCalendarPermission = {},
        )
    }
}

private val previewApps = listOf(
    InstalledApp(
        name = "Calendar",
        packageName = "app.preview.calendar",
        launchIntent = Intent(),
        icon = null,
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    ),
    InstalledApp(
        name = "Work Calendar",
        packageName = "app.preview.workcalendar",
        launchIntent = Intent(),
        icon = null,
        user = Process.myUserHandle(),
        isWorkApp = true,
        launchWithLauncherApps = false,
    ),
)
