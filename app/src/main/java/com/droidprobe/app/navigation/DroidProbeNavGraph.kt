package com.droidprobe.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.droidprobe.app.ui.analysis.AnalysisScreen
import com.droidprobe.app.ui.fileprovider.FileProviderScreen
import com.droidprobe.app.ui.intents.IntentBuilderScreen
import com.droidprobe.app.ui.providers.ContentProviderScreen
import com.droidprobe.app.ui.googleapi.GoogleApiExplorerScreen
import com.droidprobe.app.ui.scanner.AppListScreen

@Composable
fun DroidProbeNavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.AppList
    ) {
        composable<Screen.AppList> {
            AppListScreen(
                onAppClick = { packageName ->
                    navController.navigate(Screen.Analysis(packageName))
                }
            )
        }

        composable<Screen.Analysis> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.Analysis>()
            AnalysisScreen(
                packageName = route.packageName,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToContentProvider = { pkg ->
                    navController.navigate(Screen.ContentProviderExplorer(pkg))
                },
                onNavigateToIntentBuilder = { pkg ->
                    navController.navigate(Screen.IntentBuilder(pkg))
                },
                onNavigateToFileProvider = { pkg ->
                    navController.navigate(Screen.FileProviderBrowser(pkg))
                },
                onNavigateToGoogleApi = { pkg, rootUrl ->
                    navController.navigate(Screen.GoogleApiExplorer(pkg, rootUrl))
                }
            )
        }

        composable<Screen.ContentProviderExplorer> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.ContentProviderExplorer>()
            ContentProviderScreen(
                packageName = route.packageName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<Screen.IntentBuilder> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.IntentBuilder>()
            IntentBuilderScreen(
                packageName = route.packageName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<Screen.FileProviderBrowser> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.FileProviderBrowser>()
            FileProviderScreen(
                packageName = route.packageName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<Screen.GoogleApiExplorer> { backStackEntry ->
            val route = backStackEntry.toRoute<Screen.GoogleApiExplorer>()
            GoogleApiExplorerScreen(
                packageName = route.packageName,
                rootUrl = route.rootUrl,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
