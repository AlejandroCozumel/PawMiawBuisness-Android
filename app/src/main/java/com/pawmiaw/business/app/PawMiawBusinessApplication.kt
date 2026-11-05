package com.pawmiaw.business

import android.app.Application

/** Sets up the app's context, its language and the session before the first screen. */
class PawMiawBusinessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        LocalizationManager.init()
    }
}
