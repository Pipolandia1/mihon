package eu.kanade.tachiyomi.ui.start

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.start.StartScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.readingstats.ComicLibraryScreen
import eu.kanade.tachiyomi.ui.readingstats.ComicOnlineSettingsScreen
import eu.kanade.tachiyomi.ui.start.sources.RecommendationSourcesScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Home
import mihon.icons.materialsymbols.roundedfilled.Home
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Início, the tab the app opens on (amendment H1): what to read now.
 *
 * H1 gives it the one thing every later step builds on, continuing where the reader left off;
 * the rest of the plan (docs/HOME_PLAN.md) adds its blocks here.
 */
data object StartTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val icon = if (isSelected) MaterialSymbols.RoundedFilled.Home else MaterialSymbols.Rounded.Home
            return TabOptions(
                index = StartConfig.TAB_INDEX,
                title = stringResource(MR.strings.label_start),
                icon = rememberVectorPainter(icon),
            )
        }

    /** Browse used to open global search when tapped again; that shortcut lives here now. */
    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val tabNavigator = LocalTabNavigator.current
        val viewModel = metroViewModel<StartViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        StartScreen(
            state = state,
            onClickSearch = { navigator.push(GlobalSearchScreen()) },
            onOpenChapter = { mangaId, chapterId ->
                context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
            },
            onOpenManga = { navigator.push(MangaScreen(it)) },
            onOpenLibrary = { tabNavigator.current = LibraryTab },
            onFeedback = viewModel::setFeedback,
            onUndoFeedback = viewModel::undoFeedback,
            onOpenSources = { navigator.push(RecommendationSourcesScreen()) },
            onEntityFeedback = viewModel::setEntityFeedback,
            onUndoEntityFeedback = viewModel::undoEntityFeedback,
            onOpenMetronSettings = { navigator.push(ComicOnlineSettingsScreen()) },
            onOpenComicLibrary = { navigator.push(ComicLibraryScreen()) },
        )

        // The splash screen waits for the first tab to have something to show.
        LaunchedEffect(state.loading) {
            if (!state.loading) (context as? MainActivity)?.ready = true
        }
    }
}
