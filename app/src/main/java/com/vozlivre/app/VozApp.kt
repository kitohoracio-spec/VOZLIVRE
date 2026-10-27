package com.vozlivre.app

import android.app.Application

/** O motor de chamadas vive aqui para sobreviver à Activity. */
class VozApp : Application() {
    lateinit var engine: CallEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = CallEngine(this)
    }
}
