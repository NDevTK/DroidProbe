package com.droidprobe.app.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable
    data object AppList : Screen

    @Serializable
    data class Analysis(val packageName: String) : Screen

    @Serializable
    data class ContentProviderExplorer(val packageName: String) : Screen

    @Serializable
    data class IntentBuilder(val packageName: String) : Screen

    @Serializable
    data class FileProviderBrowser(val packageName: String) : Screen

    @Serializable
    data class GoogleApiExplorer(val packageName: String, val rootUrl: String) : Screen
}
