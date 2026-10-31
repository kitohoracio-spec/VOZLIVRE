package com.vozlivre.server

import com.vozlivre.core.*
import com.vozlivre.server.security.AdminSession
import com.vozlivre.server.security.Perm
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

/** Recusa a ação se o administrador atual não tiver a permissão. */
fun denied(host: Host, perm: Int): Boolean {
    if (AdminSession.can(perm)) return false
    host.toast("Sem permissão para esta ação")
    return true
}

/** Inicia uma chamada só se o administrador puder operar chamadas. */
fun guardedCall(host: Host, engine: CallEngine, p: Peer) {
    if (denied(host, Perm.CALLS)) return
    host.withPerms(host.micPerms()) { engine.startCall(p) }
}

/** Interfaces de rede e ligação manual por IP. */
class NetworkSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card()
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_wifi, "Rede Wi-Fi ou hotspot"))
        card.addView(k.muted("Liga os clientes à mesma rede, ou liga o hotspot deste telemóvel. Se não se encontrarem sozinhos, escreve o IP do cliente.", 12f), k.lp(top = 8))
        card.addView(k.manualIpRow(engine, "IP do cliente, ex.: 192.168.1.23"), k.lp(top = 10))
        card.addView(k.button("Reanalisar rede", Kit.Style.GHOST, true) { engine.lan.wake() }, k.lp(-2, -2, top = 10))
    }
}

class CallStatsSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card()
    private val total = k.text("0", 13f, Colors.TEXT, true)
    private val answered = k.text("0", 13f, Colors.TEXT, true)
    private val talk = k.text("00:00", 13f, Colors.TEXT, true)
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_bars, "Estatísticas da sessão"))
        card.addView(k.kv("Chamadas", total), k.lp(top = 10))
        card.addView(k.kv("Com conversa", answered), k.lp(top = 6))
        card.addView(k.kv("Tempo total de conversa", talk), k.lp(top = 6))
    }

    override fun refresh() {
        val h = engine.history
        total.text = h.size.toString()
        answered.text = h.count { it.seconds > 0 }.toString()
        talk.text = formatSeconds(h.sumOf { it.seconds })
    }
}

/** Tabela de pares e interfaces para diagnóstico. */
class PeersTableSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card()
    private val body = k.mono("")
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_wifi, "Rede e pares"))
        card.addView(body, k.lp(top = 10))
    }

    override fun refresh() {
        val sb = StringBuilder()
        sb.append("Interfaces:\n").append(engine.lan.describeLinks()).append("\n\nPares:\n")
        val peers = engine.allPeers()
        if (peers.isEmpty()) sb.append("(nenhum)\n")
        for (p in peers) {
            sb.append(p.name).append("  ").append(p.id).append("  ").append(p.detail).append("  ")
                .append(viaLabel(p)).append("  ").append(p.role.ifEmpty { "-" })
                .append(if (p.blocked) "  [bloqueado]" else "").append('\n')
        }
        body.text = sb.toString()
    }
}

/** Registo de eventos com copiar e limpar. */
class LogSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val body = k.mono("", 10f)
    override val view: View get() = card

    init {
        host.registerLog(this)
        card.addView(k.sectionTitle(R.drawable.ic_pulse, "Registo"))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(k.button("Copiar", Kit.Style.SECONDARY, true) {
            val cm = k.ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VozLivre", engine.logText()))
            host.toast("Registo copiado")
        }, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Limpar", Kit.Style.GHOST, true) { engine.clearLog() }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 10))
        body.setTextIsSelectable(true)
        card.addView(body, k.lp(top = 10))
    }

    override fun refresh() {
        val t = engine.logText()
        body.text = if (t.length > 16000) t.substring(t.length - 16000) else t
    }
}

/** Definições avançadas: informação técnica e ações de manutenção. */
class AdvancedSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_settings, "Definições avançadas"))
        card.addView(k.kv("Porta de controlo (UDP)", "${LanTransport.CONTROL_PORT}"), k.lp(top = 10))
        card.addView(k.kv("Porta de áudio (UDP)", "${LanTransport.AUDIO_PORT}"), k.lp(top = 6))
        card.addView(k.kv("Codec", "G.711 µ-law"), k.lp(top = 6))
        card.addView(k.kv("Taxa Wi-Fi / Bluetooth", "16 kHz / 8 kHz"), k.lp(top = 6))
        card.addView(k.kv("Cifra", "AES-256-GCM"), k.lp(top = 6))
        card.addView(k.kv("Tempo limite de chamada", "40 s"), k.lp(top = 6))
        card.addView(k.kv("Perda de ligação", "9 s sem sinal"), k.lp(top = 6))
        card.addView(k.kv("Id deste servidor", engine.myId), k.lp(top = 6))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(k.button("Reiniciar motor", Kit.Style.SECONDARY, true) {
            if (engine.state != CallEngine.State.IDLE) { host.toast("Termina a chamada primeiro"); return@button }
            engine.stopAll(); engine.start(); engine.startBluetooth()
            host.toast("Motor reiniciado")
        }, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Limpar bloqueios", Kit.Style.GHOST, true) { if (!denied(host, Perm.CLIENTS)) { engine.clearBlocked(); host.toast("Bloqueios removidos") } }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 12))
    }
}


// ======================================================================= novo painel do Servidor

/** Cartão "Sessão ativa" com tempo de sessão. */
class ServerSessionSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card(true)
    private val title = k.text("", 17f, Colors.TEXT, true)
    private val dot = k.dot(Colors.AMBER, 12)
    private val status = k.text("", 13f, Colors.GREEN, true)
    private val time = k.muted("", 12f)
    override val view: View get() = card
    override val live: Boolean get() = true

    init {
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(k.iconCircle(R.drawable.ic_pulse, 44))
        val col = LinearLayout(k.ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(title)
        val st = LinearLayout(k.ctx)
        st.orientation = LinearLayout.HORIZONTAL
        st.gravity = Gravity.CENTER_VERTICAL
        st.addView(dot)
        st.addView(status, k.lp(-2, -2, left = 6))
        col.addView(st, k.lp(top = 3))
        row.addView(col, k.lp(0, -2, left = 12, weight = 1f))
        row.addView(time)
        card.addView(row)
    }

    override fun refresh() {
        val up = engine.lan.hasLinks()
        title.text = if (up) "Sessão ativa" else "Sessão sem rede"
        k.recolorDot(dot, if (up) Colors.GREEN else Colors.AMBER)
        status.text = if (up) "Online" else "Sem rede"
        status.setTextColor(if (up) Colors.GREEN else Colors.AMBER)
        time.text = "Tempo: " + formatHms(engine.sessionSeconds())
    }
}

/** Mosaicos 2x2 da visão geral. */
class ServerTilesSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val root = LinearLayout(k.ctx)
    private val vClients = k.text("0", 20f, Colors.TEXT, true)
    private val vCalls = k.text("0", 20f, Colors.TEXT, true)
    private val vPending = k.text("0", 20f, Colors.TEXT, true)
    private val vNet = k.text("", 18f, Colors.GREEN, true)
    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        val r1 = LinearLayout(k.ctx)
        r1.orientation = LinearLayout.HORIZONTAL
        val t1 = k.statTile(R.drawable.ic_people, "Clientes ligados", vClients)
        val t2 = k.statTile(R.drawable.ic_call, "Chamadas ativas", vCalls)
        t1.setOnClickListener { host.goTo(1) }
        t2.setOnClickListener { host.goTo(2) }
        r1.addView(t1, k.lp(0, -2, weight = 1f))
        r1.addView(t2, k.lp(0, -2, left = 12, weight = 1f))
        val r2 = LinearLayout(k.ctx)
        r2.orientation = LinearLayout.HORIZONTAL
        val t3 = k.statTile(R.drawable.ic_info, "Pedidos pendentes", vPending)
        val t4 = k.statTile(R.drawable.ic_wifi, "Rede", vNet)
        t3.setOnClickListener { host.goTo(1) }
        r2.addView(t3, k.lp(0, -2, weight = 1f))
        r2.addView(t4, k.lp(0, -2, left = 12, weight = 1f))
        root.addView(r1)
        root.addView(r2, k.lp(top = 12))
    }

    override fun refresh() {
        val all = engine.clients().filter { !it.blocked }
        vPending.text = all.count { engine.isPending(it) }.toString()
        vClients.text = all.count { !engine.isPending(it) }.toString()
        vCalls.text = if (engine.state == CallEngine.State.ACTIVE) "1" else "0"
        vNet.text = engine.networkLabel()
    }
}

/** Separador Clientes: Ligados, Pendentes e Histórico. */
class ClientsTabSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
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
    }

    override fun refresh() {
        val all = engine.clients().filter { !it.blocked }
        val pending = all.filter { engine.isPending(it) }
        val linked = all.filter { !engine.isPending(it) }
        segHolder.removeAllViews()
        segHolder.addView(k.segmented(listOf("Ligados (${linked.size})", "Pendentes (${pending.size})", "Histórico"), tab) { tab = it; refresh() })
        list.removeAllViews()
        val busy = engine.state != CallEngine.State.IDLE
        when (tab) {
            0 -> {
                if (linked.isEmpty()) list.addView(k.muted("Nenhum cliente encontrado. Abre o VozLivre em modo Cliente nos outros telemóveis."))
                for (p in linked) {
                    val call = k.button("Ligar", Kit.Style.PRIMARY, true) { guardedCall(host, engine, p) }
                    call.isEnabled = !busy
                    call.alpha = if (busy) 0.4f else 1f
                    list.addView(k.peerRow(p, k.pill("Ligado"), call), k.lp(bottom = 8))
                }
                list.addView(k.navRow(R.drawable.ic_people, "Gerir clientes", "Aprovação, bloqueios e autorizações") {
                    host.openSub("Gestão de clientes", R.drawable.ic_people, clientAdminPage(k, engine, host))
                }, k.lp(top = 4))
            }
            1 -> {
                if (!engine.approvalRequired) {
                    list.addView(k.muted("A aprovação manual está desligada, por isso todos os clientes ligam sem pedido. Podes ativá-la em Gerir clientes."))
                } else if (pending.isEmpty()) {
                    list.addView(k.muted("Sem pedidos pendentes."))
                }
                for (p in pending) {
                    val ok = k.button("Aceitar", Kit.Style.PRIMARY, true) { if (!denied(host, Perm.CLIENTS)) engine.approve(p.id) }
                    val no = k.button("Recusar", Kit.Style.GHOST, true) { if (!denied(host, Perm.CLIENTS)) engine.setBlocked(p.id, true) }
                    list.addView(k.peerRow(p, ok, no), k.lp(bottom = 8))
                }
            }
            else -> k.historyRows(list, engine.history.take(30))
        }
    }
}

private fun roundSmall(k: Kit, icon: Int, fill: Int, tint: Int, rotate: Float = 0f, onClick: () -> Unit): FrameLayout {
    val f = FrameLayout(k.ctx)
    f.background = k.shape(fill, 44, Colors.alpha(tint, 0x55), 1)
    val i = ImageView(k.ctx)
    i.setImageResource(icon)
    i.setColorFilter(tint)
    i.rotation = rotate
    f.addView(i, FrameLayout.LayoutParams(k.dp(22), k.dp(22), Gravity.CENTER))
    f.layoutParams = LinearLayout.LayoutParams(k.dp(46), k.dp(46))
    f.setOnClickListener { onClick() }
    return f
}

/** Separador Chamadas: Ativas, Recentes e Registos. */
class CallsTabSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val root = LinearLayout(k.ctx)
    private val segHolder = FrameLayout(k.ctx)
    private val list = LinearLayout(k.ctx)
    private var tab = 0
    override val view: View get() = root
    override val live: Boolean get() = tab == 0 && engine.state == CallEngine.State.ACTIVE

    init {
        root.orientation = LinearLayout.VERTICAL
        list.orientation = LinearLayout.VERTICAL
        root.addView(segHolder)
        root.addView(list, k.lp(top = 12))
    }

    override fun refresh() {
        val active = engine.state == CallEngine.State.ACTIVE
        segHolder.removeAllViews()
        segHolder.addView(k.segmented(listOf("Ativas (${if (active) 1 else 0})", "Recentes", "Registos"), tab) { tab = it; refresh() })
        list.removeAllViews()
        when (tab) {
            0 -> {
                val p = engine.peer
                if (active && p != null) {
                    val card = k.card(true)
                    val row = LinearLayout(k.ctx)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.addView(k.deviceTile(46, Colors.GREEN, R.drawable.ic_person))
                    val col = LinearLayout(k.ctx)
                    col.orientation = LinearLayout.VERTICAL
                    col.addView(k.text(p.name, 16f, Colors.TEXT, true))
                    val secs = ((android.os.SystemClock.elapsedRealtime() - engine.activeSince) / 1000).toInt()
                    col.addView(k.text("Em chamada • " + formatSeconds(secs), 13f, Colors.GREEN))
                    row.addView(col, k.lp(0, -2, left = 12, weight = 1f))
                    card.addView(row)
                    val tools = LinearLayout(k.ctx)
                    tools.orientation = LinearLayout.HORIZONTAL
                    tools.gravity = Gravity.END
                    tools.addView(roundSmall(k, R.drawable.ic_mic, if (engine.muted) Colors.RED else Colors.CARD_HI, if (engine.muted) Color.WHITE else Colors.TEXT) { engine.toggleMute() })
                    tools.addView(roundSmall(k, R.drawable.ic_speaker, if (engine.speaker) Colors.GREEN else Colors.CARD_HI, if (engine.speaker) Color.BLACK else Colors.TEXT) { engine.toggleSpeaker() }, k.lp(-2, -2, left = 10))
                    tools.addView(roundSmall(k, R.drawable.ic_call, Colors.RED, Color.WHITE, 135f) { engine.hangup() }, k.lp(-2, -2, left = 10))
                    card.addView(tools, k.lp(top = 12))
                    list.addView(card, k.lp(bottom = 12))
                } else {
                    list.addView(k.muted("Sem chamadas ativas."), k.lp(bottom = 12))
                }
                val others = engine.clients().filter { !it.blocked && !engine.isPending(it) && it.id != engine.peer?.id }
                if (others.isNotEmpty()) list.addView(k.text("Disponíveis", 15f, Colors.TEXT, true), k.lp(bottom = 8))
                val busy = engine.state != CallEngine.State.IDLE
                for (o in others) {
                    val b = k.button("Ligar", Kit.Style.PRIMARY, true) { guardedCall(host, engine, o) }
                    b.isEnabled = !busy
                    b.alpha = if (busy) 0.4f else 1f
                    list.addView(k.peerRow(o, b), k.lp(bottom = 8))
                }
            }
            1 -> k.historyRows(list, engine.history.take(30))
            else -> {
                val lines = engine.logText().lines().filter { it.contains("hamada") }.takeLast(60)
                list.addView(k.mono(if (lines.isEmpty()) "Sem registos de chamadas." else lines.joinToString("\n"), 10f))
            }
        }
    }
}

/** Verificação do sistema com estado geral e lista de testes. */
class ServerDiagnosticsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private class Check(val label: String, val icon: Int, val level: Int)

    private val root = LinearLayout(k.ctx)
    private val big = FrameLayout(k.ctx)
    private val bigIcon = ImageView(k.ctx)
    private val headline = k.text("", 18f, Colors.TEXT, true)
    private val sub = k.text("", 13f, Colors.GREEN)
    private val list = k.card()
    override val view: View get() = root

    init {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        big.addView(bigIcon, FrameLayout.LayoutParams(k.dp(44), k.dp(44), Gravity.CENTER))
        root.addView(big, k.lp(k.dp(92), k.dp(92), top = 4))
        headline.gravity = Gravity.CENTER
        sub.gravity = Gravity.CENTER
        root.addView(headline, k.lp(top = 12))
        root.addView(sub, k.lp(top = 2, bottom = 14))
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)
        root.addView(k.button("Executar diagnóstico", Kit.Style.SECONDARY) {
            engine.lan.wake()
            val checks = evaluate()
            engine.log("Diagnóstico executado: " + checks.joinToString(", ") { it.label + "=" + levelText(it.level) })
            refresh()
            host.toast("Diagnóstico concluído")
        }, k.lp(top = 14))
    }

    private fun levelText(l: Int) = when (l) { 0 -> "OK"; 1 -> "Atenção"; else -> "Falha" }
    private fun levelColor(l: Int) = when (l) { 0 -> Colors.GREEN; 1 -> Colors.AMBER; else -> Colors.RED }

    private fun evaluate(): List<Check> {
        val net = engine.lan.hasLinks() || engine.bt.isEnabled()
        val mic = host.allGranted(host.micPerms())
        val others = host.allGranted(host.notifPerms())
        return listOf(
            Check("Rede (${engine.networkLabel()})", R.drawable.ic_wifi, if (net) 0 else 2),
            Check("Descoberta de dispositivos", R.drawable.ic_people, if (engine.lan.hasLinks()) 0 else 2),
            Check("Permissões", R.drawable.ic_shield, if (!mic) 2 else if (!others) 1 else 0),
            Check("Sinalização de chamadas", R.drawable.ic_call, if (net) 0 else 2),
            Check("Áudio", R.drawable.ic_speaker, if (mic) 0 else 2)
        )
    }

    override fun refresh() {
        val checks = evaluate()
        val worst = checks.maxOf { it.level }
        val color = levelColor(worst)
        big.background = k.shape(Colors.alpha(color, 0x1A), 92, color, 3)
        k.glow(big, color, 8)
        bigIcon.setImageResource(if (worst == 0) R.drawable.ic_check else R.drawable.ic_info)
        bigIcon.setColorFilter(color)
        headline.text = when (worst) { 0 -> "Sistema operacional"; 1 -> "Atenção necessária"; else -> "Falhas detetadas" }
        sub.text = if (worst == 0) "Tudo a funcionar" else "Verifica os itens assinalados"
        sub.setTextColor(color)
        list.removeAllViews()
        for ((i, c) in checks.withIndex()) {
            val r = LinearLayout(k.ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(k.iconCircle(c.icon, 34, levelColor(c.level)))
            r.addView(k.text(c.label, 14f), k.lp(0, -2, left = 12, weight = 1f))
            r.addView(k.text(levelText(c.level), 13f, levelColor(c.level), true))
            list.addView(r, k.lp(top = if (i == 0) 0 else 10))
        }
    }
}

// ----------------------------------------------------------------------- definições avançadas em subpáginas

class ClientAdminSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val list = LinearLayout(k.ctx)
    private val approval = k.switchRow("Aprovar clientes manualmente", "Novos clientes ficam pendentes até os aceitares.", engine.approvalRequired) { v ->
        if (!denied(host, Perm.CLIENTS) && engine.approvalRequired != v) engine.setApprovalRequired(v)
    }
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_people, "Autorizações e bloqueios"))
        card.addView(approval, k.lp(top = 10))
        card.addView(k.divider(), k.lp(top = 12, bottom = 12))
        card.addView(list)
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(k.button("Limpar bloqueios", Kit.Style.GHOST, true) { if (!denied(host, Perm.CLIENTS)) { engine.clearBlocked(); host.toast("Bloqueios removidos") } }, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Limpar aprovações", Kit.Style.GHOST, true) { if (!denied(host, Perm.CLIENTS)) { engine.clearApprovals(); host.toast("Aprovações removidas") } }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 12))
    }

    override fun refresh() {
        val sw = approval.tag as Switch
        if (sw.isChecked != engine.approvalRequired) sw.isChecked = engine.approvalRequired
        list.removeAllViews()
        val peers = engine.clients()
        if (peers.isEmpty()) { list.addView(k.muted("Nenhum cliente visível.")); return }
        for (p in peers) {
            val tag = when {
                p.blocked -> k.pill("Bloqueado", Colors.RED)
                engine.isPending(p) -> k.pill("Pendente", Colors.AMBER)
                else -> k.pill("Ativo")
            }
            val b = k.button(if (p.blocked) "Desbloquear" else "Bloquear", Kit.Style.GHOST, true) { if (!denied(host, Perm.CLIENTS)) engine.setBlocked(p.id, !p.blocked) }
            list.addView(k.peerRow(p, tag, b), k.lp(bottom = 8))
        }
    }
}

class CallRulesSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card()
    private val approvalValue = k.text("", 13f, Colors.TEXT, true)
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_call, "Regras de chamada"))
        card.addView(k.kv("Chamadas em simultâneo", "1"), k.lp(top = 10))
        card.addView(k.kv("Tempo limite sem resposta", "40 s"), k.lp(top = 6))
        card.addView(k.kv("Repetição do pedido de chamada", "1 s, depois 2 s"), k.lp(top = 6))
        card.addView(k.kv("Perda de ligação", "9 s sem sinal"), k.lp(top = 6))
        card.addView(k.kv("Chamada recebida sem atender", "6 s sem repetição"), k.lp(top = 6))
        card.addView(k.kv("Clientes novos", approvalValue), k.lp(top = 6))
    }

    override fun refresh() {
        approvalValue.text = if (engine.approvalRequired) "Aprovação manual" else "Aceites automaticamente"
    }
}

fun clientAdminPage(k: Kit, engine: CallEngine, host: Host): List<Section> = listOf(ClientAdminSection(k, engine, host))

/** Lista de subpáginas de Definições avançadas, filtrada pelas permissões do administrador. */
class SettingsListSection(
    private val k: Kit,
    private val engine: CallEngine,
    private val host: Host,
    private val act: android.app.Activity,
    private val onLock: () -> Unit
) : Section() {
    private val root = LinearLayout(k.ctx)
    override val view: View get() = root

    init { root.orientation = LinearLayout.VERTICAL }

    private fun row(perm: Int, icon: Int, title: String, sub: String, build: () -> List<Section>) {
        if (perm != 0 && !AdminSession.can(perm)) return
        root.addView(k.navRow(icon, title, sub) { host.openSub(title, icon, build()) }, k.lp(bottom = 10))
    }

    override fun refresh() {
        root.removeAllViews()
        row(Perm.NETWORK, R.drawable.ic_wifi, "Gestão da rede", "Wi-Fi, Wi-Fi Direct, Bluetooth e portas") {
            listOf(NetworkSection(k, engine), P2pSection(k, engine, host, true), BtSection(k, engine, host), AdvancedSection(k, engine, host))
        }
        row(Perm.CLIENTS, R.drawable.ic_people, "Gestão de clientes", "Autorizações e bloqueios") { clientAdminPage(k, engine, host) }
        row(Perm.CALLS, R.drawable.ic_call, "Gestão de chamadas", "Regras, estatísticas e eventos") {
            listOf(CallRulesSection(k, engine), CallStatsSection(k, engine), HistorySection(k, engine, 50))
        }
        row(0, R.drawable.ic_speaker, "Áudio", "Volume, microfone, saída e qualidade") { listOf(AudioSettingsSection(k, engine, host)) }
        row(0, R.drawable.ic_shield, "Segurança", "Sessão, senha, chave de recuperação e permissões") {
            val l = ArrayList<Section>()
            l.add(AdminSecuritySection(k, act, host, onLock))
            if (AdminSession.can(Perm.SECURITY)) l.add(IdentitySection(k, engine, host))
            l.add(PermissionsSection(k, host))
            l
        }
        row(Perm.MANAGE_ADMINS, R.drawable.ic_person, "Administradores", "Contas, perfis e permissões") { listOf(AdminAccountsSection(k, act, host)) }
        row(Perm.AUDIT, R.drawable.ic_doc, "Auditoria", "Acessos e alterações administrativas") { listOf(AuditSection(k, host)) }
        row(0, R.drawable.ic_pulse, "Segundo plano", "Escuta e bateria") { listOf(BackgroundSection(k, engine, host)) }
        row(0, R.drawable.ic_doc, "Registos e logs", "Eventos técnicos e pares da rede") {
            listOf(LogSection(k, engine, host), PeersTableSection(k, engine))
        }
        row(0, R.drawable.ic_info, "Sobre", "Informações da aplicação") { listOf(CreditsSection(k)) }
    }
}
