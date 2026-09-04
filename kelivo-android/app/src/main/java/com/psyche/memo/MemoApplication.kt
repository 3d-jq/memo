package com.psyche.memo

import android.app.Application

class MemoApplication : Application() {

    lateinit var container: AppContainerImpl
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainerImpl(this)
    }

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
