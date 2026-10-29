package com.vozlivre.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Cartão de estado do servidor no topo de Ligações. */
class ServerOverviewSection(private val k: Kit, private val engine: CallEngine) : Section() {
    private val card = k.card(true)
    private val dot = k.dot(Colors.AMBER)
    private val title = k.text("", 18f, Colors.TEXT, true)
    private val sub = k.muted("")
    private val nets = k.mono("")
    private val count = k.pill("0 clientes")
    override val view: View get() = card

    init {
        val top = LinearLayout(k.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(dot)
        top.addView(title, k.lp(0, -2, left = 10, weight = 1f))
        top.addView(count)
        card.addView(top)
        card.addView(sub, k.lp(top = 6))
        card.addView(nets, k.lp(top = 8))
    }

    override fun refresh() {
        val up = engine.lan.hasLinks()
        k.recolorDot(dot, if (up) Colors.GREEN else Colors.AMBER)
        title.text = if (up) "Servidor ativo" else "Servidor sem rede"
        sub.text = "Nome: ${engine.displayName}  ·  Id: ${engine.myId}"
        nets.text = engine.lan.describeLinks()
        val n = engine.allPeers().count { !it.blocked }
        count.text = if (n == 1) "1 cliente" else "$n clientes"
    }
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

/** Wi-Fi Direct: criar grupo, procurar e ligar. */
class P2pSection(private val k: Kit, private val engine: CallEngine, private val host: Host, private val server: Boolean) : Section() {
    private val card = k.card()
    private val status = k.muted("")
    private val list = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_wifi, "Wi-Fi Direct"))
        card.addView(status, k.lp(top = 8))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        if (server) {
            row.addView(k.button("Criar grupo", Kit.Style.PRIMARY, true) { host.withPerms(host.p2pPerms()) { engine.p2p.createGroup() } }, k.lp(0, -2, weight = 1f))
        }
        row.addView(k.button("Procurar", Kit.Style.SECONDARY, true) { host.withPerms(host.p2pPerms()) { engine.p2p.discover() } }, k.lp(0, -2, left = if (server) 8 else 0, weight = 1f))
        row.addView(k.button("Terminar", Kit.Style.GHOST, true) { engine.p2p.disconnect() }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 10))
        card.addView(list, k.lp(top = 8))
        card.addView(k.muted(
            if (server) "Cria o grupo aqui e, no cliente, usa Procurar e Ligar. Aceita o convite no outro telemóvel."
            else "Procura o servidor, toca em Ligar e aceita o convite no outro telemóvel.", 12f
        ), k.lp(top = 6))
    }

    override fun refresh() {
        status.text = engine.p2p.status
        list.removeAllViews()
        for (d in engine.p2p.devices) {
            val r = LinearLayout(k.ctx)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.background = k.shape(Colors.SURFACE, 12, Colors.STROKE, 1)
            r.setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8))
            val st = when (d.status) {
                android.net.wifi.p2p.WifiP2pDevice.AVAILABLE -> "disponível"
                android.net.wifi.p2p.WifiP2pDevice.INVITED -> "convite enviado"
                android.net.wifi.p2p.WifiP2pDevice.CONNECTED -> "ligado"
                android.net.wifi.p2p.WifiP2pDevice.FAILED -> "falhou"
                else -> "indisponível"
            }
            val col = LinearLayout(k.ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(k.text(d.deviceName.ifBlank { "Sem nome" }, 14f, Colors.TEXT, true))
            col.addView(k.muted(st, 12f))
            r.addView(col, k.lp(0, -2, weight = 1f))
            r.addView(k.button("Ligar", Kit.Style.PRIMARY, true) { engine.p2p.connect(d) })
            list.addView(r, k.lp(bottom = 6))
        }
    }
}

/** Bluetooth: procurar e tornar visível. */
class BtSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val status = k.muted("")
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_bluetooth, "Bluetooth"))
        card.addView(status, k.lp(top = 8))
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(k.button("Procurar", Kit.Style.SECONDARY, true) { host.scanBluetooth() }, k.lp(0, -2, weight = 1f))
        row.addView(k.button("Tornar visível", Kit.Style.GHOST, true) { host.makeDiscoverable() }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 10))
        card.addView(k.muted("Os dois telemóveis precisam do VozLivre aberto. Áudio a 8 kHz e alcance de poucos metros.", 12f), k.lp(top = 8))
    }

    override fun refresh() {
        status.text = when {
            !engine.bt.available -> "Este telemóvel não tem Bluetooth"
            !engine.bt.isEnabled() -> "Bluetooth desligado. Toca em Procurar para ligar."
            else -> "Bluetooth ligado"
        }
    }
}

/** Gestão de clientes: ligar, bloquear e desbloquear. */
class ClientsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val count = k.pill("0")
    private val list = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_people, "Clientes", count))
        card.addView(list, k.lp(top = 10))
    }

    override fun refresh() {
        list.removeAllViews()
        val peers = engine.allPeers().sortedWith(compareBy({ it.blocked }, { it.kind != PeerKind.LAN }, { it.name }))
        count.text = "${peers.count { !it.blocked }} online"
        if (peers.isEmpty()) {
            list.addView(k.muted("Nenhum cliente encontrado. Abre o VozLivre em modo Cliente nos outros telemóveis."))
            return
        }
        val busy = engine.state != CallEngine.State.IDLE
        for (p in peers) {
            val call = k.button("Ligar", Kit.Style.PRIMARY, true) { host.withPerms(host.micPerms()) { engine.startCall(p) } }
            call.isEnabled = !busy && !p.blocked
            call.alpha = if (call.isEnabled) 1f else 0.4f
            val block = k.button(if (p.blocked) "Desbloquear" else "Bloquear", Kit.Style.GHOST, true) { engine.setBlocked(p.id, !p.blocked) }
            list.addView(k.peerRow(p, call, block), k.lp(bottom = 8))
        }
    }
}

/** Marcação rápida e estatísticas na aba Chamadas do servidor. */
class QuickDialSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val list = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        card.addView(k.sectionTitle(R.drawable.ic_call, "Marcação rápida"))
        card.addView(list, k.lp(top = 10))
    }

    override fun refresh() {
        list.removeAllViews()
        val peers = engine.allPeers().filter { !it.blocked }
        if (peers.isEmpty()) {
            list.addView(k.muted("Sem clientes disponíveis."))
            return
        }
        val busy = engine.state != CallEngine.State.IDLE
        for (p in peers) {
            val b = k.button("Ligar", Kit.Style.PRIMARY, true) { host.withPerms(host.micPerms()) { engine.startCall(p) } }
            b.isEnabled = !busy
            b.alpha = if (busy) 0.4f else 1f
            list.addView(k.peerRow(p, b), k.lp(bottom = 8))
        }
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
        row.addView(k.button("Limpar bloqueios", Kit.Style.GHOST, true) { engine.clearBlocked(); host.toast("Bloqueios removidos") }, k.lp(0, -2, left = 8, weight = 1f))
        card.addView(row, k.lp(top = 12))
    }
}
