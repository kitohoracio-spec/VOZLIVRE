package com.vozlivre.server

import android.app.Application
import com.vozlivre.core.CallEngine
import com.vozlivre.core.EngineHost
import com.vozlivre.server.security.AdminStore

class ServerApp : Application(), EngineHost {
    private lateinit var eng: CallEngine
    override val engine: CallEngine get() = eng

    override fun onCreate() {
        super.onCreate()
        AdminStore.init(this)
        eng = CallEngine(this, "server")
    }
}
