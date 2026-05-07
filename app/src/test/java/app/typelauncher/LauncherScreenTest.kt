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
}
