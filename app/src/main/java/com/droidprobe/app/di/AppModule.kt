package com.droidprobe.app.di

import android.content.Context
import androidx.room.Room
import com.droidprobe.app.analysis.dex.DexAnalyzer
import com.droidprobe.app.analysis.manifest.ManifestAnalyzer
import com.droidprobe.app.data.db.DroidProbeDatabase
import com.droidprobe.app.data.repository.AnalysisRepository
import com.droidprobe.app.data.repository.AppRepository
import com.droidprobe.app.scanner.PackageScanner

class AppModule(private val appContext: Context) {

    val database: DroidProbeDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            DroidProbeDatabase::class.java,
            "droidprobe.db"
        ).build()
    }

    val packageScanner: PackageScanner by lazy {
        PackageScanner(appContext.packageManager)
    }

    val manifestAnalyzer: ManifestAnalyzer by lazy {
        ManifestAnalyzer(appContext.packageManager)
    }

    val dexAnalyzer: DexAnalyzer by lazy {
        DexAnalyzer()
    }

    val appRepository: AppRepository by lazy {
        AppRepository(packageScanner)
    }

    val analysisRepository: AnalysisRepository by lazy {
        AnalysisRepository(
            manifestAnalyzer = manifestAnalyzer,
            dexAnalyzer = dexAnalyzer,
            dao = database.analysisResultDao()
        )
    }
}
