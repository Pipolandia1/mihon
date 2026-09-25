package eu.kanade.tachiyomi.ui.start

import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.start.StartRoutes.Overlay
import eu.kanade.tachiyomi.ui.start.StartRoutes.Route
import eu.kanade.tachiyomi.ui.start.StartRoutes.TabId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every way into a tab still arrives somewhere after Updates and Browse left the bottom bar
 * (docs/HOME_PLAN.md, H1). These are the requests notifications, launcher shortcuts and screens
 * inside the app send.
 */
class StartRoutesTest {

    @Test
    fun `the new chapters notification opens Updates over Mais`() {
        assertEquals(Route(TabId.MORE, Overlay.Updates), StartRoutes.route(HomeScreen.Tab.Updates))
    }

    @Test
    fun `the extension updates notification opens Browse on its extensions over Mais`() {
        assertEquals(
            Route(TabId.MORE, Overlay.Browse(toExtensions = true)),
            StartRoutes.route(HomeScreen.Tab.Browse(toExtensions = true)),
        )
    }

    @Test
    fun `the sources shortcut opens Browse over Mais`() {
        assertEquals(
            Route(TabId.MORE, Overlay.Browse(toExtensions = false)),
            StartRoutes.route(HomeScreen.Tab.Browse()),
        )
    }

    @Test
    fun `the tabs still in the bar are selected as before`() {
        assertEquals(Route(TabId.LIBRARY), StartRoutes.route(HomeScreen.Tab.Library()))
        assertEquals(Route(TabId.HISTORY), StartRoutes.route(HomeScreen.Tab.History))
        assertEquals(Route(TabId.MORE), StartRoutes.route(HomeScreen.Tab.More(toDownloads = false)))
    }

    @Test
    fun `opening a manga and the download queue still push their screens`() {
        assertEquals(Route(TabId.LIBRARY, Overlay.Manga(42L)), StartRoutes.route(HomeScreen.Tab.Library(42L)))
        assertEquals(
            Route(TabId.MORE, Overlay.Downloads),
            StartRoutes.route(HomeScreen.Tab.More(toDownloads = true)),
        )
    }
}
