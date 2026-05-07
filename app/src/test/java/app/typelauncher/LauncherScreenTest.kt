package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherScreenTest {
    @Test
    fun disabledAgendaCarouselWrapsBetweenHomeAndWidgets() {
        val homePage = LauncherScreen.initialCarouselPage(
            screen = LauncherScreen.Home,
            isAgendaEnabled = false,
        )

        assertEquals(LauncherScreen.Home, LauncherScreen.fromCarouselPage(homePage, isAgendaEnabled = false))
        assertEquals(LauncherScreen.Widgets, LauncherScreen.fromCarouselPage(homePage + 1, isAgendaEnabled = false))
        assertEquals(LauncherScreen.Home, LauncherScreen.fromCarouselPage(homePage + 2, isAgendaEnabled = false))
        assertEquals(LauncherScreen.Widgets, LauncherScreen.fromCarouselPage(homePage - 1, isAgendaEnabled = false))
    }

    @Test
    fun disabledAgendaInitialPageFallsBackToHome() {
        val homePage = LauncherScreen.initialCarouselPage(
            screen = LauncherScreen.Home,
            isAgendaEnabled = false,
        )

        assertEquals(
            homePage,
            LauncherScreen.initialCarouselPage(
                screen = LauncherScreen.Agenda,
                isAgendaEnabled = false,
            ),
        )
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
        assertEquals(LauncherPage(LauncherScreen.Agenda), LauncherScreen.fromCarouselPage(homePage + 3, 2))
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
            oldIsAgendaEnabled = true,
            newIsAgendaEnabled = true,
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
