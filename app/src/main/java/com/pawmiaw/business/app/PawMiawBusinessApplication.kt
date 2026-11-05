package com.pawmiaw.business

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

/** Sets up the app's context, its language and the session before the first screen. */
class PawMiawBusinessApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppContext.init(this)
        LocalizationManager.init()
    }

    /** Coil reads the SVG artwork too (Olfato AI's corgi in the sidebar, `file:///android_asset/img/…`). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(SvgDecoder.Factory()) }
            .build()
}
