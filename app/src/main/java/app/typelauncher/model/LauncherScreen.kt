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

        fun fromCarouselPage(page: Int, isAgendaEnabled: Boolean = true): LauncherScreen {
            val visibleScreens = visibleCarouselScreens(isAgendaEnabled)
            return visibleScreens[Math.floorMod(page, visibleScreens.size)]
        }
    }
}
