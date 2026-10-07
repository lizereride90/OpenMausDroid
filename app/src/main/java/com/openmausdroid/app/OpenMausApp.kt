package com.openmausdroid.app

import android.app.Application
import com.openmausdroid.app.core.Omb
import com.openmausdroid.app.core.Prefs
import com.openmausdroid.app.core.Proot
import com.openmausdroid.app.core.Runtime

class OpenMausApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Proot.init(this)
        Omb.init(this)
        Runtime.init(this)
    }

    companion object {
        lateinit var instance: OpenMausApp
            private set
    }
}
