package com.vozlivre.core

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

/** Volume, microfone, saída de áudio e dados de qualidade. */
class AudioSettingsSection(private val k: Kit, private val engine: CallEngine, private val host: Host) : Section() {
    private val root = LinearLayout(k.ctx)
    private val volValue = k.text("", 14f, Colors.TEXT, true)
    private val micValue = k.text("", 14f, Colors.TEXT, true)
    private val outValue = k.text("", 14f, Colors.TEXT)
    private val volSeek = SeekBar(k.ctx)
    private val micSeek = SeekBar(k.ctx)
    override val view: View get() = root

    private fun styleSeek(s: SeekBar) {
        s.max = 100
        s.progressTintList = ColorStateList.valueOf(Colors.GREEN)
        s.thumbTintList = ColorStateList.valueOf(Colors.GREEN)
        s.progressBackgroundTintList = ColorStateList.valueOf(Colors.STROKE)
    }

    private fun control(icon: Int, label: String, value: TextView, seek: SeekBar): LinearLayout {
        val c = k.card()
        val top = LinearLayout(k.ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(k.text(label, 15f, Colors.TEXT, true), k.lp(0, -2, weight = 1f))
        top.addView(value)
        c.addView(top)
        val row = LinearLayout(k.ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(k.icon(icon, Colors.GREEN, 20))
        row.addView(seek, k.lp(0, -2, left = 8, weight = 1f))
        c.addView(row, k.lp(top = 8))
        return c
    }

    init {
        root.orientation = LinearLayout.VERTICAL
        styleSeek(volSeek)
        styleSeek(micSeek)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                volValue.text = "$p%"
                if (fromUser) host.setVolume(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        micSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                micValue.text = "$p%"
                if (fromUser) engine.setMicPercent(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(control(R.drawable.ic_speaker, "Volume do altifalante", volValue, volSeek), k.lp(bottom = 12))
        root.addView(control(R.drawable.ic_mic, "Microfone", micValue, micSeek), k.lp(bottom = 4))
        root.addView(k.muted("No microfone, 50% é o ganho normal e 100% duplica o nível.", 12f), k.lp(bottom = 12))

        val out = k.card()
        out.addView(k.text("Saída de áudio", 15f, Colors.TEXT, true))
        val pick = LinearLayout(k.ctx)
        pick.orientation = LinearLayout.HORIZONTAL
        pick.gravity = Gravity.CENTER_VERTICAL
        pick.background = k.shape(Colors.SURFACE, 12, Colors.STROKE, 1)
        pick.setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
        pick.addView(outValue, k.lp(0, -2, weight = 1f))
        val arrow = k.icon(R.drawable.ic_chevron, Colors.MUTED, 18)
        arrow.rotation = 90f
        pick.addView(arrow)
        pick.setOnClickListener { chooseOutput() }
        out.addView(pick, k.lp(top = 10))
        root.addView(out, k.lp(bottom = 12))

        val info = k.card()
        info.addView(k.sectionTitle(R.drawable.ic_info, "Qualidade"))
        info.addView(k.kv("Codec", "G.711 µ-law"), k.lp(top = 10))
        info.addView(k.kv("Taxa Wi-Fi / Bluetooth", "16 kHz / 8 kHz"), k.lp(top = 6))
        info.addView(k.kv("Cifra", "AES-256-GCM"), k.lp(top = 6))
        root.addView(info)
    }

    private fun chooseOutput() {
        val items = arrayOf("Altifalante do telemóvel", "Auricular")
        AlertDialog.Builder(k.ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Saída de áudio")
            .setSingleChoiceItems(items, if (engine.defaultSpeaker) 0 else 1) { d, which ->
                val sp = which == 0
                engine.setDefaultSpeaker(sp)
                if (engine.state == CallEngine.State.ACTIVE && engine.speaker != sp) engine.toggleSpeaker()
                d.dismiss()
                refresh()
            }
            .show()
    }

    override fun refresh() {
        if (!volSeek.isPressed) { volSeek.progress = host.getVolume(); volValue.text = "${volSeek.progress}%" }
        if (!micSeek.isPressed) { micSeek.progress = engine.micPercent; micValue.text = "${engine.micPercent}%" }
        outValue.text = if (engine.defaultSpeaker) "Altifalante do telemóvel" else "Auricular"
    }
}

