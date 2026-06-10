package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherScreenTest {
    @Test
    fun carouselWrapsBetweenHomeAndWidgets() {
        val homePage = LauncherScreen.initialCarouselPage(screen = LauncherScreen.Home)

        assertEquals(LauncherScreen.Home, LauncherScreen.fromCarouselPage(homePage))
        assertEquals(LauncherScreen.Widgets, LauncherScreen.fromCarouselPage(homePage + 1))
        assertEquals(LauncherScreen.Home, LauncherScreen.fromCarouselPage(homePage + 2))
        assertEquals(LauncherScreen.Widgets, LauncherScreen.fromCarouselPage(homePage - 1))
    }

    @Test
    fun multipleWidgetPagesAreAdjacentInCarousel() {
        val homePage = LauncherScreen.initialCarouselPage(
            page = LauncherPage(LauncherScreen.Home),
            widgetPageCount = 2,
        )

        assertEquals(LauncherPage(LauncherScreen.Home), LauncherScreen.fromCarouselPage(homePage, 2))
        assertEquals(LauncherPage(LauncherScreen.Widgets, 0), LauncherScreen.fromCarouselPage(homePage + 1, 2))
        assertEquals(LauncherPage(LauncherScreen.Widgets, 1), LauncherScreen.fromCarouselPage(homePage + 2, 2))
        assertEquals(LauncherPage(LauncherScreen.Home), LauncherScreen.fromCarouselPage(homePage + 3, 2))
    }

    @Test
    fun widgetPageCountChangeReanchorsCurrentPageBeforeFindingNewWidgetPage() {
        val oldWidgetPage = LauncherScreen.initialCarouselPage(
            page = LauncherPage(LauncherScreen.Widgets, 0),
            widgetPageCount = 1,
        )

        val reanchoredPage = LauncherScreen.reanchoredCarouselPage(
            currentPage = oldWidgetPage,
            oldWidgetPageCount = 1,
            newWidgetPageCount = 2,
        )

        assertEquals(LauncherPage(LauncherScreen.Widgets, 0), LauncherScreen.fromCarouselPage(reanchoredPage, 2))
        assertEquals(
            reanchoredPage + 1,
            LauncherScreen.closestCarouselPage(
                currentPage = reanchoredPage,
                page = LauncherPage(LauncherScreen.Widgets, 1),
                widgetPageCount = 2,
            ),
        )
    }
}
