package app.typelauncher

import kotlin.math.abs

internal enum class LauncherScreen {
    Home,
    Widgets,
    Agenda,
    ;

    val carouselPage: Int
        get() = carouselScreens.indexOf(this)

    companion object {
        val carouselScreens = entries.toList()
        const val carouselPageCount = Int.MAX_VALUE

        private fun visibleCarouselScreens(isAgendaEnabled: Boolean): List<LauncherScreen> =
            if (isAgendaEnabled) carouselScreens else listOf(Home, Widgets)

        fun visibleCarouselPages(
            widgetPageCount: Int,
            isAgendaEnabled: Boolean,
        ): List<LauncherPage> {
            val widgetPages = List(widgetPageCount.coerceAtLeast(1)) { index ->
                LauncherPage(Widgets, widgetPageIndex = index)
            }
            return if (isAgendaEnabled) {
                listOf(LauncherPage(Home)) + widgetPages + LauncherPage(Agenda)
            } else {
                listOf(LauncherPage(Home)) + widgetPages
            }
        }

        private fun firstVirtualCarouselPage(screenCount: Int): Int =
            carouselPageCount / 2 - (carouselPageCount / 2 % screenCount)

        private fun LauncherScreen.visibleCarouselPage(isAgendaEnabled: Boolean): Int {
            val visibleScreens = visibleCarouselScreens(isAgendaEnabled)
            return visibleScreens.indexOf(this).takeIf { index -> index >= 0 } ?: Home.carouselPage
        }

        fun initialCarouselPage(screen: LauncherScreen, isAgendaEnabled: Boolean = true): Int {
            val visibleScreens = visibleCarouselScreens(isAgendaEnabled)
            val visibleScreen = if (screen in visibleScreens) screen else Home
            return firstVirtualCarouselPage(visibleScreens.size) + visibleScreens.indexOf(visibleScreen)
        }

        fun initialCarouselPage(
            page: LauncherPage,
            widgetPageCount: Int,
            isAgendaEnabled: Boolean = true,
        ): Int {
            val visiblePages = visibleCarouselPages(widgetPageCount, isAgendaEnabled)
            val visiblePage = page.visibleCarouselPage(visiblePages)
            return firstVirtualCarouselPage(visiblePages.size) + visiblePages.indexOf(visiblePage)
        }

        fun closestCarouselPage(currentPage: Int, screen: LauncherScreen, isAgendaEnabled: Boolean = true): Int {
            val visibleScreens = visibleCarouselScreens(isAgendaEnabled)
            val screenCount = visibleScreens.size
            val currentCarouselPage = Math.floorMod(currentPage, screenCount)
            val targetCarouselPage = screen.visibleCarouselPage(isAgendaEnabled)
            val forwardDelta = Math.floorMod(targetCarouselPage - currentCarouselPage, screenCount)
            val backwardDelta = forwardDelta - screenCount
            val delta = when {
                forwardDelta == 0 -> 0
                forwardDelta < abs(backwardDelta) -> forwardDelta
                forwardDelta > abs(backwardDelta) -> backwardDelta
                targetCarouselPage >= currentCarouselPage -> forwardDelta
                else -> backwardDelta
            }
            return currentPage + delta
        }

        fun closestCarouselPage(
            currentPage: Int,
            page: LauncherPage,
            widgetPageCount: Int,
            isAgendaEnabled: Boolean = true,
        ): Int {
            val visiblePages = visibleCarouselPages(widgetPageCount, isAgendaEnabled)
            val screenCount = visiblePages.size
            val currentCarouselPage = Math.floorMod(currentPage, screenCount)
            val targetCarouselPage = visiblePages.indexOf(page.visibleCarouselPage(visiblePages))
            val forwardDelta = Math.floorMod(targetCarouselPage - currentCarouselPage, screenCount)
            val backwardDelta = forwardDelta - screenCount
            val delta = when {
                forwardDelta == 0 -> 0
                forwardDelta < abs(backwardDelta) -> forwardDelta
                forwardDelta > abs(backwardDelta) -> backwardDelta
                targetCarouselPage >= currentCarouselPage -> forwardDelta
                else -> backwardDelta
            }
            return currentPage + delta
        }

        fun reanchoredCarouselPage(
            currentPage: Int,
            oldWidgetPageCount: Int,
            newWidgetPageCount: Int,
            oldIsAgendaEnabled: Boolean,
            newIsAgendaEnabled: Boolean,
        ): Int {
            val oldPage = fromCarouselPage(
                page = currentPage,
                widgetPageCount = oldWidgetPageCount,
                isAgendaEnabled = oldIsAgendaEnabled,
            )
            return closestCarouselPage(
                currentPage = currentPage,
                page = oldPage,
                widgetPageCount = newWidgetPageCount,
                isAgendaEnabled = newIsAgendaEnabled,
            )
        }

        fun fromCarouselPage(page: Int, isAgendaEnabled: Boolean = true): LauncherScreen {
            val visibleScreens = visibleCarouselScreens(isAgendaEnabled)
            return visibleScreens[Math.floorMod(page, visibleScreens.size)]
        }

        fun fromCarouselPage(
            page: Int,
            widgetPageCount: Int,
            isAgendaEnabled: Boolean = true,
        ): LauncherPage {
            val visiblePages = visibleCarouselPages(widgetPageCount, isAgendaEnabled)
            return visiblePages[Math.floorMod(page, visiblePages.size)]
        }
    }
}

internal data class LauncherPage(
    val screen: LauncherScreen,
    val widgetPageIndex: Int = 0,
) {
    val clampedWidgetPageIndex: Int
        get() = widgetPageIndex.coerceAtLeast(0)
}

// The user-facing carousel page identity. Unlike LauncherPage (a flat
// (screen, widgetPageIndex) pair), the sealed shape makes "Home with widget
// page index 1" unrepresentable, so a leftover widget index can't poison
// equality checks the way it did before PR #294. State stores this; readers
// pattern-match on it when the screen kind matters; the carousel goes
// through `toLauncherPage()` to talk to the existing visible-page math.
internal sealed class LauncherDestination {
    data object Home : LauncherDestination()
    data object Agenda : LauncherDestination()
    data class Widgets(val pageIndex: Int = 0) : LauncherDestination()

    val screen: LauncherScreen
        get() = when (this) {
            Home -> LauncherScreen.Home
            Agenda -> LauncherScreen.Agenda
            is Widgets -> LauncherScreen.Widgets
        }

    fun toLauncherPage(): LauncherPage = when (this) {
        Home -> LauncherPage(LauncherScreen.Home)
        Agenda -> LauncherPage(LauncherScreen.Agenda)
        is Widgets -> LauncherPage(LauncherScreen.Widgets, pageIndex)
    }
}

internal fun LauncherPage.toDestination(): LauncherDestination = when (screen) {
    LauncherScreen.Home -> LauncherDestination.Home
    LauncherScreen.Agenda -> LauncherDestination.Agenda
    LauncherScreen.Widgets -> LauncherDestination.Widgets(clampedWidgetPageIndex)
}

private fun LauncherPage.visibleCarouselPage(visiblePages: List<LauncherPage>): LauncherPage {
    if (screen != LauncherScreen.Widgets) {
        return visiblePages.firstOrNull { page -> page.screen == screen } ?: LauncherPage(LauncherScreen.Home)
    }
    return visiblePages.firstOrNull {
        it.screen == LauncherScreen.Widgets && it.widgetPageIndex == clampedWidgetPageIndex
    } ?: visiblePages.lastOrNull { it.screen == LauncherScreen.Widgets } ?: LauncherPage(LauncherScreen.Home)
}
