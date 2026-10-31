package com.vozlivre.server

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.vozlivre.core.BaseMainActivity
import com.vozlivre.core.LogoSection
import com.vozlivre.core.R
import com.vozlivre.core.Screen
import com.vozlivre.core.Tab
import com.vozlivre.server.security.AdminSession

/** Painel do Servidor, protegido por credenciais do Administrador. */
class ServerActivity : BaseMainActivity() {
    private var gate: AdminGate? = null

    override val inlineActiveCall: Boolean get() = true
    override fun forceOverlayWhileActive(): Boolean = gate?.locked ?: true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // impede capturas de ecrã e miniaturas com o painel ou a chave de recuperação
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun buildTabs(): List<Tab> = listOf(
        Tab(R.drawable.ic_wifi, "Ligações", Screen(k, listOf(
            LogoSection(k, "Servidor"), ServerSessionSection(k, engine), ServerTilesSection(k, engine, this)))),
        Tab(R.drawable.ic_people, "Clientes", Screen(k, listOf(
            header(R.drawable.ic_people, "Clientes"), ClientsTabSection(k, engine, this)))),
        Tab(R.drawable.ic_call, "Chamadas", Screen(k, listOf(
            header(R.drawable.ic_call, "Chamadas"), CallsTabSection(k, engine, this)))),
        Tab(R.drawable.ic_pulse, "Diagnóstico", Screen(k, listOf(
            header(R.drawable.ic_pulse, "Diagnóstico"), ServerDiagnosticsSection(k, engine, this),
            PeersTableSection(k, engine), LogSection(k, engine, this)))),
        Tab(R.drawable.ic_settings, "Definições", Screen(k, listOf(
            header(R.drawable.ic_settings, "Definições avançadas"),
            SettingsListSection(k, engine, this, this) { gate?.update() })))
    )

    override fun onShellBuilt() {
        val g = AdminGate(this, k) { refreshCallState() }
        gate = g
        root.addView(g.view, root.childCount - 1, FrameLayout.LayoutParams(-1, -1))
        g.update()
    }

    override fun onStart() {
        super.onStart()
        gate?.update()
    }

    override fun onTick() { gate?.tick() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        AdminSession.touch()
        return super.dispatchTouchEvent(ev)
    }
}
