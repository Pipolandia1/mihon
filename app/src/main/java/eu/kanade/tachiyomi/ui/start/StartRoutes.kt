package eu.kanade.tachiyomi.ui.start

import eu.kanade.tachiyomi.ui.home.HomeScreen

/**
 * Where each request to open a tab lands since the bottom bar became Início, Biblioteca,
 * Histórico and Mais (amendment H1).
 *
 * Notifications, launcher shortcuts and screens inside the app still ask for the Updates and
 * Browse tabs by name. Those are now screens opened from Mais, so the request is answered with
 * the Mais tab and, on top of it, the screen. Pure, so every request is a JVM test.
 */
object StartRoutes {

    enum class TabId { START, LIBRARY, HISTORY, MORE }

    /** What is pushed over the tab, if anything. */
    sealed interface Overlay {
        data object None : Overlay
        data class Manga(val mangaId: Long) : Overlay
        data object Downloads : Overlay
        data object Updates : Overlay
        data class Browse(val toExtensions: Boolean) : Overlay
    }

    data class Route(val tab: TabId, val overlay: Overlay = Overlay.None)

    fun route(request: HomeScreen.Tab): Route = when (request) {
        is HomeScreen.Tab.Library -> Route(
            TabId.LIBRARY,
            request.mangaIdToOpen?.let { Overlay.Manga(it) } ?: Overlay.None,
        )
        HomeScreen.Tab.Updates -> Route(TabId.MORE, Overlay.Updates)
        HomeScreen.Tab.History -> Route(TabId.HISTORY)
        is HomeScreen.Tab.Browse -> Route(TabId.MORE, Overlay.Browse(request.toExtensions))
        is HomeScreen.Tab.More -> Route(TabId.MORE, if (request.toDownloads) Overlay.Downloads else Overlay.None)
    }
}
