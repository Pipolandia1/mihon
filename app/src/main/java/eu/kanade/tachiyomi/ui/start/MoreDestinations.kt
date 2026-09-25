package eu.kanade.tachiyomi.ui.start

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab

/**
 * Updates, opened from Mais since it left the bottom bar (amendment H1). The same screen the tab
 * showed, with an up button.
 */
class MoreUpdatesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        UpdatesTab.Content(navigateUp = navigator::pop)
    }
}

/** Browse, opened from Mais since it left the bottom bar (amendment H1). */
class MoreBrowseScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        BrowseTab.Content(navigateUp = navigator::pop)
    }
}
