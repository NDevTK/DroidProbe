package com.droidprobe.app

import android.app.Application
import com.droidprobe.app.di.AppModule

class DroidProbeApplication : Application() {
    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
    }
}
