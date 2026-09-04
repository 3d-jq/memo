package com.psyche.memo

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

class MemoApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainerImpl
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainerImpl(this)
    }

    /**
     * Most icons under assets/icons are SVG, which Coil cannot decode without an
     * explicit decoder; every AsyncImage in the app resolves through this factory.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()

    companion object {
        /**
         * Readable from Activity.attachBaseContext: Application.onCreate always
         * runs before the first activity attaches, so the container (and thus
         * the stored UI language) is ready when resources get configured.
         */
        @Volatile
        var instance: MemoApplication? = null
            private set
    }
}
