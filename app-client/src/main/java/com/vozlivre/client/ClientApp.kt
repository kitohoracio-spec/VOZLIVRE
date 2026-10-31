package com.vozlivre.client

import android.app.Application
import com.vozlivre.core.CallEngine
import com.vozlivre.core.EngineHost

class ClientApp : Application(), EngineHost {
    private lateinit var eng: CallEngine
    override val engine: CallEngine get() = eng

    override fun onCreate() {
        super.onCreate()
        eng = CallEngine(this, "client")
    }
}
