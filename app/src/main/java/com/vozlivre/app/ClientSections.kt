package com.vozlivre.app

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList

/** Estado da ligação ao servidor escolhido. */
class ClientLinkStatusSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card(true)
    private val dot = k.dot(Colors.AMBER)
    private val title = k.text("", 18f, Colors.TEXT, true)
    private val sub = k.muted("")
    private val scan = k.button("Procurar servidores", Kit.Style.PRIMARY) { host.scanAll() }
    override val view: View get() = card

    init {
        val top = LinearLayout(k.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(dot)
        top.addView(title, k.lp(0, -2, left = 10, weight = 1f))
        card.addView(top)
        card.addView(sub, k.lp(top = 6))
        card.addView(scan, k.lp(top = 12))
    }

    override fun refresh() {
        val s = engine.selectedServerPeer()
        when {
            s != null -> {
                k.recolorDot(dot, Colors.GREEN)
                title.text = "Ligado a ${s.name}"
                sub.text = "${viaLabel(s)}${if (s.detail.isNotEmpty()) " · " + s.detail else ""}"
            }
            engine.serverId != null -> {
                k.recolorDot(dot, Colors.AMBER)
                title.text = "Servidor fora de alcance"
                sub.text = "O servidor escolhido não está visível. Procura de novo."
            }
            else -> {
                k.recolorDot(dot, Colors.MUTED)
                title.text = "Sem servidor escolhido"
                sub.text = "Procura e escolhe um servidor na lista abaixo."
            }
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
            val b = if (chosen) k.pill("Escolhido") else k.button("Ligar", Kit.Style.PRIMARY, true) {
                engine.selectServer(p.id)
                host.toast("Servidor: ${p.name}")
                host.goTo(1)
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

/** Cartão grande para ligar ao servidor. */
class CallServerSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card(true)
    private val holder = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        holder.orientation = LinearLayout.VERTICAL
        holder.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(holder)
    }

    override fun refresh() {
        holder.removeAllViews()
        val s = engine.selectedServerPeer()
        if (s == null) {
            holder.addView(k.muted("Escolhe primeiro um servidor no separador Ligação."), k.lp(top = 8, bottom = 8))
            holder.addView(k.button("Ir para Ligação", Kit.Style.SECONDARY) { host.goTo(0) })
            return
        }
        holder.addView(k.avatar(s.name, 88), k.lp(-2, -2, top = 8))
        holder.addView(k.text(s.name, 22f, Colors.TEXT, true), k.lp(-2, -2, top = 12))
        holder.addView(k.muted("${viaLabel(s)}${if (s.detail.isNotEmpty()) " · " + s.detail else ""}"), k.lp(-2, -2, top = 2))
        val busy = engine.state != CallEngine.State.IDLE
        val call = FrameLayout(k.ctx)
        call.background = k.shape(Colors.GREEN, 40)
        call.setOnClickListener { if (!busy) host.withPerms(host.micPerms()) { engine.startCall(s) } }
        call.alpha = if (busy) 0.4f else 1f
        k.glow(call, Colors.GREEN, 10)
        val ic = ImageView(k.ctx)
        ic.setImageResource(R.drawable.ic_call)
        ic.setColorFilter(Color.BLACK)
        call.addView(ic, FrameLayout.LayoutParams(k.dp(34), k.dp(34), Gravity.CENTER))
        holder.addView(call, k.lp(k.dp(80), k.dp(80), top = 20))
        holder.addView(k.muted("Ligar ao servidor", 13f), k.lp(-2, -2, top = 8, bottom = 4))
    }
}

/** Mosaicos de microfone e altifalante, volume e preferências. */
class AudioControlsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card()
    private val tiles = LinearLayout(k.ctx)
    private val note = k.muted("", 12f)
    private val seek = SeekBar(k.ctx)
    private val pref = k.switchRow("Altifalante por defeito", "Usa o altifalante ao atender ou ligar.", engine.defaultSpeaker) { v -> engine.setDefaultSpeaker(v) }
    override val view: View get() = card

    init {
        card.addView(k.sectionTitle(R.drawable.ic_speaker, "Controlo de áudio"))
        tiles.orientation = LinearLayout.HORIZONTAL
        card.addView(tiles, k.lp(top = 12))
        card.addView(note, k.lp(top = 8))
        card.addView(k.divider(), k.lp(top = 14, bottom = 14))
        card.addView(k.text("Volume da chamada", 14f, Colors.TEXT, true))
        seek.max = 100
        seek.progress = host.getVolume()
        seek.progressTintList = ColorStateList.valueOf(Colors.GREEN)
        seek.thumbTintList = ColorStateList.valueOf(Colors.GREEN)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Colors.STROKE)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) host.setVolume(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        card.addView(seek, k.lp(top = 8))
        card.addView(k.divider(), k.lp(top = 14, bottom = 14))
        card.addView(pref)
        card.addView(k.divider(), k.lp(top = 14, bottom = 14))
        card.addView(k.kv("Codec", "G.711 µ-law"))
        card.addView(k.kv("Taxa Wi-Fi / Bluetooth", "16 kHz / 8 kHz"), k.lp(top = 6))
        card.addView(k.kv("Cifra", "AES-256-GCM"), k.lp(top = 6))
    }

    private fun tile(icon: Int, label: String, active: Boolean, activeColor: Int, enabled: Boolean, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(k.ctx)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.setPadding(k.dp(8), k.dp(16), k.dp(8), k.dp(16))
        t.background = k.shape(if (active) Colors.alpha(activeColor, 0x22) else Colors.SURFACE, 16, if (active) activeColor else Colors.STROKE, 1)
        t.addView(k.icon(icon, if (active) activeColor else Colors.TEXT, 30))
        t.addView(k.text(label, 13f, if (active) activeColor else Colors.TEXT, true), k.lp(-2, -2, top = 8))
        t.alpha = if (enabled) 1f else 0.45f
        t.setOnClickListener { if (enabled) onClick() }
        return t
    }

    override fun refresh() {
        tiles.removeAllViews()
        val live = engine.state == CallEngine.State.ACTIVE
        tiles.addView(tile(R.drawable.ic_mic, if (engine.muted) "Microfone off" else "Microfone", engine.muted, Colors.RED, live) { engine.toggleMute() }, k.lp(0, -2, weight = 1f))
        tiles.addView(tile(R.drawable.ic_speaker, "Altifalante", engine.speaker, Colors.GREEN, live) { engine.toggleSpeaker() }, k.lp(0, -2, left = 10, weight = 1f))
        note.text = if (live) "Chamada em curso." else "Os botões ficam ativos durante uma chamada."
        val sw = pref.tag as Switch
        if (sw.isChecked != engine.defaultSpeaker) sw.isChecked = engine.defaultSpeaker
        if (!seek.isPressed) seek.progress = host.getVolume()
    }
}

/** Indicadores de estado do cliente. */
class ClientIndicatorsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val card = k.card(true)
    private val head = k.text("", 18f, Colors.TEXT, true)
    private val headDot = k.dot(Colors.AMBER)
    private val list = LinearLayout(k.ctx)
    override val view: View get() = card

    init {
        list.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(k.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(headDot)
        top.addView(head, k.lp(0, -2, left = 10, weight = 1f))
        card.addView(top)
        card.addView(list, k.lp(top = 10))
    }

    private fun item(label: String, ok: Boolean, text: String) {
        val r = LinearLayout(k.ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.addView(k.dot(if (ok) Colors.GREEN else Colors.AMBER, 12))
        r.addView(k.text(label, 14f), k.lp(0, -2, left = 10, weight = 1f))
        r.addView(k.muted(text, 12f))
        list.addView(r, k.lp(top = 10))
    }

    override fun refresh() {
        list.removeAllViews()
        val net = engine.lan.hasLinks() || engine.bt.isEnabled()
        val server = engine.selectedServerPeer() != null
        val mic = host.allGranted(host.micPerms())
        val secure = !engine.usingDefaultPassword
        item("Rede", net, if (net) "ativa" else "sem rede")
        item("Servidor", server, if (server) "visível" else "ausente")
        item("Microfone", mic, if (mic) "permitido" else "em falta")
        item("Senha", secure, if (secure) "personalizada" else "padrão")
        item("Segundo plano", engine.listenInBackground, if (engine.listenInBackground) "ligado" else "desligado")
        val ready = net && server && mic
        k.recolorDot(headDot, if (ready) Colors.GREEN else Colors.AMBER)
        head.text = if (ready) "Pronto para ligar" else "A aguardar ligação"
    }
}
