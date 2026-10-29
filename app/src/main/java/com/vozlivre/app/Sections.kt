package com.vozlivre.app

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Um ecrã da barra inferior: uma lista de secções dentro de um ScrollView. */
class Screen(k: Kit, private val sections: List<Section>) {
    val view: ScrollView = ScrollView(k.ctx)

    init {
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        val col = LinearLayout(k.ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(k.dp(16), k.dp(8), k.dp(16), k.dp(24))
        for (s in sections) col.addView(s.view, k.lp(bottom = 12))
        view.addView(col)
    }

    fun refresh() { for (s in sections) s.refresh() }
}

fun viaLabel(p: Peer): String = when {
    p.kind == PeerKind.BLUETOOTH -> "Bluetooth"
    p.isP2p -> "Wi-Fi Direct"
    else -> "Wi-Fi"
}

fun formatSeconds(s: Int): String = "%02d:%02d".format(s / 60, s % 60)

fun Kit.peerRow(p: Peer, vararg actions: View): LinearLayout {
    val row = LinearLayout(ctx)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    row.background = shape(Colors.SURFACE, 14, Colors.STROKE, 1)
    row.setPadding(dp(12), dp(10), dp(12), dp(10))
    row.alpha = if (p.blocked) 0.5f else 1f
    row.addView(avatar(p.name, 40, if (p.blocked) Colors.MUTED else Colors.GREEN))
    val col = LinearLayout(ctx)
    col.orientation = LinearLayout.VERTICAL
    col.addView(text(p.name, 15f, Colors.TEXT, true))
    val sub = StringBuilder(viaLabel(p))
    if (p.detail.isNotEmpty()) sub.append(" · ").append(p.detail)
    if (p.role == "S") sub.append(" · Servidor")
    if (p.role == "C") sub.append(" · Cliente")
    col.addView(muted(sub.toString(), 12f))
    row.addView(col, lp(0, -2, left = 12, weight = 1f))
    for (a in actions) row.addView(a, lp(-2, -2, left = 6))
    return row
}

fun Kit.manualIpRow(engine: CallEngine, hint: String): LinearLayout {
    val ip = input(hint)
    return inputRow(ip, button("Procurar", Kit.Style.SECONDARY) {
        val v = ip.text.toString().trim()
        if (v.isNotEmpty()) engine.lan.probe(v)
    })
}

/** Nome e senha das chamadas. */
class IdentitySection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val name = k.input("O teu nome")
    private val pw = k.input("Nova senha das chamadas", true)
    private val info = k.muted("", 12f)
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_shield, "Identificação e segurança"))
        name.setText(engine.displayName)
        card.addView(k.inputRow(name, k.button("Guardar", Kit.Style.SECONDARY) {
            engine.setName(name.text.toString())
            host.toast("Nome guardado")
        }), k.lp(top = 12))
        card.addView(k.inputRow(pw, k.button("Guardar", Kit.Style.SECONDARY) {
            engine.setPassword(pw.text.toString())
            pw.setText("")
            host.toast("Senha guardada")
            refresh()
        }), k.lp(top = 8))
        card.addView(info, k.lp(top = 8))
        card.addView(k.muted("Cifra AES-256-GCM. Os dois telemóveis precisam da mesma senha.", 12f), k.lp(top = 4))
    }

    override fun refresh() {
        if (engine.usingDefaultPassword) {
            info.text = "Senha padrão ativa: qualquer VozLivre na rede pode decifrar."
            info.setTextColor(Colors.AMBER)
        } else {
            info.text = "Senha personalizada definida."
            info.setTextColor(Colors.GREEN)
        }
    }
}

/** Escuta em segundo plano e otimização de bateria. */
class BackgroundSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val row = k.switchRow("Receber chamadas em segundo plano", "Mantém a app à escuta com o ecrã apagado. Gasta mais bateria.", engine.listenInBackground) { v ->
        if (engine.listenInBackground != v) engine.setListen(v)
    }
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_pulse, "Segundo plano"))
        card.addView(row, k.lp(top = 10))
        card.addView(k.button("Definições de bateria", Kit.Style.GHOST, true) { host.openBatterySettings() }, k.lp(-2, -2, top = 10))
    }

    override fun refresh() {
        val sw = row.tag as Switch
        if (sw.isChecked != engine.listenInBackground) sw.isChecked = engine.listenInBackground
    }
}

/** Estado das permissões com pedido direto. */
class PermissionsSection(private val k: Kit, private val host: Host) : Section() {
    private val card = k.card()
    private val list = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_shield, "Permissões"))
        card.addView(list, k.lp(top = 4))
    }

    private fun item(label: String, perms: List<String>) {
        if (perms.isEmpty()) return
        val ok = host.allGranted(perms)
        val r = LinearLayout(k.ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(k.text(label, 14f), k.lp(0, -2, weight = 1f))
        r.addView(k.pill(if (ok) "Concedida" else "Em falta", if (ok) Colors.GREEN else Colors.AMBER))
        if (!ok) r.addView(k.button("Pedir", Kit.Style.GHOST, true) { host.withPerms(perms) { } }, k.lp(-2, -2, left = 8))
        list.addView(r, k.lp(top = 8))
    }

    override fun refresh() {
        list.removeAllViews()
        item("Microfone", host.micPerms())
        item("Notificações", host.notifPerms())
        item("Bluetooth", host.btPerms())
        item("Wi-Fi Direct", host.p2pPerms())
    }
}

/** Histórico de chamadas da sessão. */
class HistorySection(private val k: Kit, private val engine: CallEngine, private val limit: Int, private val title: String = "Histórico") : Section() {
    private val card = k.card()
    private val list = LinearLayout(k.ctx)
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_call, title))
        card.addView(list, k.lp(top = 6))
    }

    override fun refresh() {
        list.removeAllViews()
        val items = engine.history.take(limit)
        if (items.isEmpty()) {
            list.addView(k.muted("Ainda não há chamadas nesta sessão."), k.lp(top = 6))
            return
        }
        for (h in items) {
            val r = LinearLayout(k.ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(k.ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(k.text(h.peerName, 14f, Colors.TEXT, true))
            col.addView(k.muted("${fmt.format(Date(h.at))} · ${if (h.seconds > 0) formatSeconds(h.seconds) else "sem conversa"} · ${h.result}", 12f))
            r.addView(col, k.lp(0, -2, weight = 1f))
            r.addView(k.pill(if (h.incoming) "Recebida" else "Efetuada", if (h.incoming) Colors.AMBER else Colors.GREEN))
            list.addView(r, k.lp(top = 10))
        }
    }
}

/** Botão de mudar entre Servidor e Cliente. */
class ModeSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val label = k.muted("")
    private val btn = k.button("Mudar de modo", Kit.Style.GHOST) { host.changeMode() }
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_server, "Modo da app"))
        card.addView(label, k.lp(top = 8))
        card.addView(btn, k.lp(top = 10))
    }

    override fun refresh() {
        label.text = if (engine.mode == "server") "Este telemóvel está em modo Servidor." else "Este telemóvel está em modo Cliente."
    }
}

/** Sobre / Créditos com a assinatura do projeto. */
class CreditsSection(private val k: Kit) : Section() {
    private val card = k.card(true)
    override val view: View get() = card

    init {
        val version = try { k.ctx.packageManager.getPackageInfo(k.ctx.packageName, 0).versionName } catch (e: Exception) { "" }
        card.addView(k.sectionTitle(R.drawable.ic_pulse, "Sobre / Créditos"))
        val word = k.text("VozLivre", 26f, Colors.GREEN, true)
        card.addView(word, k.lp(top = 12))
        card.addView(k.text(Brand.SIGNATURE, 14f, Colors.TEXT, true), k.lp(top = 4))
        card.addView(k.muted("Chamadas de voz sem Internet por Wi-Fi, Wi-Fi Direct e Bluetooth, com áudio cifrado.", 13f), k.lp(top = 8))
        card.addView(k.muted("Versão $version", 12f), k.lp(top = 8))
    }
}
