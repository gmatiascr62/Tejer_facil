package com.tejerfacil.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tejerfacil.app.AppContainer
import com.tejerfacil.app.ui.crop.CropEditorScreen
import com.tejerfacil.app.ui.gallery.GalleryScreen
import com.tejerfacil.app.ui.importer.ImportScreen
import com.tejerfacil.app.ui.library.LibraryScreen
import com.tejerfacil.app.ui.reader.ReaderScreen
import com.tejerfacil.app.ui.rows.RowEditorScreen
import com.tejerfacil.app.ui.settings.SettingsScreen
import com.tejerfacil.app.ui.symbols.SymbolEditorScreen

object Routes {
    const val LIBRARY = "biblioteca"
    const val IMPORT = "importar"
    const val SETTINGS = "ajustes"
    fun gallery(patternId: Long) = "galeria/$patternId"
    fun reader(diagramId: Long) = "lector/$diagramId"
    fun crop(patternId: Long, page: Int) = "recortes/$patternId/$page"
    fun rows(diagramId: Long) = "filas/$diagramId"
    fun symbols(diagramId: Long) = "simbolos/$diagramId"
}

@Composable
fun AppNavHost(container: AppContainer, importRequests: StateFlow<Long>) {
    val nav = rememberNavController()
    // PDF recibido desde otra app: ir a la pantalla de importación.
    val request by importRequests.collectAsState()
    LaunchedEffect(request) {
        if (request != 0L) nav.navigate(Routes.IMPORT) { launchSingleTop = true }
    }
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                container = container,
                onOpenPattern = { nav.navigate(Routes.gallery(it)) },
                onImportStarted = { nav.navigate(Routes.IMPORT) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                container = container,
                onDone = { patternId ->
                    nav.popBackStack(Routes.LIBRARY, inclusive = false)
                    if (patternId != null) nav.navigate(Routes.gallery(patternId))
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }) }
        composable(
            "galeria/{patternId}",
            arguments = listOf(navArgument("patternId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments!!.getLong("patternId")
            GalleryScreen(
                container = container, patternId = id,
                onOpenDiagram = { nav.navigate(Routes.reader(it)) },
                onEditCrops = { page -> nav.navigate(Routes.crop(id, page)) },
                onEditSymbols = { nav.navigate(Routes.symbols(it)) },
                onOpenPattern = { nav.navigate(Routes.gallery(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "lector/{diagramId}",
            arguments = listOf(navArgument("diagramId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments!!.getLong("diagramId")
            ReaderScreen(
                container = container, diagramId = id,
                onEditRows = { nav.navigate(Routes.rows(id)) },
                onEditSymbols = { did -> nav.navigate(Routes.symbols(did)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "recortes/{patternId}/{page}",
            arguments = listOf(
                navArgument("patternId") { type = NavType.LongType },
                navArgument("page") { type = NavType.IntType },
            ),
        ) { entry ->
            CropEditorScreen(
                container = container,
                patternId = entry.arguments!!.getLong("patternId"),
                initialPage = entry.arguments!!.getInt("page"),
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "simbolos/{diagramId}",
            arguments = listOf(navArgument("diagramId") { type = NavType.LongType }),
        ) { entry ->
            SymbolEditorScreen(container, entry.arguments!!.getLong("diagramId"), onBack = { nav.popBackStack() })
        }
        composable(
            "filas/{diagramId}",
            arguments = listOf(navArgument("diagramId") { type = NavType.LongType }),
        ) { entry ->
            RowEditorScreen(container, entry.arguments!!.getLong("diagramId"), onBack = { nav.popBackStack() })
        }
    }
}
