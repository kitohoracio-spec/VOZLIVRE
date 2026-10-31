package com.vozlivre.client

import com.vozlivre.core.*
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Cartão "Ligado ao servidor" com botão Desligar. */
class ClientConnectionSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card(true)
    private val content = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        content.orientation = LinearLayout.VERTICAL
        card.addView(content)
    }

    override fun refresh() {
        content.removeAllViews()
        val s = engine.selectedServerPeer()
        if (s != null) {
            content.addView(k.text("Ligado ao servidor", 18f, Colors.TEXT, true))
            val row = LinearLayout(k.ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(k.deviceTile(46, Colors.GREEN, R.drawable.ic_person))
            val col = LinearLayout(k.ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(k.text(s.name, 16f, Colors.TEXT, true))
            val ipRow = LinearLayout(k.ctx)
            ipRow.orientation = LinearLayout.HORIZONTAL
            ipRow.gravity = Gravity.CENTER_VERTICAL
            ipRow.addView(k.icon(R.drawable.ic_wifi, Colors.GREEN, 14))
            ipRow.addView(k.muted(if (s.detail.isNotEmpty()) s.detail else viaLabel(s), 13f), k.lp(-2, -2, left = 6))
            col.addView(ipRow, k.lp(top = 2))
            row.addView(col, k.lp(0, -2, left = 12, weight = 1f))
            content.addView(row, k.lp(top = 12))
            content.addView(k.button("Desligar") { engine.selectServer(null); host.toast("Desligado do servidor") }, k.lp(top = 14))
        } else if (engine.serverId != null) {
            content.addView(k.text("Servidor fora de alcance", 18f, Colors.AMBER, true))
            content.addView(k.muted("O servidor escolhido não está visível. Procura de novo."), k.lp(top = 6))
            content.addView(k.button("Procurar servidores") { host.scanAll() }, k.lp(top = 14))
        } else {
            content.addView(k.text("Sem servidor escolhido", 18f, Colors.TEXT, true))
            content.addView(k.muted("Procura e escolhe um servidor na lista abaixo."), k.lp(top = 6))
            content.addView(k.button("Procurar servidores") { host.scanAll() }, k.lp(top = 14))
        }
    }
}

/** Lista de servidores encontrados (Wi-Fi com papel de servidor e dispositivos Bluetooth). */
class ServerListSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val list = LinearLayout(k.ctx)
    private val count = k.pill("0")
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_server, "Servidores encontrados", count))
        card.addView(list, k.lp(top = 10))
        card.addView(k.muted("Não aparece? Escreve o IP do servidor:", 12f), k.lp(top = 8))
        card.addView(k.manualIpRow(engine, "IP do servidor"), k.lp(top = 8))
    }

    override fun refresh() {
        list.removeAllViews()
        val servers = engine.allPeers().filter { !it.blocked && (it.kind == PeerKind.BLUETOOTH || it.role == "S") }
        count.text = servers.size.toString()
        if (servers.isEmpty()) {
            list.addView(k.muted("Nenhum servidor visível. Confirma que o servidor está ligado à mesma rede."))
            return
        }
        for (p in servers) {
            val chosen = engine.serverId == p.id
            val b: View = if (chosen) k.pill("Escolhido") else k.button("Ligar", Kit.Style.PRIMARY, true) {
                engine.selectServer(p.id)
                host.toast("Servidor: ${p.name}")
            }
            list.addView(k.peerRow(p, b), k.lp(bottom = 8))
        }
    }
}

/** Outros métodos de ligação, recolhidos por defeito para manter o ecrã simples. */
class OtherMethodsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val body = LinearLayout(k.ctx)
    private val p2p = P2pSection(k, engine, host, false)
    private val bt = BtSection(k, engine, host)
    private val toggle: TextView
    override val view: View get() = card

    init {
        body.orientation = LinearLayout.VERTICAL
        body.visibility = View.GONE
        body.addView(p2p.view, k.lp(top = 12))
        body.addView(bt.view, k.lp(top = 12))
        toggle = k.pill("Mostrar")
        card.addView(k.sectionTitle(R.drawable.ic_wifi, "Wi-Fi Direct e Bluetooth", toggle))
        card.addView(body)
        card.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Esconder" else "Mostrar"
            refresh()
        }
    }

    override fun refresh() {
        if (body.visibility == View.VISIBLE) { p2p.refresh(); bt.refresh() }
    }
}

/** Separador Chamadas do cliente: Recentes e Favoritos, com botão Nova chamada. */
class ClientCallsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val root = LinearLayout(k.ctx)
    private val segHolder = FrameLayout(k.ctx)
    private val list = LinearLayout(k.ctx)
    private var tab = 0
    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        list.orientation = LinearLayout.VERTICAL
        root.addView(segHolder)
        root.addView(list, k.lp(top = 12))
        root.addView(k.button("Nova chamada") { newCall() }, k.lp(top = 8))
    }

    private fun newCall() {
        val s = engine.selectedServerPeer()
        if (s == null) {
            host.toast("Escolhe primeiro um servidor")
            host.goTo(0)
        } else {
            host.withPerms(host.micPerms()) { engine.startCall(s) }
        }
    }

    private fun callBack(id: String) {
        val p = engine.allPeers().firstOrNull { it.id == id }
        if (p == null) host.toast("Fora de alcance") else host.withPerms(host.micPerms()) { engine.startCall(p) }
    }

    private fun row(id: String, name: String, sub: String): LinearLayout {
        val r = LinearLayout(k.ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.background = k.shape(Colors.CARD, 14, Colors.STROKE, 1)
        r.setPadding(k.dp(12), k.dp(10), k.dp(12), k.dp(10))
        r.addView(k.deviceTile(42, Colors.GREEN, R.drawable.ic_person))
        val col = LinearLayout(k.ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(k.text(name, 15f, Colors.TEXT, true))
        col.addView(k.muted(sub, 12f))
        r.addView(col, k.lp(0, -2, left = 12, weight = 1f))
        val star = ImageView(k.ctx)
        star.setImageResource(R.drawable.ic_star)
        star.setColorFilter(if (engine.isFavorite(id)) Colors.GREEN else Colors.MUTED)
        star.setPadding(k.dp(6), k.dp(6), k.dp(6), k.dp(6))
        star.setOnClickListener { engine.toggleFavorite(id, name) }
        r.addView(star, k.lp(k.dp(36), k.dp(36)))
        val phone = FrameLayout(k.ctx)
        phone.background = k.shape(Colors.alpha(Colors.GREEN, 0x1F), 40, Colors.alpha(Colors.GREEN, 0x88), 1)
        val pi = ImageView(k.ctx)
        pi.setImageResource(R.drawable.ic_call)
        pi.setColorFilter(Colors.GREEN)
        phone.addView(pi, FrameLayout.LayoutParams(k.dp(18), k.dp(18), Gravity.CENTER))
        phone.setOnClickListener { callBack(id) }
        r.addView(phone, k.lp(k.dp(40), k.dp(40), left = 6))
        return r
    }

    override fun refresh() {
        segHolder.removeAllViews()
        segHolder.addView(k.segmented(listOf("Recentes", "Favoritos"), tab) { tab = it; refresh() })
        list.removeAllViews()
        if (tab == 0) {
            val items = engine.history.take(20)
            if (items.isEmpty()) list.addView(k.muted("Ainda não há chamadas nesta sessão."))
            for (h in items) list.addView(row(h.peerId, h.peerName, formatWhen(h.at)), k.lp(bottom = 8))
        } else {
            val favs = engine.favoriteList()
            if (favs.isEmpty()) list.addView(k.muted("Toca na estrela de uma chamada recente para a guardar aqui."))
            for ((id, name) in favs) {
                val visible = engine.allPeers().any { it.id == id }
                list.addView(row(id, name, if (visible) "Disponível" else "Fora de alcance"), k.lp(bottom = 8))
            }
        }
    }
}

/** Separador Estado: informações da ligação. */
class ClientStatusSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val root = LinearLayout(k.ctx)
    private val vServer = k.text("", 15f, Colors.GREEN, true)
    private val vType = k.text("", 15f, Colors.TEXT, true)
    private val vIp = k.text("", 15f, Colors.TEXT, true)
    private val vSignal = k.text("", 15f, Colors.TEXT, true)
    private val vTime = k.text("", 15f, Colors.TEXT, true)
    override val view: View get() = root
    override val live: Boolean get() = true

    init {
        root.orientation = LinearLayout.VERTICAL
        root.addView(k.infoRow(R.drawable.ic_server, "Servidor", vServer), k.lp(bottom = 10))
        root.addView(k.infoRow(R.drawable.ic_wifi, "Tipo de ligação", vType), k.lp(bottom = 10))
        root.addView(k.infoRow(R.drawable.ic_device, "IP local", vIp), k.lp(bottom = 10))
        root.addView(k.infoRow(R.drawable.ic_bars, "Força do sinal", vSignal), k.lp(bottom = 10))
        root.addView(k.infoRow(R.drawable.ic_clock, "Tempo de ligação", vTime))
    }

    override fun refresh() {
        val s = engine.selectedServerPeer()
        vServer.text = if (s != null) "Conectado" else if (engine.serverId != null) "Fora de alcance" else "Desligado"
        vServer.setTextColor(if (s != null) Colors.GREEN else Colors.AMBER)
        vType.text = if (s != null) viaLabel(s) else engine.networkLabel()
        vIp.text = engine.lan.primaryIp()
        vSignal.text = engine.wifiSignal()
        val t = engine.serverLinkSeconds()
        vTime.text = if (t >= 0) formatHms(t) else "-"
    }
}
