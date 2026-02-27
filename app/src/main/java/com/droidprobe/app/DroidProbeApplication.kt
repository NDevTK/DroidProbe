package com.droidprobe.app

import android.app.Application
import com.droidprobe.app.di.AppModule
import com.droidprobe.app.test.TestFileProviderInit

class DroidProbeApplication : Application() {
    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
        TestFileProviderInit.ensureSampleFile(this)
    }
}
