package com.vozlivre.server.security

import android.os.SystemClock

/** Sessão administrativa em memória: expira por inatividade e é revogada quando a época muda. */
object AdminSession {
    @Volatile private var account: AdminAccount? = null
    @Volatile private var epoch = 0
    @Volatile private var lastTouch = 0L

    fun start(a: AdminAccount) {
        account = a
        epoch = AdminStore.epoch
        lastTouch = SystemClock.elapsedRealtime()
    }

    private fun valid(a: AdminAccount): Boolean {
        val timeout = AdminStore.sessionTimeoutMin * 60_000L
        val live = AdminStore.find(a.id)
        return epoch == AdminStore.epoch && SystemClock.elapsedRealtime() - lastTouch <= timeout && live != null && live.status == Status.ACTIVE
    }

    /** Conta com sessão válida, ou null (e encerra a sessão se expirou). */
    fun current(): AdminAccount? {
        val a = account ?: return null
        if (!valid(a)) { end("expirada"); return null }
        return a
    }

    fun touch() { if (account != null) lastTouch = SystemClock.elapsedRealtime() }

    fun end(reason: String) {
        val a = account
        account = null
        if (a != null) AdminStore.auditEvent("LOGOUT", a.username, reason)
    }

    fun can(perm: Int): Boolean {
        val a = current() ?: return false
        return a.perms and perm != 0
    }
}
