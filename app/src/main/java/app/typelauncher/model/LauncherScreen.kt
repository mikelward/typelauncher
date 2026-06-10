package app.typelauncher

import kotlin.math.abs

internal enum class LauncherScreen {
    Home,
    Widgets,
    ;

    val carouselPage: Int
        get() = carouselScreens.indexOf(this)

    companion object {
        val carouselScreens = entries.toList()
        const val carouselPageCount = Int.MAX_VALUE

        fun visibleCarouselPages(widgetPageCount: Int): List<LauncherPage> {
            val widgetPages = List(widgetPageCount.coerceAtLeast(1)) { index ->
                LauncherPage(Widgets, widgetPageIndex = index)
            }
            return listOf(LauncherPage(Home)) + widgetPages
        }

        private fun firstVirtualCarouselPage(screenCount: Int): Int =
            carouselPageCount / 2 - (carouselPageCount / 2 % screenCount)

        private fun LauncherScreen.visibleCarouselPage(): Int =
            carouselScreens.indexOf(this).takeIf { index -> index >= 0 } ?: Home.carouselPage

        fun initialCarouselPage(screen: LauncherScreen): Int =
            firstVirtualCarouselPage(carouselScreens.size) + carouselScreens.indexOf(screen)

        fun initialCarouselPage(
            page: LauncherPage,
            widgetPageCount: Int,
        ): Int {
            val visiblePages = visibleCarouselPages(widgetPageCount)
            val visiblePage = page.visibleCarouselPage(visiblePages)
            return firstVirtualCarouselPage(visiblePages.size) + visiblePages.indexOf(visiblePage)
        }

        fun closestCarouselPage(currentPage: Int, screen: LauncherScreen): Int {
            val screenCount = carouselScreens.size
            val currentCarouselPage = Math.floorMod(currentPage, screenCount)
            val targetCarouselPage = screen.visibleCarouselPage()
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
        ): Int {
            val visiblePages = visibleCarouselPages(widgetPageCount)
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
        ): Int {
            val oldPage = fromCarouselPage(page = currentPage, widgetPageCount = oldWidgetPageCount)
            return closestCarouselPage(
                currentPage = currentPage,
                page = oldPage,
                widgetPageCount = newWidgetPageCount,
            )
        }

        fun fromCarouselPage(page: Int): LauncherScreen =
            carouselScreens[Math.floorMod(page, carouselScreens.size)]

        fun fromCarouselPage(
            page: Int,
            widgetPageCount: Int,
        ): LauncherPage {
            val visiblePages = visibleCarouselPages(widgetPageCount)
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

/**
 * Stable composition key for a carousel destination. The carousel `key`s its
 * page slots with this so the same destination is never composed twice (with
 * two visible pages the -1 and +1 slots alias to one destination) and so a
 * page's state follows the destination across slots and full wraparound
 * loops rather than being tied to a virtual page index.
 */
internal fun LauncherPage.carouselContentKey(): String = when (screen) {
    LauncherScreen.Home -> "home"
    LauncherScreen.Widgets -> "widgets:$clampedWidgetPageIndex"
}

// The user-facing carousel page identity. Unlike LauncherPage (a flat
// (screen, widgetPageIndex) pair), the sealed shape makes "Home with widget
// page index 1" unrepresentable, so a leftover widget index can't poison
// equality checks the way it did before PR #294. State stores this; readers
// pattern-match on it when the screen kind matters; the carousel goes
// through `toLauncherPage()` to talk to the existing visible-page math.
internal sealed class LauncherDestination {
    data object Home : LauncherDestination()
    data class Widgets(val pageIndex: Int = 0) : LauncherDestination()

    val screen: LauncherScreen
        get() = when (this) {
            Home -> LauncherScreen.Home
            is Widgets -> LauncherScreen.Widgets
        }

    fun toLauncherPage(): LauncherPage = when (this) {
        Home -> LauncherPage(LauncherScreen.Home)
        is Widgets -> LauncherPage(LauncherScreen.Widgets, pageIndex)
    }
}

internal fun LauncherPage.toDestination(): LauncherDestination = when (screen) {
    LauncherScreen.Home -> LauncherDestination.Home
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
