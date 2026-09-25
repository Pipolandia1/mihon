package eu.kanade.tachiyomi.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import mihon.app.di.AppGraph
import mihon.app.di.appGraph

/**
 * The bottom bar since amendment H1, and the Voyager side of [StartRoutes].
 *
 * Updates and Browse left the bar for Mais; their counts went with them, as a badge on each item
 * and their sum on the Mais tab.
 */
object StartNavigation {

    val TABS: List<Tab> = listOf(StartTab, LibraryTab, HistoryTab, MoreTab)

    /** The tab to select for a request, and the screen to push over it. */
    class Destination(val tab: Tab, val screen: Screen?)

    fun destinationOf(request: HomeScreen.Tab): Destination {
        val route = StartRoutes.route(request)
        val tab = when (route.tab) {
            StartRoutes.TabId.START -> StartTab
            StartRoutes.TabId.LIBRARY -> LibraryTab
            StartRoutes.TabId.HISTORY -> HistoryTab
            StartRoutes.TabId.MORE -> MoreTab
        }
        val screen = when (val overlay = route.overlay) {
            StartRoutes.Overlay.None -> null
            is StartRoutes.Overlay.Manga -> MangaScreen(overlay.mangaId)
            StartRoutes.Overlay.Downloads -> DownloadQueueScreen
            StartRoutes.Overlay.Updates -> MoreUpdatesScreen()
            is StartRoutes.Overlay.Browse -> {
                if (overlay.toExtensions) BrowseTab.showExtension()
                MoreBrowseScreen()
            }
        }
        return Destination(tab, screen)
    }

    /** New chapters and extension updates, each only when its badge is switched on. */
    @Immutable
    data class MoreBadges(val updates: Int = 0, val extensions: Int = 0) {
        val total: Int get() = updates + extensions
    }

    fun moreBadges(graph: AppGraph): Flow<MoreBadges> = combine(
        graph.libraryPreferences.newShowUpdatesCount.changes(),
        graph.libraryPreferences.newUpdatesCount.changes(),
        graph.sourcePreferences.extensionUpdatesCount.changes(),
    ) { showUpdates, updates, extensions ->
        MoreBadges(updates = if (showUpdates) updates else 0, extensions = extensions)
    }

    @Composable
    fun rememberMoreBadges(): MoreBadges {
        val context = LocalContext.current
        val badges by produceState(MoreBadges()) {
            moreBadges(context.appGraph).collectLatest { value = it }
        }
        return badges
    }
}
