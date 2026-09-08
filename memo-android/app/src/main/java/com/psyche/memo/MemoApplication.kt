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
        // Startup provider seeding (Flutter SettingsProvider.ensureProviderConfig
        // per-key fill-missing pass): builtin keys without a provider_rows row
        // get their pristine default config (baseUrl/label/enabled verbatim
        // from ProviderConfig.defaultsFor). Idempotent.
        container.providerRepository.ensureBuiltinDefaultsSeeded()
        // PDFBox needs its resource loader before the first PDF extraction.
        com.psyche.memo.provider.DocumentTextExtractor.init(this)
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
