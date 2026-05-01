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

        private val firstVirtualCarouselPage =
            carouselPageCount / 2 - (carouselPageCount / 2 % carouselScreens.size)

        fun initialCarouselPage(screen: LauncherScreen): Int =
            firstVirtualCarouselPage + screen.carouselPage

        fun closestCarouselPage(currentPage: Int, screen: LauncherScreen): Int {
            val currentCarouselPage = Math.floorMod(currentPage, carouselScreens.size)
            val forwardDelta = Math.floorMod(screen.carouselPage - currentCarouselPage, carouselScreens.size)
            val backwardDelta = forwardDelta - carouselScreens.size
            val delta = when {
                forwardDelta == 0 -> 0
                forwardDelta < abs(backwardDelta) -> forwardDelta
                forwardDelta > abs(backwardDelta) -> backwardDelta
                screen.carouselPage >= currentCarouselPage -> forwardDelta
                else -> backwardDelta
            }
            return currentPage + delta
        }

        fun fromCarouselPage(page: Int): LauncherScreen =
            carouselScreens[Math.floorMod(page, carouselScreens.size)]
    }
}
